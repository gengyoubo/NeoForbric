package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.*;
import org.neoforbric.forge.*;
import org.neoforbric.minecraft.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class ForgeAnchorsTest {
    private Map<String, byte[]> nativeClasses() throws Exception {
        Path libraries = Path.of(System.getProperty("forge.testDirectory", ""), "libraries");
        Assumptions.assumeTrue(Files.isDirectory(libraries), "Pinned Forge inputs not prepared");
        Map<String, byte[]> classes = new HashMap<>();
        try (var paths = Files.walk(libraries)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".jar")).toList()) try (var jar = new JarFile(path.toFile())) {
                for (String name : ForgeAnchors.lock().getAsJsonObject("classes").keySet()) {
                    var entry = jar.getJarEntry(name.replace('.', '/') + ".class");
                    if (entry != null) try (var input = jar.getInputStream(entry)) { classes.put(name, input.readAllBytes()); }
                }
            }
        }
        return classes;
    }
    @Test void realPinnedClassesMatchHashesAndMethodDescriptors() throws Exception {
        var classes = nativeClasses(); assertEquals(50, classes.size());
        classes.forEach(ForgeAnchors::verifyClass);
    }
    @Test void changedInstructionsAndMethodDescriptorsFailBeforeDefinition() throws Exception {
        byte[] original = nativeClasses().get("cpw.mods.modlauncher.Launcher");
        for (boolean changeDescriptor : new boolean[]{false, true}) {
            ClassNode node = new ClassNode(); new ClassReader(original).accept(node, 0);
            var method = node.methods.stream().filter(m -> m.name.equals("main")).findFirst().orElseThrow();
            if (changeDescriptor) method.desc = "()V"; else method.instructions.insert(new InsnNode(Opcodes.NOP));
            ClassWriter writer = new ClassWriter(0); node.accept(writer); byte[] changed = writer.toByteArray();
            assertEquals("FORGE_ANCHOR", assertThrows(Failure.class, () -> new ForgeHostHook().transform(new TransformPipeline.Context("cpw.mods.modlauncher.Launcher", ignored -> changed), changed)).code());
        }
    }
    @Test void transformationStagesHaveAnExplicitStableOrder() throws Exception {
        Path root = Path.of(System.getProperty("forge.testDirectory", ""));
        Assumptions.assumeTrue(Files.isRegularFile(root.resolve("forge-runtime.json")));
        var runtime = new ForgeRuntime(root.resolve("forge-runtime.json"), root.resolve("libraries/forge-universal.jar"));
        var pipeline = new TransformPipeline(); runtime.install(pipeline); var audit = new AuditLog(); pipeline.seal(audit);
        assertEquals("[forge-native-host, forge-native-plugins, forge-access-transformers, forge-coremods]", audit.events().getFirst().details().get("ids"));
    }
    @Test void clientLifecycleReadsOriginalForgeBytesBeforeNativePlugins() throws Exception {
        Path root = Path.of(System.getProperty("forge.testDirectory", ""));
        Assumptions.assumeTrue(Files.isRegularFile(root.resolve("client-forge.jar")));
        var game = Archive.readRuntimeGame(root.resolve("client-forge.jar"));
        byte[] main = game.read("net/minecraft/client/main/Main.class"), client = game.read("net/minecraft/client/Minecraft.class");
        var hook = new ClientLifecycleHook(Archive.sha256(client), Archive.sha256(main));
        try (var runtime = new ForgeRuntime(root.resolve("forge-runtime.json"), root.resolve("libraries/forge-universal.jar"))) {
            var pipeline = new TransformPipeline(); pipeline.add(hook); var audit = new AuditLog(); runtime.install(pipeline, true); pipeline.seal(audit);
            String order = audit.events().getFirst().details().get("ids");
            assertTrue(order.indexOf(hook.id()) < order.indexOf("forge-native-plugins"), order);
            // Read and transform only. Neither class is defined and Main is never invoked.
            for (var entry : Map.of(ClientLifecycleHook.MAIN, main, ClientLifecycleHook.CLIENT, client).entrySet()) {
                byte[] transformed = pipeline.apply(entry.getKey(), entry.getValue(), ignored -> null, audit);
                var node = new ClassNode(); new ClassReader(transformed).accept(node, 0);
                assertTrue(node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray())).anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("org/neoforbric/api/ClientHooks")));
            }
            assertEquals("HOOK_INPUT", assertThrows(Failure.class, () -> hook.transform(new TransformPipeline.Context(ClientLifecycleHook.MAIN, ignored -> null), TestJars.type(ClientLifecycleHook.MAIN))).code());
        }
    }
}
