package org.neoforbric.neoforge;

import java.util.*;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Adapt specific injection contracts to the verified NeoForge 21.1.248 patches.
 * Required injections remain required; unsupported differences still fail the launch. */
public final class NeoForgeFabricCompatibility implements TransformPipeline.Transformer {
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String POSITION = "net/minecraft/core/BlockPos";
    private final AuditLog audit;
    public NeoForgeFabricCompatibility(AuditLog audit) { this.audit = audit; }
    public String id() { return "neoforge-fabric-mixin-compatibility"; }
    public Set<String> after() { return Set.of("fabric-runtime-access-and-environment"); }
    public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        if (node.name.equals("de/florianmichael/viafabricplus/save/SaveManager")) return viaSaving(node);
        if (node.name.equals("de/florianmichael/viafabricplus/settings/impl/VisualSettings$1")) return viaFontCache(node);
        if (node.name.equals("net/fabricmc/fabric/mixin/tag/TagFileMixin")) return nativeTagFile(node);
        if (node.name.equals("net/fabricmc/fabric/mixin/tag/TagGroupLoaderMixin")) {
            // The pinned native TagLoader already decodes remove entries, includes
            // their dependency edges and removes values while building each tag.
            // Keep this deprecated API's classes, and use that implementation once.
            node.fields.clear(); node.methods.removeIf(method -> !method.name.equals("<init>"));
            audit.record("PREPARE", "fabric-native-tag-removal", node.name, Map.of("implementation", "NeoForge TagLoader", "duplicateRemovalPass", "replaced"));
            ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
        }
        if (node.name.equals("de/florianmichael/viafabricplus/injection/mixin/base/MixinMain")) return viaBootstrap(node);
        if (node.name.equals("carpet/mixins/CustomPacketPayload_networkStuffMixin")) return carpetPayload(node);
        if (node.name.equals("carpet/mixins/ServerPlayerGameMode_scarpetEventsMixin")) return carpetBlockBreak(node);
        if (node.name.equals("carpet/mixins/PersistentEntitySectionManager_scarpetMixin")) {
            for (MethodNode method : node.methods) for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) if (annotation.desc.endsWith("/Inject;"))
                for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals("method")) annotation.values.set(i + 1, List.of("addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z"));
            audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "entity tracking in addEntity", "to", "addEntityWithoutEvent after native join event"));
            ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
        }
        if (node.name.equals("de/florianmichael/viafabricplus/injection/mixin/fixes/minecraft/network/MixinClientPlayNetworkHandler")) return viaRespawn(node);
        if (node.name.equals("me/pepperbell/continuity/client/mixin/FallingBlockEntityRendererMixin")) return fallingBlock(node);
        if (node.name.equals("de/florianmichael/viafabricplus/injection/mixin/fixes/minecraft/network/MixinClientPlayerInteractionManager")) return viaBlockInteraction(node);
        if (targets(node, "net/minecraft/world/item/ShovelItem")) return shovelPath(node, bytes);
        if (targets(node, "net/minecraft/world/level/Level")) return levelUpdates(node, bytes);
        if (!targets(node, PLAYER)) return bytes;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            boolean moved = false;
            for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (annotation.values == null) continue;
                for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals("method")) {
                    Object value = annotation.values.get(i + 1);
                    if (value instanceof List<?> selectors) {
                        List<Object> updated = new ArrayList<>();
                        for (Object selector : selectors) {
                            String name = selector.toString().split("\\(", 2)[0];
                            if (Set.of("getBlockBreakingSpeed", "getDestroySpeed", "method_7351").contains(name)) {
                                updated.add("getDigSpeed(" + STATE + "L" + POSITION + ";)F"); moved = true;
                            } else updated.add(selector);
                        }
                        annotation.values.set(i + 1, updated);
                    }
                }
            }
            if (!moved) continue;
            // Inject callbacks capturing BlockState also capture the added BlockPos argument.
            // MixinExtras expression handlers without target arguments keep their existing shape.
            if (method.desc.startsWith("(" + STATE + "Lorg/spongepowered/asm/mixin/injection/callback/")) addPositionArgument(method);
            changed = true;
            audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("handler", method.name, "from", "Player.getDestroySpeed", "to", "Player.getDigSpeed", "nativeBreakSpeedEvent", "preserved"));
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private byte[] viaBootstrap(ClassNode node) {
        for (AnnotationNode annotation : annotations(node.visibleAnnotations, node.invisibleAnnotations)) if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
            annotation.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType("net/neoforged/neoforge/internal/CommonModLoader")), "remap", false));
        }
        for (MethodNode method : node.methods) for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) if (annotation.desc.endsWith("/Inject;")) {
            AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
            at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Lnet/neoforged/neoforge/registries/GameData;postRegisterEvents()V", "remap", false));
            annotation.values = new ArrayList<>(List.of("method", List.of("lambda$begin$0()V"), "at", List.of(at), "remap", false));
        }
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "Main.startTimerHack", "to", "NeoForge before postRegisterEvents", "registryWindow", "native"));
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    private byte[] viaSaving(ClassNode node) {
        int hooks = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals("java/lang/Runtime") && call.name.equals("addShutdownHook") && call.desc.equals("(Ljava/lang/Thread;)V")) {
            call.setOpcode(Opcodes.INVOKESTATIC); call.owner = "org/neoforbric/api/FabricRuntimeHooks"; call.desc = "(Ljava/lang/Runtime;Ljava/lang/Thread;)V"; hooks++;
        }
        if (hooks != 1) throw new Failure("FABRIC_NATIVE_MIXIN", "Expected one ViaFabricPlus configuration save hook, found " + hooks);
        audit.record("PREPARE", "fabric-native-save-hook", node.name, Map.of("phase", "before game classloader close"));
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    private byte[] viaFontCache(ClassNode node) {
        for (MethodNode method : node.methods) if (method.name.equals("onValueChanged")) for (AbstractInsnNode instruction : method.instructions.toArray())
            if (instruction instanceof FieldInsnNode field && field.owner.equals("net/minecraft/client/Minecraft") && field.name.equals("fontManager")) {
                // Native registry loading runs inside Minecraft's constructor. Settings
                // still load, but there are no glyph caches to invalidate until fonts exist.
                LabelNode ready = new LabelNode(); InsnList guard = new InsnList(); guard.add(new InsnNode(Opcodes.DUP));
                guard.add(new JumpInsnNode(Opcodes.IFNONNULL, ready)); guard.add(new InsnNode(Opcodes.POP)); guard.add(new InsnNode(Opcodes.RETURN)); guard.add(ready);
                guard.add(new FrameNode(Opcodes.F_NEW, 2, new Object[]{node.name, "net/minecraft/client/Minecraft"}, 1, new Object[]{"net/minecraft/client/gui/font/FontManager"}));
                method.instructions.insert(field, guard);
            }
        audit.record("PREPARE", "fabric-native-font-cache", node.name, Map.of("settings", "loaded", "absentFontCache", "nothing to invalidate"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private byte[] nativeTagFile(ClassNode node) {
        node.access |= Opcodes.ACC_ABSTRACT;
        for (FieldNode field : node.fields) if (field.name.equals("remove")) {
            field.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;"), new AnnotationNode("Lorg/spongepowered/asm/mixin/Final;"), new AnnotationNode("Lorg/spongepowered/asm/mixin/Mutable;")));
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals("remove")) {
                method.access |= Opcodes.ACC_ABSTRACT; method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
            } else if (method.name.equals("modifyCodec")) {
                // Native CODEC already contains the remove field; a second codec
                // would deserialize it twice into independently owned lists.
                method.instructions.clear(); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); method.instructions.add(new InsnNode(Opcodes.ARETURN));
                method.tryCatchBlocks.clear(); method.localVariables = null;
            } else if (method.name.equals("<init>")) {
                method.instructions.clear(); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)); method.instructions.add(new InsnNode(Opcodes.RETURN));
                method.tryCatchBlocks.clear(); method.localVariables = null;
            }
        }
        audit.record("PREPARE", "fabric-native-tag-removal", node.name, Map.of("implementation", "NeoForge TagFile.CODEC and remove", "fabricInterfaces", "bridged"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private byte[] carpetPayload(ClassNode node) {
        String owner = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
        String oldArguments = "(L" + owner + "$FallbackProvider;Ljava/util/List;";
        String added = "Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketFlow;";
        String codec = oldArguments + added + ")Lnet/minecraft/network/codec/StreamCodec;";
        for (MethodNode method : node.methods) if (method.name.equals("onCodec") && method.desc.startsWith(oldArguments)) {
            method.desc = oldArguments + added + method.desc.substring(oldArguments.length());
            // This synthetic injection handler has no callers using generic reflection.
            method.signature = null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof VarInsnNode variable && variable.var >= 2) variable.var += 2;
                if (instruction instanceof IincInsnNode increment && increment.var >= 2) increment.var += 2;
                if (instruction instanceof FrameNode frame && frame.local != null) { frame.local.add(2, "net/minecraft/network/ConnectionProtocol"); frame.local.add(3, "net/minecraft/network/protocol/PacketFlow"); }
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals("codec")) {
                    InsnList arguments = new InsnList(); arguments.add(new VarInsnNode(Opcodes.ALOAD, 2)); arguments.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    method.instructions.insertBefore(call, arguments); call.desc = codec;
                }
            }
            if (method.localVariables != null) method.localVariables.forEach(local -> { if (local.index >= 2) local.index += 2; });
            if (method.parameters != null) { method.parameters.add(2, new ParameterNode("protocol", 0)); method.parameters.add(3, new ParameterNode("flow", 0)); }
            method.maxLocals += 2;
            for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) if (annotation.desc.endsWith("/Inject;"))
                for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals("method")) annotation.values.set(i + 1, List.of("codec" + codec));
        }
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "CustomPacketPayload.codec", "to", "codec with native protocol and flow", "carpetPayload", "preserved"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private byte[] carpetBlockBreak(ClassNode node) {
        for (MethodNode method : node.methods) if (method.name.equals("onBlockBroken")) {
            String callback = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
            String event = "net/neoforged/neoforge/event/level/BlockEvent$BreakEvent";
            method.desc = "(Lnet/minecraft/core/BlockPos;L" + callback + ";" + STATE + "L" + event + ";Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/level/block/Block;" + STATE + ")V"; method.signature = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof VarInsnNode variable) {
                    if (variable.var == 5) variable.var = 3;
                    else if (variable.var == 3 || variable.var == 4) variable.var += 2;
                    else if (variable.var >= 6) throw new Failure("FABRIC_NATIVE_MIXIN", "Unexpected Carpet block-break captured local " + variable.var);
                }
                if (instruction instanceof FrameNode frame) frame.local = new ArrayList<>(List.of(node.name, POSITION, callback, "net/minecraft/world/level/block/state/BlockState", event, "net/minecraft/world/level/block/entity/BlockEntity", "net/minecraft/world/level/block/Block", "net/minecraft/world/level/block/state/BlockState"));
            }
            if (method.localVariables != null) {
                method.localVariables.forEach(local -> { if (local.index == 5) local.index = 3; else if (local.index == 3 || local.index == 4) local.index += 2; });
            }
            method.parameters = null; method.maxLocals = 8;
            for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) if (annotation.desc.endsWith("/Inject;")) {
                AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;"); at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Lnet/minecraft/server/level/ServerPlayerGameMode;removeBlock(Lnet/minecraft/core/BlockPos;" + STATE + "Z)Z", "remap", false));
                annotation.values = new ArrayList<>(List.of("method", List.of("destroyBlock"), "at", List.of(at), "cancellable", true, "locals", new String[]{"Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;", "CAPTURE_FAILHARD"}));
            }
        }
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "ServerLevel.removeBlock call", "to", "ServerPlayerGameMode.removeBlock", "nativeBreakEvent", "preserved"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private byte[] viaRespawn(ClassNode node) {
        for (MethodNode method : node.methods) if (method.name.equals("checkDimensionChange")) {
            String reason = "Lnet/minecraft/client/gui/screens/ReceivingLevelScreen$Reason;";
            String key = "Lnet/minecraft/resources/ResourceKey;";
            method.desc = method.desc.replace(reason, reason + key + key); method.signature = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof VarInsnNode variable && variable.var >= 5) variable.var += 2;
                if (instruction instanceof IincInsnNode increment && increment.var >= 5) increment.var += 2;
                if (instruction instanceof FrameNode frame && frame.local != null) { frame.local.add(5, "net/minecraft/resources/ResourceKey"); frame.local.add(6, "net/minecraft/resources/ResourceKey"); }
            }
            if (method.localVariables != null) method.localVariables.forEach(local -> { if (local.index >= 5) local.index += 2; });
            if (method.parameters != null) { method.parameters.add(4, new ParameterNode("from", 0)); method.parameters.add(5, new ParameterNode("to", 0)); }
            method.invisibleParameterAnnotations = insertParameters(method.invisibleParameterAnnotations, 4, 2);
            method.visibleParameterAnnotations = insertParameters(method.visibleParameterAnnotations, 4, 2);
            if (method.invisibleAnnotableParameterCount > 0) method.invisibleAnnotableParameterCount += 2;
            if (method.visibleAnnotableParameterCount > 0) method.visibleAnnotableParameterCount += 2;
            method.maxLocals += 2;
            for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) if (annotation.desc.endsWith("/WrapWithCondition;")) {
                AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
                at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Lnet/minecraft/client/multiplayer/ClientPacketListener;startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/multiplayer/ClientLevel;" + reason + key + key + ")V", "remap", false));
                for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals("at")) annotation.values.set(i + 1, List.of(at));
            }
        }
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "ClientPacketListener respawn", "to", "native dimension transition", "transitionScreen", "preserved"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private byte[] fallingBlock(ClassNode node) {
        String nativeTarget = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;" + STATE
                + "Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V";
        for (MethodNode method : node.methods) for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations))
            replaceRenderTarget(annotation, nativeTarget);
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "falling block tesselateBlock", "to", "tesselateBlock with native model data", "featureStateCallbacks", "preserved"));
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    private byte[] viaBlockInteraction(ClassNode node) {
        for (MethodNode method : node.methods) if (method.name.equals("interactBlock1_12_2"))
            for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) changeEmptyOrdinal(annotation);
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "third vanilla empty-stack check", "to", "native item-use empty-stack check", "rightClickEvent", "preserved"));
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    private static void changeEmptyOrdinal(AnnotationNode node) {
        if (node.values == null) return;
        if (hasValue(node, "target", "Lnet/minecraft/item/ItemStack;isEmpty()Z"))
            for (int i = 0; i < node.values.size(); i += 2) if (node.values.get(i).equals("ordinal")) node.values.set(i + 1, 0);
        for (int i = 1; i < node.values.size(); i += 2) {
            Object value = node.values.get(i);
            if (value instanceof AnnotationNode nested) changeEmptyOrdinal(nested);
            if (value instanceof List<?> list) for (Object element : list) if (element instanceof AnnotationNode nested) changeEmptyOrdinal(nested);
        }
    }
    private static void replaceRenderTarget(AnnotationNode node, String target) {
        if (node.values == null) return;
        for (int i = 0; i < node.values.size(); i += 2) {
            Object value = node.values.get(i + 1);
            if (node.values.get(i).equals("target") && value instanceof String name && (name.startsWith("Lnet/minecraft/client/render/block/BlockModelRenderer;render(") || name.startsWith("Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock("))) {
                node.values.set(i + 1, target); node.values.addAll(List.of("remap", false));
            }
            if (value instanceof AnnotationNode nested) replaceRenderTarget(nested, target);
            if (value instanceof List<?> list) for (Object element : list) if (element instanceof AnnotationNode nested) replaceRenderTarget(nested, target);
        }
    }
    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[] insertParameters(List<AnnotationNode>[] original, int at, int count) {
        if (original == null) return null;
        List<AnnotationNode>[] updated = new List[original.length + count];
        System.arraycopy(original, 0, updated, 0, at); System.arraycopy(original, at, updated, at + count, original.length - at); return updated;
    }
    private byte[] levelUpdates(ClassNode node, byte[] original) {
        boolean changed = false;
        for (MethodNode method : node.methods) for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotation.values == null) continue;
            // Only the update mask and neighbor notification moved; HEAD/RETURN
            // callbacks still belong on setBlock, which retains snapshot capture.
            boolean updateMask = annotation.desc.endsWith("/ModifyConstant;") && hasValue(annotation, "intValue", 16);
            boolean neighborCall = annotation.desc.endsWith("/Redirect;") && hasValue(annotation, "target", "Lnet/minecraft/world/level/Level;blockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V");
            if (!updateMask && !neighborCall) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals("method") && annotation.values.get(i + 1) instanceof List<?> selectors) {
                List<Object> updated = new ArrayList<>();
                for (Object selector : selectors) {
                    if (selector.toString().equals("setBlock(Lnet/minecraft/core/BlockPos;" + STATE + "II)Z")) {
                        updated.add("markAndNotifyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;" + STATE + STATE + "II)V"); changed = true;
                    } else updated.add(selector);
                }
                annotation.values.set(i + 1, updated);
            }
        }
        if (!changed) return original;
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "Level.setBlock updates", "to", "Level.markAndNotifyBlock", "nativeSnapshots", "preserved"));
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
    private byte[] shovelPath(ClassNode node, byte[] original) {
        boolean changed = false;
        for (MethodNode method : node.methods) for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (!annotation.desc.endsWith("/Redirect;") || !hasValue(annotation, "target", "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;")
                    || !method.desc.equals("(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;")) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals("method") && annotation.values.get(i + 1) instanceof List<?> selectors
                    && (selectors.contains("useOnBlock") || selectors.contains("useOn"))) {
                annotation.values.set(i + 1, List.of("getShovelPathingState(" + STATE + ")" + STATE));
                if ((method.access & Opcodes.ACC_STATIC) == 0) {
                    for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof VarInsnNode variable && variable.var == 0)
                        throw new Failure("FABRIC_NATIVE_MIXIN", "Cannot move a shovel lookup handler that reads its original receiver: " + node.name + "." + method.name);
                    method.access |= Opcodes.ACC_STATIC;
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (instruction instanceof VarInsnNode variable) variable.var--;
                        if (instruction instanceof IincInsnNode increment) increment.var--;
                        if (instruction instanceof FrameNode frame && frame.local != null && !frame.local.isEmpty()) frame.local.removeFirst();
                    }
                    if (method.localVariables != null) { method.localVariables.removeIf(local -> local.index == 0); method.localVariables.forEach(local -> local.index--); }
                    method.maxLocals--;
                }
                changed = true;
            }
        }
        if (!changed) return original;
        audit.record("PREPARE", "fabric-native-mixin-retarget", node.name, Map.of("from", "ShovelItem.useOn path lookup", "to", "ShovelItem.getShovelPathingState", "nativeToolEvents", "preserved"));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static boolean hasValue(AnnotationNode node, String key, Object expected) {
        if (node.values == null) return false;
        for (int i = 0; i < node.values.size(); i += 2) {
            Object value = node.values.get(i + 1);
            if (node.values.get(i).equals(key) && value.equals(expected)) return true;
            if (value instanceof AnnotationNode nested && hasValue(nested, key, expected)) return true;
            if (value instanceof List<?> list) for (Object element : list) if (element instanceof AnnotationNode nested && hasValue(nested, key, expected)) return true;
        }
        return false;
    }
    private static boolean targets(ClassNode node, String target) {
        for (AnnotationNode annotation : annotations(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || annotation.values == null) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals("value")) {
                for (Object value : (List<?>)annotation.values.get(i + 1)) if (value instanceof Type type && type.getInternalName().equals(target)) return true;
            }
        }
        return false;
    }
    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> result = new ArrayList<>(); if (visible != null) result.addAll(visible); if (invisible != null) result.addAll(invisible); return result;
    }
    private static void addPositionArgument(MethodNode method) {
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 2 : 1;
        method.desc = "(" + STATE + "L" + POSITION + ";" + method.desc.substring(1 + STATE.length());
        if (method.signature != null) method.signature = "(" + STATE + "L" + POSITION + ";" + method.signature.substring(1 + STATE.length());
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode variable && variable.var >= slot) variable.var++;
            if (instruction instanceof IincInsnNode increment && increment.var >= slot) increment.var++;
            if (instruction instanceof FrameNode frame && frame.local != null && frame.local.size() >= slot) frame.local.add(slot, POSITION);
        }
        if (method.localVariables != null) for (LocalVariableNode local : method.localVariables) if (local.index >= slot) local.index++;
        if (method.parameters != null) method.parameters.add(1, new ParameterNode("neoforbric$position", 0));
        method.visibleParameterAnnotations = insertParameter(method.visibleParameterAnnotations);
        method.invisibleParameterAnnotations = insertParameter(method.invisibleParameterAnnotations);
        if (method.visibleAnnotableParameterCount > 0) method.visibleAnnotableParameterCount++;
        if (method.invisibleAnnotableParameterCount > 0) method.invisibleAnnotableParameterCount++;
        method.maxLocals++;
    }
    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[] insertParameter(List<AnnotationNode>[] original) {
        if (original == null) return null;
        List<AnnotationNode>[] updated = new List[original.length + 1];
        updated[0] = original[0]; System.arraycopy(original, 1, updated, 2, original.length - 1); return updated;
    }
}
