package org.neoforbric.minecraft;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.fabricmc.mappingio.*;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricMixinRemapTest implements Opcodes {
    @TempDir Path temporary;

    @Test void refmapMixinsRemapShadowsAndReferencesWithoutChangingSelectors() throws Exception {
        assertRemap(false, true, "travel", "jumping");
    }
    @Test void staticMixinsAlsoRemapInjectionSelectors() throws Exception {
        assertRemap(true, true, "method_1", "jumping");
    }
    @Test void shadowRemapFalsePreservesTheDeclaredName() throws Exception {
        assertRemap(false, false, "travel", "field_1");
    }

    private void assertRemap(boolean staticMixin, boolean shadowRemap, String selector, String expectedField) throws Exception {
        Path source = temporary.resolve("input.jar"), output = temporary.resolve("output.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(source))) {
            jar.putNextEntry(new JarEntry("net/minecraft/class_1.class")); jar.write(target()); jar.closeEntry();
            jar.putNextEntry(new JarEntry("example/TravelMixin.class")); jar.write(mixin(shadowRemap, selector)); jar.closeEntry();
        }
        var tree = new MemoryMappingTree(); tree.visitNamespaces("intermediary", List.of("mojang"));
        tree.visitClass("net/minecraft/class_1"); tree.visitDstName(MappedElementKind.CLASS, 0, "net/minecraft/world/entity/LivingEntity"); tree.visitElementContent(MappedElementKind.CLASS);
        tree.visitField("field_1", "Z"); tree.visitDstName(MappedElementKind.FIELD, 0, "jumping"); tree.visitElementContent(MappedElementKind.FIELD);
        tree.visitMethod("method_1", "()V"); tree.visitDstName(MappedElementKind.METHOD, 0, "travel"); tree.visitElementContent(MappedElementKind.METHOD); tree.visitEnd();
        Set<InputTag> staticTags = new HashSet<>();
        TinyRemapper remapper = GamePreparation.fabricRemapper(tree, staticTags);
        try {
            InputTag tag = remapper.createInputTag(); if (staticMixin) staticTags.add(tag);
            remapper.readInputs(tag, source);
            try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) { remapper.apply(consumer, tag); }
        } finally { remapper.finish(); }
        ClassNode node = new ClassNode();
        try (JarFile jar = new JarFile(output.toFile())) { new ClassReader(jar.getInputStream(jar.getJarEntry("example/TravelMixin.class"))).accept(node, 0); }
        assertEquals(expectedField, node.fields.getFirst().name);
        assertEquals(ACC_PROTECTED, node.fields.getFirst().access);
        MethodNode read = node.methods.stream().filter(m -> m.name.equals("readJumping")).findFirst().orElseThrow();
        FieldInsnNode reference = (FieldInsnNode) Arrays.stream(read.instructions.toArray()).filter(FieldInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals(expectedField, reference.name); assertEquals(node.name, reference.owner);
        MethodNode handler = node.methods.stream().filter(m -> m.name.equals("onTravel")).findFirst().orElseThrow();
        AnnotationNode injection = handler.visibleAnnotations.getFirst();
        @SuppressWarnings("unchecked") List<String> selectors = (List<String>) injection.values.get(1);
        assertEquals(List.of(staticMixin ? "travel()V" : selector), selectors);
        @SuppressWarnings("unchecked") List<Type> targets = (List<Type>) node.invisibleAnnotations.getFirst().values.get(1);
        assertEquals("net/minecraft/world/entity/LivingEntity", targets.getFirst().getInternalName());
    }

    private byte[] target() {
        ClassWriter out = new ClassWriter(0); out.visit(V21, ACC_PUBLIC, "net/minecraft/class_1", null, "java/lang/Object", null);
        out.visitField(ACC_PROTECTED, "field_1", "Z", null, null).visitEnd();
        MethodVisitor travel = out.visitMethod(ACC_PUBLIC, "method_1", "()V", null, null);
        travel.visitCode(); travel.visitInsn(RETURN); travel.visitMaxs(0, 1); travel.visitEnd(); out.visitEnd(); return out.toByteArray();
    }
    private byte[] mixin(boolean remap, String selector) {
        ClassWriter out = new ClassWriter(0); out.visit(V21, ACC_PUBLIC | ACC_ABSTRACT, "example/TravelMixin", null, "java/lang/Object", null);
        AnnotationVisitor mixin = out.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value"); targets.visit(null, Type.getObjectType("net/minecraft/class_1")); targets.visitEnd(); mixin.visitEnd();
        FieldVisitor field = out.visitField(ACC_PROTECTED, "field_1", "Z", null, null);
        AnnotationVisitor shadow = field.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true); shadow.visit("remap", remap);
        AnnotationVisitor aliases = shadow.visitArray("aliases"); aliases.visit(null, "field_wrong"); aliases.visit(null, "jumping"); aliases.visitEnd(); shadow.visitEnd(); field.visitEnd();
        MethodVisitor read = out.visitMethod(ACC_PUBLIC, "readJumping", "()Z", null, null); read.visitCode(); read.visitVarInsn(ALOAD, 0);
        read.visitFieldInsn(GETFIELD, "example/TravelMixin", "field_1", "Z"); read.visitInsn(IRETURN); read.visitMaxs(1, 1); read.visitEnd();
        MethodVisitor handler = out.visitMethod(ACC_PRIVATE, "onTravel", "()V", null, null);
        AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method"); methods.visit(null, selector); methods.visitEnd(); inject.visitEnd();
        handler.visitCode(); handler.visitInsn(RETURN); handler.visitMaxs(0, 1); handler.visitEnd(); out.visitEnd(); return out.toByteArray();
    }
}
