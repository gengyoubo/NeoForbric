package org.neoforbric.minecraft;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.neoforbric.api.ClientHooks;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import static org.objectweb.asm.Opcodes.*;
import static org.junit.jupiter.api.Assertions.*;

class TitleScreenHookTest {
    @Test void injectedThisSurvivesAChoppedReturnFrame() throws Exception {
        String name = TitleScreenHook.TARGET.replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(ALOAD, 0); constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        var init = writer.visitMethod(ACC_PUBLIC, "init", "()V", null, null);
        init.visitCode(); init.visitInsn(ICONST_0); Label end = new Label(); init.visitJumpInsn(IFEQ, end); init.visitInsn(NOP);
        init.visitLabel(end); init.visitFrame(F_CHOP, 1, null, 0, null); init.visitInsn(RETURN); init.visitMaxs(0, 1); init.visitEnd(); writer.visitEnd();
        byte[] original = writer.toByteArray();
        byte[] transformed = new TitleScreenHook(Archive.sha256(original)).transform(new TransformPipeline.Context(TitleScreenHook.TARGET, ignored -> original), original);
        class Definer extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(TitleScreenHook.TARGET, bytes, 0, bytes.length); } }
        AtomicInteger callbacks = new AtomicInteger();
        try (var session = ClientHooks.attach(ignored -> {}, ignored -> {}, 0, ignored -> callbacks.incrementAndGet())) {
            Class<?> type = new Definer().define(transformed); type.getMethod("init").invoke(type.getConstructor().newInstance());
            assertEquals(1, callbacks.get());
        }
    }
}
