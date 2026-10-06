package org.neoforbric.loader;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.api.FabricRuntimeHooks;
import org.neoforbric.fabric.FabricLifecycleCompatibility;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class FabricLifecycleCompatibilityTest {
    @TempDir Path temporary;
    private byte[] client() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "gameDirectory", "Ljava/io/File;", null, null).visitEnd();
        writer.visitField(ACC_PUBLIC, "frozen", "Z", null, null).visitEnd();
        var constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(ALOAD, 0); constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(ALOAD, 0); constructor.visitMethodInsn(INVOKESTATIC, "org/neoforbric/api/FabricRuntimeHooks", "clientInit", "(Ljava/lang/Object;)V", false);
        constructor.visitInsn(RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    @Test void fabricAfterInjectionRunsAfterExactlyOneOwnedInitialization() throws Exception {
        String name = "net.minecraft.client.Minecraft";
        byte[] before = FabricLifecycleCompatibility.beforeMixin(name, client());
        ClassNode node = new ClassNode(); new ClassReader(before).accept(node, 0);
        int anchors = 0;
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/fabricmc/loader/impl/game/minecraft/Hooks") && call.name.equals("startClient")) {
                anchors++;
                assertEquals("(Ljava/io/File;Ljava/lang/Object;)V", call.desc);
                InsnList injected = new InsnList(); injected.add(new VarInsnNode(ALOAD, 0)); injected.add(new InsnNode(ICONST_1));
                injected.add(new FieldInsnNode(PUTFIELD, "net/minecraft/client/Minecraft", "frozen", "Z")); method.instructions.insert(call, injected);
            }
        }
        assertEquals(1, anchors);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        byte[] after = FabricLifecycleCompatibility.afterMixin(name, writer.toByteArray());
        Archive archive = Archive.read(TestJars.jar(temporary.resolve("client.jar"), Map.of("net/minecraft/client/Minecraft.class", after)));
        AuditLog audit = new AuditLog(); TransformPipeline pipeline = new TransformPipeline();
        AtomicInteger initialized = new AtomicInteger();
        try (var callback = FabricRuntimeHooks.attachClient(instance -> {
                 try { assertFalse(instance.getClass().getField("frozen").getBoolean(instance)); }
                 catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                 initialized.incrementAndGet();
             });
             var loader = new GameClassLoader(ClassIndex.prepare(List.of(archive), getClass().getClassLoader(), audit), pipeline, getClass().getClassLoader(), audit)) {
            pipeline.seal(audit); loader.open();
            Object instance = loader.loadClass(name).getConstructor().newInstance();
            assertEquals(1, initialized.get()); assertTrue(instance.getClass().getField("frozen").getBoolean(instance));
        }
        assertEquals("FABRIC_LIFECYCLE_ANCHOR", assertThrows(Failure.class, () -> FabricLifecycleCompatibility.afterMixin(name, client())).code());
    }
}
