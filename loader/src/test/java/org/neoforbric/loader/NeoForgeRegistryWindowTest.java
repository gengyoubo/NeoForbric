package org.neoforbric.loader;

import java.util.*;
import java.nio.file.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.neoforbric.api.GameHooks;
import org.neoforbric.neoforge.NeoForgeHostHook;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

public class NeoForgeRegistryWindowTest {
    private static final String TARGET = "net.neoforged.neoforge.internal.CommonModLoader";
    private static final String DATA = "net/neoforged/neoforge/registries/GameData";
    private static final String RUNTIME = "org/neoforbric/neoforge/runtime/NativeNeoForgeRuntime";

    public static final class RegistryState {
        static final List<String> events = new ArrayList<>();
        static boolean constructed, open, registered, configs;
        public static void unfreezeData() { assertTrue(constructed); open = true; events.add("unfreeze"); }
        public static void register() { assertTrue(open, "Fabric registration must run in the writable NeoForge pass"); registered = true; events.add("fabric-main"); }
        public static void postRegisterEvents() { assertTrue(registered); events.add("native-register"); }
        public static void freezeData() { assertTrue(open); open = false; events.add("freeze"); }
        public static void loadServerDefaults() { assertFalse(open); configs = true; events.add("configs"); }
        public static void client() { assertFalse(open); assertTrue(registered); assertTrue(configs); events.add("fabric-client-model-registration"); }
        public static void commonSetup() { assertEquals("fabric-client-model-registration", events.getLast()); events.add("common-setup"); }
    }

    private byte[] commonLoader(List<String> sequence) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, TARGET.replace('.', '/'), null, "java/lang/Object", null);
        var load = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "load", "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)V", null, null);
        load.visitCode(); load.visitMethodInsn(INVOKESTATIC, Type.getInternalName(RegistryState.class), "commonSetup", "()V", false);
        load.visitInsn(RETURN); load.visitMaxs(0, 2); load.visitEnd();
        var registry = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "()V", null, null);
        registry.visitCode();
        for (String method : sequence) registry.visitMethodInsn(INVOKESTATIC, DATA, method, "()V", false);
        registry.visitInsn(RETURN); registry.visitMaxs(0, 0); registry.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] gameData() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, DATA, null, "java/lang/Object", null);
        for (String name : List.of("unfreezeData", "postRegisterEvents", "freezeData")) {
            var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()V", null, null);
            method.visitCode(); method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(RegistryState.class), name, "()V", false);
            method.visitInsn(RETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private byte[] runtime() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, RUNTIME, null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "loadServerDefaults", "()V", null, null);
        method.visitCode(); method.visitMethodInsn(INVOKESTATIC, Type.getInternalName(RegistryState.class), "loadServerDefaults", "()V", false);
        method.visitInsn(RETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    @Test void mixedEntriesRegisterAfterConstructionAndBeforeNativeFreeze() throws Exception {
        byte[] original = commonLoader(List.of("unfreezeData", "postRegisterEvents", "freezeData"));
        byte[] transformed = new NeoForgeHostHook().transform(new TransformPipeline.Context(TARGET, ignored -> original), original);
        class Definer extends ClassLoader {
            Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        }
        RegistryState.events.clear(); RegistryState.constructed = false; RegistryState.open = false; RegistryState.registered = false; RegistryState.configs = false;
        var loader = new Definer(); loader.define(DATA.replace('/', '.'), gameData());
        loader.define(RUNTIME.replace('/', '.'), runtime());
        var common = loader.define(TARGET, transformed);
        try (var hooks = GameHooks.attach(RegistryState::register, RegistryState::client)) {
            // Vanilla's first freeze happens before containers exist and must not dispatch entries.
            assertTrue(RegistryState.events.isEmpty());
            RegistryState.constructed = true; RegistryState.events.add("construct");
            common.getMethod("register").invoke(null);
            assertFalse(RegistryState.configs);
            common.getMethod("load", java.util.concurrent.Executor.class, java.util.concurrent.Executor.class).invoke(null, null, null);
            hooks.verifyComplete();
        }
        assertEquals(List.of("construct", "unfreeze", "fabric-main", "native-register", "freeze", "configs", "fabric-client-model-registration", "common-setup"), RegistryState.events);
    }

    @Test void rejectsMissingReorderedAndDuplicateNativeRegistryWindows() {
        for (var sequence : List.of(List.<String>of(), List.of("postRegisterEvents", "unfreezeData", "freezeData"),
                List.of("unfreezeData", "postRegisterEvents", "freezeData", "unfreezeData"))) {
            byte[] original = commonLoader(sequence);
            assertEquals("NEOFORGE_ANCHOR", assertThrows(Failure.class, () -> new NeoForgeHostHook()
                    .transform(new TransformPipeline.Context(TARGET, ignored -> original), original)).code());
        }
    }

    @Test void canonicalContractDoesNotInjectANativeRegistryWindow() throws Exception {
        byte[] original = commonLoader(List.of("unfreezeData", "postRegisterEvents", "freezeData"));
        assertSame(original, new NeoForgeHostHook(true).transform(new TransformPipeline.Context(TARGET, ignored -> original), original));
    }

    @Test void preparedNeoForgeBinaryKeepsRegistrationBetweenOwnedCallbacks() throws Exception {
        Path universal = Path.of(System.getProperty("neoforge.testUniversal", ""));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(universal), "Pinned NeoForge inputs have not been prepared");
        byte[] original;
        try (var jar = new JarFile(universal.toFile()); var stream = jar.getInputStream(jar.getJarEntry(TARGET.replace('.', '/') + ".class"))) {
            original = stream.readAllBytes();
        }
        byte[] transformed = new NeoForgeHostHook().transform(new TransformPipeline.Context(TARGET, ignored -> original), original);
        ClassNode node = new ClassNode(); new ClassReader(transformed).accept(node, 0);
        var registry = node.methods.stream().filter(method -> Arrays.stream(method.instructions.toArray())
                .anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals(DATA) && call.name.equals("unfreezeData"))).findFirst().orElseThrow();
        var calls = Arrays.stream(registry.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).filter(call -> call.owner.equals(DATA) || call.owner.equals("org/neoforbric/api/GameHooks"))
                .map(call -> call.name).toList();
        assertEquals(List.of("unfreezeData", "beforeRegistryFreeze", "postRegisterEvents", "freezeData"), calls);
        var load = node.methods.stream().filter(method -> method.name.equals("load")).findFirst().orElseThrow();
        var setupCalls = Arrays.stream(load.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).limit(2).map(call -> call.name).toList();
        assertEquals(List.of("loadServerDefaults", "afterRegistryFreeze"), setupCalls);
    }
}
