package org.neoforbric.neoforge;

import org.neoforbric.loader.*;
import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Replace only FML's native discovery/launch handoff; keep real containers and events. */
public final class NeoForgeHostHook implements TransformPipeline.Transformer {
    private final boolean registryContract;
    public NeoForgeHostHook() { this(false); }
    public NeoForgeHostHook(boolean registryContract) { this.registryContract = registryContract; }
    @Override public String id() { return "neoforge-passive-host"; }
    @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        if (context.name().equals("cpw.mods.modlauncher.Launcher")) return passiveLauncher(bytes);
        if (context.name().equals("net.neoforged.fml.loading.JarVersionLookupHandler")) {
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            var method = node.methods.stream().filter(candidate -> candidate.name.equals("getVersion") && candidate.desc.equals("(Ljava/lang/Class;)Ljava/util/Optional;")).findFirst()
                    .orElseThrow(() -> new Failure("NEOFORGE_ANCHOR", "Missing passive jar version lookup"));
            method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/neoforbric/neoforge/runtime/NeoForgeBridge", "jarVersion", method.desc, false));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        }
        if (registryContract && !context.name().equals("cpw.mods.jarhandling.impl.JarContentsImpl")) {
            if (context.name().equals("net.neoforged.neoforge.registries.DeferredHolder")) {
                ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); int anchors = 0;
                for (var method : node.methods) for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/core/Holder") && call.name.equals("getDelegate")) {
                        call.setOpcode(Opcodes.INVOKESTATIC); call.owner = "org/neoforbric/neoforge/runtime/NativeNeoForgeRuntime";
                        call.name = "vanillaDelegate"; call.desc = "(Lnet/minecraft/core/Holder;)Lnet/minecraft/core/Holder;"; call.itf = false; anchors++;
                    }
                if (anchors != 1) throw new Failure("NEOFORGE_ANCHOR", "Expected one DeferredHolder delegate ABI anchor");
                int lookup = 0;
                for (var method : node.methods) if (method.name.equals("getRegistry")) for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/core/Registry") && call.name.equals("get")
                            && call.desc.equals("(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;")) {
                        call.setOpcode(Opcodes.INVOKESTATIC); call.owner = "org/neoforbric/neoforge/runtime/NativeNeoForgeRuntime"; call.name = "lookupRegistry";
                        call.desc = "(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/Object;"; call.itf = false; lookup++;
                    }
                if (lookup != 1) throw new Failure("NEOFORGE_ANCHOR", "Expected one DeferredHolder registry lookup anchor");
                ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
            }
            if (context.name().equals("net.minecraft.world.item.CreativeModeTab")) {
                ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); int anchors = 0;
                for (var method : node.methods) if (method.name.equals("buildContents") && method.desc.equals("(Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V"))
                    for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                        InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0)); hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/neoforbric/neoforge/runtime/NativeNeoForgeRuntime", "creativeTab",
                                "(Lnet/minecraft/world/item/CreativeModeTab;Lnet/minecraft/world/item/CreativeModeTab$ItemDisplayParameters;)V", false));
                        method.instructions.insertBefore(instruction, hook); anchors++;
                    }
                if (anchors != 1) throw new Failure("NEOFORGE_ANCHOR", "Expected one vanilla creative tab content anchor");
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
            }
            if (!context.name().equals("net.neoforged.neoforge.registries.DeferredRegister")) return bytes;
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); int anchors = 0;
            for (var method : node.methods) if (method.name.equals("addEntries")) for (var instruction : method.instructions)
                if (instruction instanceof InvokeDynamicInsnNode dynamic) for (int i = 0; i < dynamic.bsmArgs.length; i++)
                    if (dynamic.bsmArgs[i] instanceof Handle handle && handle.getOwner().equals("net/neoforged/neoforge/registries/IRegistryExtension") && handle.getName().equals("addAlias")) {
                        dynamic.bsmArgs[i] = new Handle(Opcodes.H_INVOKESTATIC, "org/neoforbric/neoforge/runtime/NativeNeoForgeRuntime", "unsupportedAlias",
                                "(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/resources/ResourceLocation;)V", false); anchors++;
                    }
            if (anchors != 1) throw new Failure("NEOFORGE_ANCHOR", "Expected one DeferredRegister alias ABI anchor");
            ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
        }
        if (context.name().equals("net.minecraft.client.gui.screens.TitleScreen")) return titleScreen(bytes);
        if (context.name().equals("net.neoforged.neoforge.server.ServerLifecycleHooks")) {
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            var methods = node.methods.stream().filter(m -> m.name.equals("handleExit") && m.desc.equals("(I)V")).toList();
            if (methods.size() != 1) throw new Failure("NEOFORGE_ANCHOR", "Missing NeoForge process exit anchor");
            MethodNode method = methods.getFirst(); method.instructions.clear(); method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/neoforbric/api/ClientHooks", "exit", "(I)V", false));
            method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 1; method.maxLocals = 1;
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        }
        if (context.name().equals("net.neoforged.fml.loading.BackgroundWaiter")) {
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            var methods = node.methods.stream().filter(m -> m.name.equals("runAndTick") && m.desc.equals("(Ljava/lang/Runnable;Ljava/lang/Runnable;)V")).toList();
            if (methods.size() != 1) throw new Failure("NEOFORGE_ANCHOR", "Missing FML background bootstrap anchor");
            MethodNode method = methods.getFirst(); method.instructions.clear(); method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
            method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 1; method.maxLocals = 2;
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        }
        if (context.name().equals("cpw.mods.jarhandling.impl.JarContentsImpl")) {
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); int replaced = 0;
            for (var method : node.methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("java/nio/file/spi/FileSystemProvider") && call.name.equals("installedProviders")) {
                    call.owner = "org/neoforbric/neoforge/runtime/NeoForgeBridge"; call.name = "fileSystemProviders"; replaced++;
                }
            }
            if (replaced != 1) throw new Failure("NEOFORGE_ANCHOR", "Expected one secure JAR filesystem provider anchor");
            ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
        }
        if (context.name().equals("net.minecraft.core.MappedRegistry"))
            return accessor(bytes, "registrationInfos", "Ljava/util/Map;", "neoforge$getRegistrationInfos", "net/neoforged/neoforge/mixins/MappedRegistryAccessor", false);
        if (context.name().equals("net.minecraft.world.level.block.entity.BlockEntityType"))
            return accessor(bytes, "validBlocks", "Ljava/util/Set;", "neoforge$setValidBlocks", "net/neoforged/neoforge/mixins/BlockEntityTypeAccessor", true);
        if (!context.name().equals("net.neoforged.fml.ModLoader")) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        var methods = node.methods.stream().filter(m -> m.name.equals("gatherAndInitializeMods")
                && m.desc.equals("(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V")).toList();
        if (methods.size() != 1) throw new Failure("NEOFORGE_ANCHOR", "Expected one pinned FML construction anchor");
        MethodNode method = methods.getFirst(); method.instructions.clear(); method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/neoforbric/neoforge/runtime/NeoForgeBridge", "construct", method.desc, false));
        method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 3; method.maxLocals = 3;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static byte[] passiveLauncher(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); int anchors = 0;
        var signatures = java.util.Map.of("<init>", "()V", "main", "([Ljava/lang/String;)V", "run", "([Ljava/lang/String;)V",
                "findLaunchPlugin", "(Ljava/lang/String;)Ljava/util/Optional;", "findLaunchHandler", "(Ljava/lang/String;)Ljava/util/Optional;", "findLayerManager", "()Ljava/util/Optional;");
        for (var method : node.methods) {
            String name = method.name;
            if (!signatures.containsKey(name)) continue;
            if (!signatures.get(name).equals(method.desc)) throw new Failure("NEOFORGE_ANCHOR", "Unexpected Launcher method " + name + method.desc);
            method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
            if (name.equals("<init>")) {
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            } else if (name.equals("main") || name.equals("run")) {
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/neoforbric/neoforge/runtime/NeoForgeBridge", "forbiddenNativeLaunch", "()V", false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            } else {
                if (name.equals("findLayerManager")) method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/neoforbric/neoforge/runtime/NeoForgeBridge", "layerManager", "()Ljava/util/Optional;", false));
                else method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
            anchors++;
        }
        if (anchors != 6) throw new Failure("NEOFORGE_ANCHOR", "Expected six passive Launcher anchors, found " + anchors);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    /** Remove the native button and its extra layout row; the kernel UI supplies Mods. */
    private static byte[] titleScreen(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        var methods = node.methods.stream().filter(m -> m.name.equals("init") && m.desc.equals("()V")).toList();
        if (methods.size() != 1) throw new Failure("NEOFORGE_ANCHOR", "Missing title screen initialization anchor");
        MethodNode method = methods.getFirst();
        AbstractInsnNode start = null, end = null;
        int buttons = 0;
        for (var instruction : method.instructions) if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                && type.desc.equals("net/neoforged/neoforge/client/gui/widget/ModsButton")) {
            buttons++; start = instruction.getPrevious();
            if (!(start instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0)
                throw new Failure("NEOFORGE_ANCHOR", "Unexpected native Mods button receiver");
            for (var cursor = instruction.getNext(); cursor != null; cursor = cursor.getNext()) {
                if (cursor instanceof MethodInsnNode call && call.name.equals("addRenderableWidget")
                        && call.desc.equals("(Lnet/minecraft/client/gui/components/events/GuiEventListener;)Lnet/minecraft/client/gui/components/events/GuiEventListener;")) {
                    var pop = nextInstruction(cursor); var increment = pop == null ? null : nextInstruction(pop);
                    if (pop == null || pop.getOpcode() != Opcodes.POP || !(increment instanceof IincInsnNode offset) || offset.incr != 22)
                        throw new Failure("NEOFORGE_ANCHOR", "Unexpected native Mods button layout offset");
                    end = increment; break;
                }
            }
        }
        if (buttons != 1 || end == null) throw new Failure("NEOFORGE_ANCHOR", "Expected one native title Mods button");
        var after = end.getNext();
        for (var cursor = start; cursor != after;) {
            var next = cursor.getNext(); method.instructions.remove(cursor); cursor = next;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static AbstractInsnNode nextInstruction(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
    private static byte[] accessor(byte[] bytes, String fieldName, String descriptor, String methodName, String api, boolean setter) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        var fields = node.fields.stream().filter(f -> f.name.equals(fieldName) && f.desc.equals(descriptor)).toList();
        if (fields.size() != 1) throw new Failure("NEOFORGE_ANCHOR", "Missing accessor field " + fieldName);
        if (node.interfaces.contains(api)) throw new Failure("NEOFORGE_ANCHOR", "Accessor already applied: " + api);
        node.interfaces.add(api);
        if (setter) fields.getFirst().access &= ~Opcodes.ACC_FINAL;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, methodName, setter ? "(" + descriptor + ")V" : "()" + descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (setter) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(setter ? Opcodes.PUTFIELD : Opcodes.GETFIELD, node.name, fieldName, descriptor));
        method.instructions.add(new InsnNode(setter ? Opcodes.RETURN : Opcodes.ARETURN)); node.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
}
