package org.neoforbric.minecraft;

import org.neoforbric.loader.*;
import org.objectweb.asm.*;

/** Keeps normal native cleanup but routes process exit through the kernel's audit boundary. */
public final class ClientLifecycleHook implements TransformPipeline.Transformer {
    public static final String CLIENT = "net.minecraft.client.Minecraft", MAIN = "net.minecraft.client.main.Main";
    private static final String HOOK = "org/neoforbric/api/ClientHooks";
    private final String clientSha256, mainSha256;
    public ClientLifecycleHook(String clientSha256, String mainSha256) { this.clientSha256 = clientSha256; this.mainSha256 = mainSha256; }
    @Override public String id() { return "minecraft-1.21.1-client-lifecycle"; }
    @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        boolean client = context.name().equals(CLIENT), main = context.name().equals(MAIN);
        if (!client && !main) return bytes;
        if (!Archive.sha256(bytes).equals(client ? clientSha256 : mainSha256)) throw new Failure("HOOK_INPUT", "Client lifecycle input differs: " + context.name());
        int[] anchors = new int[3]; ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, output) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean frame = client && name.equals("runTick") && descriptor.equals("(Z)V");
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitInsn(int opcode) {
                        if (frame && opcode == Opcodes.RETURN) {
                            anchors[0]++; super.visitVarInsn(Opcodes.ALOAD, 0); super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "frame", "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (client && opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System") && name.equals("exit") && desc.equals("(I)V")) {
                            anchors[1]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "exit", "(I)V", false);
                        } else if (main && opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Runtime") && name.equals("addShutdownHook") && desc.equals("(Ljava/lang/Thread;)V")) {
                            anchors[2]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "registerShutdownHook", "(Ljava/lang/Runtime;Ljava/lang/Thread;)V", false);
                        } else super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        if (client ? anchors[0] != 1 || anchors[1] != 5 : anchors[2] != 1) throw new Failure("HOOK_ANCHOR", "Unexpected client lifecycle anchors: " + java.util.Arrays.toString(anchors));
        return output.toByteArray();
    }
}
