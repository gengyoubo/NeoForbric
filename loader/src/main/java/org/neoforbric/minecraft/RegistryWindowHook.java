package org.neoforbric.minecraft;

import org.neoforbric.loader.*;
import org.objectweb.asm.*;

/** Fixed 1.21.1 hook: after vanilla contents exist, before the original freeze; never unfreezes. */
public final class RegistryWindowHook implements TransformPipeline.Transformer {
    public static final String TARGET = "net.minecraft.core.registries.BuiltInRegistries";
    private static final String OWNER = TARGET.replace('.', '/');
    private final String expectedSha256;
    private final boolean deferred;
    public RegistryWindowHook(String expectedSha256) { this(expectedSha256, false); }
    public RegistryWindowHook(String expectedSha256, boolean deferred) { this.expectedSha256 = expectedSha256; this.deferred = deferred; }
    @Override public String id() { return "minecraft-1.21.1-registry-window"; }
    @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        if (!context.name().equals(TARGET)) return bytes;
        if (!Archive.sha256(bytes).equals(expectedSha256)) throw new Failure("HOOK_INPUT", "BuiltInRegistries differs from the prepared vanilla input");
        int[] anchors = {0}, contents = {0};
        ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, output) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (deferred && name.equals("freeze") && descriptor.equals("()V") && (access & Opcodes.ACC_STATIC) != 0) {
                    anchors[0]++;
                    return new MethodVisitor(Opcodes.ASM9, method) {
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/neoforbric/api/GameHooks", "afterRegistryFreeze", "()V", false);
                            super.visitInsn(opcode);
                        }
                    };
                }
                if (deferred) return method;
                if (!name.equals("bootStrap") || !descriptor.equals("()V") || (access & Opcodes.ACC_STATIC) == 0) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        boolean freeze = opcode == Opcodes.INVOKESTATIC && owner.equals(OWNER) && name.equals("freeze") && desc.equals("()V");
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals(OWNER) && name.equals("createContents") && desc.equals("()V")) contents[0]++;
                        if (freeze) {
                            if (contents[0] != 1) throw new Failure("HOOK_ANCHOR", "Freeze is not after exactly one createContents call");
                            anchors[0]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/neoforbric/api/GameHooks", "beforeRegistryFreeze", "()V", false);
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                        if (freeze) super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/neoforbric/api/GameHooks", "afterRegistryFreeze", "()V", false);
                    }
                };
            }
        }, 0);
        if (anchors[0] != 1 || (!deferred && contents[0] != 1)) throw new Failure("HOOK_ANCHOR", "Expected one registry freeze anchor, got " + contents[0] + " / " + anchors[0]);
        return output.toByteArray();
    }
}
