package org.neoforbric.forge;

import java.util.*;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/** A query facade only: native launcher entrypoints and classloader takeover remain closed. */
public final class ForgeHostHook implements TransformPipeline.Transformer {
    private static final String BRIDGE = "org/neoforbric/forge/runtime/ForgeBridge";
    public String id() { return "forge-native-host"; }
    public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        if (!Set.of("cpw.mods.modlauncher.Launcher", "cpw.mods.modlauncher.LaunchPluginHandler", "cpw.mods.modlauncher.TransformingClassLoader", "net.minecraftforge.fml.loading.FMLLoader", "net.minecraftforge.fml.Bindings").contains(context.name())) return bytes;
        ForgeAnchors.verifyClass(context.name(), bytes);
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        for (var method : node.methods) {
            String signature = method.name + method.desc;
            if (context.name().equals("cpw.mods.modlauncher.Launcher")) {
                if (signature.equals("<init>()V")) {
                    clear(method); method.instructions.add(new VarInsnNode(ALOAD, 0));
                    method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                    initializeField(method, node.name, "blackboard", "cpw/mods/modlauncher/api/TypesafeMap", false);
                    initializeField(method, node.name, "environment", "cpw/mods/modlauncher/Environment", true);
                    method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new TypeInsnNode(NEW, "cpw/mods/modlauncher/LaunchPluginHandler")); method.instructions.add(new InsnNode(DUP)); method.instructions.add(new InsnNode(ACONST_NULL));
                    method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "cpw/mods/modlauncher/LaunchPluginHandler", "<init>", "(Lcpw/mods/modlauncher/ModuleLayerHandler;)V", false));
                    method.instructions.add(new FieldInsnNode(PUTFIELD, node.name, "launchPlugins", "Lcpw/mods/modlauncher/LaunchPluginHandler;"));
                    method.instructions.add(new InsnNode(RETURN));
                } else if (Set.of("main([Ljava/lang/String;)V", "run([Ljava/lang/String;)V").contains(signature)) refuse(method);
                else if (Set.of("findLaunchPlugin", "findLaunchHandler", "findNameMapping", "findLayerManager").contains(method.name)) {
                    clear(method);
                    if (!method.name.equals("findLayerManager")) method.instructions.add(new VarInsnNode(ALOAD, 1));
                    method.instructions.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, method.name, method.desc, false));
                    method.instructions.add(new InsnNode(ARETURN));
                }
            } else if (context.name().equals("cpw.mods.modlauncher.LaunchPluginHandler")) {
                if (signature.equals("<init>(Lcpw/mods/modlauncher/ModuleLayerHandler;)V")) {
                    clear(method); method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                    method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, "launchPluginsView", "()Ljava/util/Map;", false));
                    method.instructions.add(new FieldInsnNode(PUTFIELD, node.name, "plugins", "Ljava/util/Map;")); method.instructions.add(new InsnNode(RETURN));
                } else if (Set.of("computeLaunchPluginTransformerSet", "offerScanResultsToPlugins", "offerClassNodeToPlugins", "announceLaunch").contains(method.name)) refuse(method);
            } else if (context.name().equals("cpw.mods.modlauncher.TransformingClassLoader")) {
                if (method.name.equals("<init>")) refuse(method);
            } else if (context.name().equals("net.minecraftforge.fml.Bindings")) {
                if (signature.equals("<clinit>()V")) {
                    clear(method); method.instructions.add(new TypeInsnNode(NEW, "net/minecraftforge/internal/ForgeBindings")); method.instructions.add(new InsnNode(DUP));
                    method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "net/minecraftforge/internal/ForgeBindings", "<init>", "()V", false));
                    method.instructions.add(new FieldInsnNode(PUTSTATIC, node.name, "PROVIDER", "Lnet/minecraftforge/fml/IBindingsProvider;")); method.instructions.add(new InsnNode(RETURN));
                }
            } else {
                String target = switch (signature) {
                    case "getDist()Lnet/minecraftforge/api/distmarker/Dist;" -> "dist";
                    case "getNaming()Ljava/lang/String;" -> "naming";
                    case "isProduction()Z" -> "production";
                    case "isSecureJarEnabled()Z" -> "secureJarsEnabled";
                    case "getGamePath()Ljava/nio/file/Path;" -> "gamePath";
                    case "getNameFunction(Ljava/lang/String;)Ljava/util/Optional;" -> "findNameMapping";
                    default -> null;
                };
                if (target != null) {
                    clear(method);
                    if (method.desc.startsWith("(Ljava/lang/String;")) method.instructions.add(new VarInsnNode(ALOAD, 0));
                    method.instructions.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, target, method.desc, false));
                    method.instructions.add(new InsnNode(method.desc.endsWith("Z") ? IRETURN : ARETURN));
                } else if (Set.of("onInitialLoad", "setupLaunchHandler", "beginModScan", "completeScan", "beforeStart").contains(method.name)) refuse(method);
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static void clear(MethodNode method) { method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null; }
    private static void refuse(MethodNode method) {
        clear(method); method.instructions.add(new TypeInsnNode(NEW, "java/lang/UnsupportedOperationException")); method.instructions.add(new InsnNode(DUP));
        method.instructions.add(new LdcInsnNode("FORGE_LAUNCH_OWNERSHIP: NeoForbric owns main, G and the transform pipeline"));
        method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false)); method.instructions.add(new InsnNode(ATHROW));
    }
    private static void initializeField(MethodNode method, String owner, String field, String type, boolean launcher) {
        method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new TypeInsnNode(NEW, type)); method.instructions.add(new InsnNode(DUP));
        if (launcher) method.instructions.add(new VarInsnNode(ALOAD, 0)); else method.instructions.add(new LdcInsnNode(Type.getObjectType(owner)));
        method.instructions.add(new MethodInsnNode(INVOKESPECIAL, type, "<init>", launcher ? "(L" + owner + ";)V" : "(Ljava/lang/Class;)V", false));
        method.instructions.add(new FieldInsnNode(PUTFIELD, owner, field, "L" + type + ";"));
    }
}
