package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.*;
import org.neoforbric.forge.*;
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
        var classes = nativeClasses(); assertEquals(29, classes.size());
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
}
