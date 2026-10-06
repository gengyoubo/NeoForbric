package org.neoforbric.minecraft;

import org.neoforbric.loader.*;
import org.objectweb.asm.*;

/** Fixed vanilla 1.21.1 hook; layout and rendering live in the first-party G-side UI module. */
public final class TitleScreenHook implements TransformPipeline.Transformer {
    public static final String TARGET = "net.minecraft.client.gui.screens.TitleScreen";
    private final String expectedSha256;
    public TitleScreenHook(String expectedSha256) { this.expectedSha256 = expectedSha256; }
    @Override public String id() { return "minecraft-1.21.1-title-screen-ui"; }
    @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        if (!context.name().equals(TARGET)) return bytes;
        if (!Archive.sha256(bytes).equals(expectedSha256)) throw new Failure("HOOK_INPUT", "TitleScreen differs from prepared vanilla input");
        int[] anchors = {0}; ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, output) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("init") || !descriptor.equals("()V")) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitVarInsn(int opcode, int variable) {
                        if (opcode == Opcodes.ASTORE && variable == 0) throw new Failure("HOOK_ANCHOR", "TitleScreen.init overwrites this");
                        super.visitVarInsn(opcode, variable);
                    }
                    @Override public void visitFrame(int type, int localCount, Object[] locals, int stackCount, Object[] stack) {
                        // Vanilla's final RETURN drops all locals. Our ALOAD 0 makes this live again.
                        if (localCount == 0) super.visitFrame(type, 1, new Object[]{TARGET.replace('.', '/')}, stackCount, stack);
                        else super.visitFrame(type, localCount, locals, stackCount, stack);
                    }
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            anchors[0]++; super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/neoforbric/api/ClientHooks", "titleInitialized", "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (anchors[0] != 1) throw new Failure("HOOK_ANCHOR", "Expected one TitleScreen.init return, got " + anchors[0]);
        return output.toByteArray();
    }
}
