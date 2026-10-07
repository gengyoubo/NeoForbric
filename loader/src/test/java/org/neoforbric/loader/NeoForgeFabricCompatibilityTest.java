package org.neoforbric.loader;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.neoforbric.neoforge.NeoForgeFabricCompatibility;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgeFabricCompatibilityTest {
    private ClassNode mixin(String target) {
        ClassNode node = new ClassNode(); node.version = Opcodes.V21; node.access = Opcodes.ACC_PUBLIC;
        node.name = "example/Mixin"; node.superName = "java/lang/Object";
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        annotation.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(target))));
        node.invisibleAnnotations = new ArrayList<>(List.of(annotation)); return node;
    }
    private AnnotationNode inject(String kind, String selector) {
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/" + kind + ";");
        annotation.values = new ArrayList<>(List.of("method", List.of(selector))); return annotation;
    }
    private byte[] transform(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); byte[] input = writer.toByteArray();
        return new NeoForgeFabricCompatibility(new AuditLog()).transform(new TransformPipeline.Context(node.name, ignored -> input), input);
    }
    private void constructor(ClassWriter writer) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
    }
    @Test void nativeResourceDiscoveryRestoresBasePacksOnceAndKeepsParentAndOtherPacks() throws Exception {
        String owner = "net/fabricmc/fabric/impl/resource/loader/ModResourcePackCreator";
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null); constructor(writer);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BASE_PARENT", "Ljava/util/function/Predicate;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "seenParent", "Ljava/util/function/Predicate;", null, null).visitEnd();
        var register = writer.visitMethod(Opcodes.ACC_PRIVATE, "registerModPack", "(Ljava/util/function/Consumer;Ljava/lang/String;Ljava/util/function/Predicate;)V", null, null);
        register.visitCode(); register.visitVarInsn(Opcodes.ALOAD, 0); register.visitVarInsn(Opcodes.ALOAD, 3);
        register.visitFieldInsn(Opcodes.PUTFIELD, owner, "seenParent", "Ljava/util/function/Predicate;");
        register.visitVarInsn(Opcodes.ALOAD, 1); register.visitVarInsn(Opcodes.ALOAD, 2); register.visitLdcInsn("base");
        register.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "toString", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false);
        register.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
        register.visitInsn(Opcodes.RETURN); register.visitMaxs(3, 4); register.visitEnd();
        var load = writer.visitMethod(Opcodes.ACC_PUBLIC, "loadPacks", "(Ljava/util/function/Consumer;)V", null, null); load.visitCode();
        load.visitVarInsn(Opcodes.ALOAD, 1); load.visitLdcInsn("programmer_art");
        load.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
        load.visitInsn(Opcodes.RETURN); load.visitMaxs(2, 2); load.visitEnd(); writer.visitEnd();
        ClassNode node = new ClassNode(); new ClassReader(writer.toByteArray()).accept(node, ClassReader.EXPAND_FRAMES);
        byte[] adapted = transform(node);
        ClassNode again = new ClassNode(); new ClassReader(adapted).accept(again, ClassReader.EXPAND_FRAMES);
        class Types extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
        Class<?> type = new Types().define(transform(again));
        java.util.function.Predicate<Set<String>> parent = enabled -> enabled.contains("fabric");
        type.getField("BASE_PARENT").set(null, parent); Object source = type.getConstructor().newInstance();
        List<String> packs = new ArrayList<>(); java.util.function.Consumer<String> collect = packs::add;
        type.getMethod("loadPacks", java.util.function.Consumer.class).invoke(source, collect);
        assertEquals(List.of("programmer_art", "base"), packs);
        assertSame(parent, type.getField("seenParent").get(source));
    }
    @Test void changedNativeResourceContractFailsExplicitly() {
        ClassNode node = mixin("example/Unused"); node.name = "net/fabricmc/fabric/impl/resource/loader/ModResourcePackCreator";
        MethodNode load = new MethodNode(Opcodes.ACC_PUBLIC, "loadPacks", "(Ljava/util/function/Consumer;)V", null, null);
        load.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(load);
        assertEquals("FABRIC_RESOURCE_CONTRACT", assertThrows(Failure.class, () -> transform(node)).code());
    }
    @Test void settingsBeforeFontConstructionSkipOnlyCacheInvalidationAndKeepValidFrames() throws Exception {
        String clientName = "net/minecraft/client/Minecraft", fontName = "net/minecraft/client/gui/font/FontManager";
        ClassWriter font = new ClassWriter(0); font.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, fontName, null, "java/lang/Object", null); constructor(font);
        font.visitField(Opcodes.ACC_PUBLIC, "clears", "I", null, null).visitEnd();
        var clear = font.visitMethod(Opcodes.ACC_PUBLIC, "clear", "()V", null, null); clear.visitCode(); clear.visitVarInsn(Opcodes.ALOAD, 0); clear.visitInsn(Opcodes.DUP);
        clear.visitFieldInsn(Opcodes.GETFIELD, fontName, "clears", "I"); clear.visitInsn(Opcodes.ICONST_1); clear.visitInsn(Opcodes.IADD);
        clear.visitFieldInsn(Opcodes.PUTFIELD, fontName, "clears", "I"); clear.visitInsn(Opcodes.RETURN); clear.visitMaxs(3, 1); clear.visitEnd(); font.visitEnd();
        ClassWriter client = new ClassWriter(0); client.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, clientName, null, "java/lang/Object", null); constructor(client);
        client.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "instance", "L" + clientName + ";", null, null).visitEnd();
        client.visitField(Opcodes.ACC_PUBLIC, "fontManager", "L" + fontName + ";", null, null).visitEnd();
        var instance = client.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getInstance", "()L" + clientName + ";", null, null); instance.visitCode();
        instance.visitFieldInsn(Opcodes.GETSTATIC, clientName, "instance", "L" + clientName + ";"); instance.visitInsn(Opcodes.ARETURN); instance.visitMaxs(1, 0); instance.visitEnd(); client.visitEnd();
        ClassWriter settings = new ClassWriter(ClassWriter.COMPUTE_FRAMES); settings.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "de/florianmichael/viafabricplus/settings/impl/VisualSettings$1", null, "java/lang/Object", null); constructor(settings);
        var change = settings.visitMethod(Opcodes.ACC_PUBLIC, "onValueChanged", "()V", null, null); change.visitCode();
        change.visitMethodInsn(Opcodes.INVOKESTATIC, clientName, "getInstance", "()L" + clientName + ";", false); change.visitVarInsn(Opcodes.ASTORE, 1);
        Label end = new Label(); change.visitVarInsn(Opcodes.ALOAD, 1); change.visitJumpInsn(Opcodes.IFNULL, end); change.visitVarInsn(Opcodes.ALOAD, 1);
        change.visitFieldInsn(Opcodes.GETFIELD, clientName, "fontManager", "L" + fontName + ";"); change.visitMethodInsn(Opcodes.INVOKEVIRTUAL, fontName, "clear", "()V", false);
        change.visitLabel(end); change.visitInsn(Opcodes.RETURN); change.visitMaxs(0, 0); change.visitEnd(); settings.visitEnd();
        ClassNode node = new ClassNode(); new ClassReader(settings.toByteArray()).accept(node, ClassReader.EXPAND_FRAMES);
        class Types extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
        Types types = new Types(); Class<?> fontType = types.define(font.toByteArray()), clientType = types.define(client.toByteArray()), settingType = types.define(transform(node));
        Object game = clientType.getConstructor().newInstance(), setting = settingType.getConstructor().newInstance(); clientType.getField("instance").set(null, game);
        settingType.getMethod("onValueChanged").invoke(setting);
        Object cache = fontType.getConstructor().newInstance(); clientType.getField("fontManager").set(game, cache);
        settingType.getMethod("onValueChanged").invoke(setting); assertEquals(1, fontType.getField("clears").get(cache));
    }
    @Test void movedShovelLookupStillExecutesItsHandlerAsAValidStaticMethod() throws Exception {
        ClassNode node = mixin("net/minecraft/world/item/ShovelItem");
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "lookup", "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        AnnotationNode annotation = inject("Redirect", "useOnBlock");
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>(List.of("target", "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"));
        annotation.values.addAll(List.of("at", at)); method.visibleAnnotations = new ArrayList<>(List.of(annotation));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); LabelNode lookup = new LabelNode();
        method.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, lookup));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(lookup); method.instructions.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{node.name, "java/util/Map", "java/lang/Object"}, 0, new Object[0]));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.ARETURN)); method.maxLocals = 3; node.methods.add(method);
        byte[] bytes = transform(node);
        Class<?> type = new ClassLoader() { Class<?> define() { return defineClass(null, bytes, 0, bytes.length); } }.define();
        var handler = type.getMethod("lookup", Map.class, Object.class);
        assertEquals("path", handler.invoke(null, Map.of("grass", "path"), "grass"));
        assertNull(handler.invoke(null, Map.of("grass", "path"), null));
    }
    @Test void addedPlayerArgumentPreservesCallbackAndMixinExtrasLocalPositions() {
        ClassNode node = mixin("net/minecraft/world/entity/player/Player");
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "speed", "(Lnet/minecraft/world/level/block/state/BlockState;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;Ljava/lang/Object;)V", null, null);
        method.visibleAnnotations = new ArrayList<>(List.of(inject("Inject", "getDestroySpeed")));
        method.visibleParameterAnnotations = new List[3];
        method.visibleParameterAnnotations[2] = List.of(new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxLocals = 4; node.methods.add(method);
        ClassNode output = new ClassNode(); new ClassReader(transform(node)).accept(output, 0);
        MethodNode result = output.methods.getFirst();
        assertTrue(result.desc.contains("BlockState;Lnet/minecraft/core/BlockPos;"));
        assertEquals(4, ((VarInsnNode)result.instructions.getFirst()).var);
        assertNull(result.visibleParameterAnnotations[1]);
        assertEquals("Lcom/llamalad7/mixinextras/sugar/Local;", result.visibleParameterAnnotations[3].getFirst().desc);
    }
    @Test void unrelatedMixinAndPlayerCallbackAreLeftUntouched() {
        ClassNode node = mixin("net/minecraft/world/entity/player/Player");
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "other", "()V", null, null);
        method.visibleAnnotations = new ArrayList<>(List.of(inject("Inject", "tick")));
        method.instructions.add(new InsnNode(Opcodes.RETURN)); node.methods.add(method);
        ClassNode result = new ClassNode(); new ClassReader(transform(node)).accept(result, 0);
        assertEquals("()V", result.methods.getFirst().desc);
        assertEquals(List.of("tick"), result.methods.getFirst().visibleAnnotations.getFirst().values.get(1));
    }
}
