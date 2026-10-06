package org.neoforbric.fabric;

import org.neoforbric.loader.Failure;
import org.objectweb.asm.*;

/** Exposes Fabric's standard injection anchor while NeoForbric retains lifecycle dispatch. */
public final class FabricLifecycleCompatibility {
    private static final String CLIENT = "net.minecraft.client.Minecraft";
    private static final String NATIVE = "net/fabricmc/loader/impl/game/minecraft/Hooks";
    private static final String OWNED = "org/neoforbric/api/FabricRuntimeHooks";
    private static final String DESCRIPTOR = "(Ljava/io/File;Ljava/lang/Object;)V";
    private FabricLifecycleCompatibility() {}
    public static byte[] beforeMixin(String name, byte[] bytes) { return rewrite(name, bytes, false); }
    public static byte[] afterMixin(String name, byte[] bytes) { return rewrite(name, bytes, true); }
    private static byte[] rewrite(String className, byte[] bytes, boolean dispatch) {
        if (!CLIENT.equals(className)) return bytes;
        int[] anchors = {0}; ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, output) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (!dispatch && opcode == Opcodes.INVOKESTATIC && owner.equals(OWNED) && name.equals("clientInit") && desc.equals("(Ljava/lang/Object;)V")) {
                            anchors[0]++;
                            super.visitInsn(Opcodes.DUP);
                            super.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "gameDirectory", "Ljava/io/File;");
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(opcode, NATIVE, "startClient", DESCRIPTOR, false);
                        } else if (dispatch && opcode == Opcodes.INVOKESTATIC && owner.equals(NATIVE) && name.equals("startClient") && desc.equals(DESCRIPTOR)) {
                            anchors[0]++;
                            super.visitMethodInsn(opcode, OWNED, "startClient", DESCRIPTOR, false);
                        } else super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        if (anchors[0] != 1) throw new Failure("FABRIC_LIFECYCLE_ANCHOR", "Expected one client lifecycle " + (dispatch ? "dispatch" : "injection") + " anchor, got " + anchors[0]);
        return output.toByteArray();
    }
}
