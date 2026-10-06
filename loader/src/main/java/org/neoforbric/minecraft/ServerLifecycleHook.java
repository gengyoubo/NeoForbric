package org.neoforbric.minecraft;

import org.neoforbric.loader.*;
import org.objectweb.asm.*;

/** Fixed 1.21.1 anchors; original init, tick, crash handling and saves still execute. */
public final class ServerLifecycleHook implements TransformPipeline.Transformer {
    public static final String TARGET = "net.minecraft.server.MinecraftServer";
    public static final String MAIN = "net.minecraft.server.Main";
    private static final String OWNER = TARGET.replace('.', '/'), HOOK = "org/neoforbric/api/ServerHooks";
    private final String expectedSha256;
    private final String mainSha256;
    public ServerLifecycleHook(String expectedSha256, String mainSha256) { this.expectedSha256 = expectedSha256; this.mainSha256 = mainSha256; }
    @Override public String id() { return "minecraft-1.21.1-server-lifecycle"; }
    @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        if (context.name().equals(MAIN)) return transformMain(bytes);
        if (!context.name().equals(TARGET)) return bytes;
        if (!Archive.sha256(bytes).equals(expectedSha256)) throw new Failure("HOOK_INPUT", "MinecraftServer differs from prepared vanilla input");
        int[] anchors = new int[5];
        ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, output) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("spin") && descriptor.equals("(Ljava/util/function/Function;)Lnet/minecraft/server/MinecraftServer;")) {
                    return new MethodVisitor(Opcodes.ASM9, method) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/util/concurrent/atomic/AtomicReference") && name.equals("set") && desc.equals("(Ljava/lang/Object;)V")) {
                                anchors[0]++; super.visitInsn(Opcodes.DUP); super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "bind", "(Ljava/lang/Object;)V", false);
                            }
                            super.visitMethodInsn(opcode, owner, name, desc, itf);
                        }
                    };
                }
                if (!name.equals("runServer") || !descriptor.equals("()V")) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals(OWNER) && name.equals("constructOrExtractCrashReport") && desc.equals("(Ljava/lang/Throwable;)Lnet/minecraft/CrashReport;")) {
                            anchors[3]++; super.visitInsn(Opcodes.DUP); super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "failed", "(Ljava/lang/Throwable;)V", false);
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                        if (opcode != Opcodes.INVOKEVIRTUAL || !owner.equals(OWNER)) return;
                        if (name.equals("initServer") && desc.equals("()Z")) {
                            anchors[1]++; super.visitInsn(Opcodes.DUP); super.visitVarInsn(Opcodes.ALOAD, 0); super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "initialized", "(Ljava/lang/Object;Z)V", false);
                        } else if (name.equals("tickServer") && desc.equals("(Ljava/util/function/BooleanSupplier;)V")) {
                            anchors[2]++; super.visitVarInsn(Opcodes.ALOAD, 0); super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "tick", "(Ljava/lang/Object;)V", false);
                        } else if (name.equals("stopServer") && desc.equals("()V")) {
                            anchors[4]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "stopped", "()V", false);
                        }
                    }
                };
            }
        }, 0);
        if (anchors[0] != 1 || anchors[1] != 1 || anchors[2] != 1 || anchors[3] != 1 || anchors[4] != 3)
            throw new Failure("HOOK_ANCHOR", "Unexpected MinecraftServer lifecycle anchors: " + java.util.Arrays.toString(anchors));
        return output.toByteArray();
    }
    private byte[] transformMain(byte[] bytes) {
        if (!Archive.sha256(bytes).equals(mainSha256)) throw new Failure("HOOK_INPUT", "Server Main differs from prepared vanilla input");
        int[] anchors = {0}; ClassWriter output = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, output) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("main") || !descriptor.equals("([Ljava/lang/String;)V")) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/Runtime") && name.equals("addShutdownHook") && desc.equals("(Ljava/lang/Thread;)V")) {
                            anchors[0]++; super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "registerShutdownHook", "(Ljava/lang/Runtime;Ljava/lang/Thread;)V", false);
                        } else super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        if (anchors[0] != 1) throw new Failure("HOOK_ANCHOR", "Expected one vanilla shutdown hook registration");
        return output.toByteArray();
    }
}
