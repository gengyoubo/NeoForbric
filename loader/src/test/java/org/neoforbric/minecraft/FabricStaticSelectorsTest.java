package org.neoforbric.minecraft;

import java.util.List;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class FabricStaticSelectorsTest {
    @Test void legacyMixinDirectSelectorsMapButRefmapLookupKeysRemainUnchanged() throws Exception {
        var tree = new MemoryMappingTree(); tree.visitNamespaces("intermediary", List.of("mojang"));
        tree.visitClass("net/minecraft/class_3785"); tree.visitDstName(MappedElementKind.CLASS, 0, "net/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool"); tree.visitElementContent(MappedElementKind.CLASS);
        tree.visitMethod("method_28886", "()V"); tree.visitDstName(MappedElementKind.METHOD, 0, "lambda$static$0"); tree.visitElementContent(MappedElementKind.METHOD); tree.visitEnd();
        ClassWriter writer = new ClassWriter(0); writer.visit(V21, ACC_PUBLIC, "demo/WeightMixin", null, "java/lang/Object", null);
        var mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var targets = mixin.visitArray("value"); targets.visit(null, Type.getObjectType("net/minecraft/world/level/levelgen/structure/pools/StructureTemplatePool")); targets.visitEnd(); mixin.visitEnd();
        for (boolean direct : List.of(true, false)) {
            var method = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, direct ? "direct" : "refmap", "()V", null, null);
            var injection = method.visitAnnotation("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", true);
            var methods = injection.visitArray("method"); methods.visit(null, "m_dgkaflam"); methods.visit(null, "method_28886"); methods.visitEnd();
            if (direct) injection.visit("remap", false);
            injection.visit("require", 0); injection.visitEnd();
            method.visitCode(); method.visitInsn(RETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); ClassNode output = new ClassNode();
        new ClassReader(FabricStaticSelectors.remap(writer.toByteArray(), new FabricRefmaps.SelectorMapper(tree), false)).accept(output, 0);
        assertEquals(List.of("m_dgkaflam", "lambda$static$0"), output.methods.get(0).visibleAnnotations.getFirst().values.get(1));
        assertEquals(List.of("m_dgkaflam", "method_28886"), output.methods.get(1).visibleAnnotations.getFirst().values.get(1));
        assertEquals(0, output.methods.get(0).visibleAnnotations.getFirst().values.get(5));
    }
    @Test void staticThirdPartyMixinResolvesNestedMinecraftAtEvenWithRemapFalse() throws Exception {
        var tree = new MemoryMappingTree(); tree.visitNamespaces("intermediary", List.of("mojang"));
        tree.visitClass("net/minecraft/class_4597$class_4598");
        tree.visitDstName(MappedElementKind.CLASS, 0, "net/minecraft/client/renderer/MultiBufferSource$BufferSource"); tree.visitElementContent(MappedElementKind.CLASS);
        tree.visitMethod("method_22993", "()V"); tree.visitDstName(MappedElementKind.METHOD, 0, "endBatch"); tree.visitElementContent(MappedElementKind.METHOD); tree.visitEnd();
        String selector = "Lnet/minecraft/class_4597$class_4598;method_22993()V";
        ClassWriter writer = new ClassWriter(0); writer.visit(V21, ACC_PUBLIC, "demo/Mixin", null, "java/lang/Object", null);
        var mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var targets = mixin.visitArray("targets"); targets.visit(null, "net/irisshaders/iris/shadows/ShadowRenderer"); targets.visitEnd();
        mixin.visit("remap", false); mixin.visitEnd();
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "inject", "()V", null, null);
        var inject = method.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        var methods = inject.visitArray("method"); methods.visit(null, "renderShadows"); methods.visitEnd();
        var ats = inject.visitArray("at"); var at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE"); at.visit("target", selector); at.visitEnd(); ats.visitEnd(); inject.visitEnd();
        var ordinary = method.visitAnnotation("Ldemo/Ordinary;", true); ordinary.visit("value", selector); ordinary.visitEnd();
        method.visitCode(); method.visitLdcInsn(selector); method.visitInsn(POP); method.visitInsn(RETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd();
        ClassNode output = new ClassNode(); new ClassReader(FabricStaticSelectors.remap(writer.toByteArray(), new FabricRefmaps.SelectorMapper(tree))).accept(output, 0);
        assertEquals(List.of("net/irisshaders/iris/shadows/ShadowRenderer"), output.invisibleAnnotations.getFirst().values.get(1));
        assertEquals(false, output.invisibleAnnotations.getFirst().values.get(3));
        var actual = (AnnotationNode) ((List<?>) output.methods.getFirst().visibleAnnotations.getFirst().values.get(3)).getFirst();
        assertEquals("INVOKE", actual.values.get(1));
        assertEquals("Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", actual.values.get(3));
        assertEquals(selector, output.methods.getFirst().visibleAnnotations.get(1).values.get(1));
        assertEquals(selector, ((LdcInsnNode) output.methods.getFirst().instructions.getFirst()).cst);
    }
}
