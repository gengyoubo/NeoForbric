package org.neoforbric.forge;

import java.util.*;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/** Pinned passive services and successful native lifecycle observations; no state actions are replaced. */
public final class ForgeLifecycleHook implements TransformPipeline.Transformer {
    private static final String RUNTIME = "org/neoforbric/forge/runtime/NativeForgeRuntime";
    public String id() { return "forge-lifecycle"; }
    public Set<String> after() { return Set.of("forge-native-host"); }
    public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        if (!Set.of("cpw.mods.jarhandling.impl.Jar", "net.minecraftforge.fml.loading.LanguageLoadingProvider", "net.minecraftforge.fml.ModStateManager", "net.minecraftforge.fml.ModLoader", "net.minecraftforge.registries.GameData", "net.minecraftforge.client.loading.ClientModLoader", "net.minecraftforge.server.loading.ServerModLoader").contains(context.name())) return bytes;
        ForgeAnchors.verifyClass(context.name(), bytes); ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        if (context.name().equals("cpw.mods.jarhandling.impl.Jar")) {
            int anchors = 0;
            for (var method : node.methods) for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals("java/nio/file/spi/FileSystemProvider") && call.name.equals("installedProviders")) { call.owner = RUNTIME; call.name = "fileSystemProviders"; anchors++; }
            if (anchors != 1) throw new Failure("FORGE_ANCHOR", "Expected one Forge secure JAR filesystem boundary");
        }
        else if (context.name().endsWith("LanguageLoadingProvider")) for (var method : node.methods) {
            if (method.name.equals("<init>")) {
                clear(method); method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "languageProviders", "()Ljava/util/List;", false));
                method.instructions.add(new FieldInsnNode(PUTFIELD, node.name, "providers", "Ljava/util/List;"));
                method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true));
                method.instructions.add(new FieldInsnNode(PUTFIELD, node.name, "providersByName", "Ljava/util/Map;")); method.instructions.add(new InsnNode(RETURN));
            } else if (method.name.equals("findLanguage")) {
                clear(method); for (int i = 1; i <= 3; i++) method.instructions.add(new VarInsnNode(ALOAD, i));
                method.instructions.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "findLanguage", method.desc, false)); method.instructions.add(new InsnNode(ARETURN));
            } else if (method.name.equals("loadLanguageProviders")) { clear(method); method.instructions.add(new InsnNode(RETURN)); }
        }
        else if (context.name().endsWith("ModStateManager")) {
            int anchors = 0;
            for (var method : node.methods) for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals("cpw/mods/modlauncher/util/ServiceLoaderUtils") && call.name.equals("streamWithErrorHandling")) {
                call.owner = RUNTIME; call.name = "stateProviders"; anchors++;
            }
            if (anchors != 1) throw new Failure("FORGE_ANCHOR", "Expected one native state provider discovery boundary");
        }
        else if (context.name().equals("net.minecraftforge.fml.ModLoader")) {
            var method = node.methods.stream().filter(m -> m.name.equals("dispatchAndHandleError")).findFirst().orElseThrow();
            for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == RETURN) {
                InsnList callback = new InsnList(); callback.add(new VarInsnNode(ALOAD, 1)); callback.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, "observeState", "(Lnet/minecraftforge/fml/IModLoadingState;)V", false)); method.instructions.insertBefore(instruction, callback);
            }
        }
        else if (context.name().equals("net.minecraftforge.registries.GameData")) {
            for (var method : node.methods) if (method.desc.equals("()V") && Set.of("unfreezeData", "freezeData").contains(method.name)) {
                for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == RETURN) method.instructions.insertBefore(instruction, callback(method.name.equals("unfreezeData") ? "registryOpened" : "registryFrozen"));
            }
        }
        else for (var method : node.methods) if (context.name().contains("ClientModLoader") ? method.name.equals("begin") : method.name.equals("load")) method.instructions.insert(callback("captureRegistrySession"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static InsnList callback(String name) { InsnList list = new InsnList(); list.add(new MethodInsnNode(INVOKESTATIC, RUNTIME, name, "()V", false)); return list; }
    private static void clear(MethodNode method) { method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null; }
}
