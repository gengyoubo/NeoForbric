package org.neoforbric.minecraft;

import java.util.*;
import kotlin.Metadata;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.metadata.*;
import kotlin.metadata.jvm.*;
import kotlin.reflect.full.KClasses;
import kotlin.reflect.jvm.ReflectJvmMapping;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import static kotlin.metadata.jvm.JvmExtensionsKt.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class FabricKotlinMetadataTest {
    private byte[] propertyClass() {
        KmClass klass = new KmClass(); klass.setName("demo/Properties");
        Attributes.setVisibility(klass, Visibility.PUBLIC);
        KmType type = new KmType(); type.setClassifier(new KmClassifier.Class("net/minecraft/class_2960"));
        KmProperty property = new KmProperty("identifier"); property.setReturnType(type);
        Attributes.setVisibility(property, Visibility.PUBLIC);
        Attributes.setVisibility(property.getGetter(), Visibility.PUBLIC);
        setGetterSignature(property, new JvmMethodSignature("getIdentifier", "()Lnet/minecraft/class_2960;"));
        klass.getProperties().add(property);
        Metadata metadata = new KotlinClassMetadata.Class(klass, new JvmMetadataVersion(2, 2, 0), 0).write();
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V21, ACC_PUBLIC, "demo/Properties", null, "java/lang/Object", null);
        AnnotationVisitor annotation = writer.visitAnnotation("Lkotlin/Metadata;", true);
        annotation.visit("k", metadata.k()); annotation.visit("mv", metadata.mv());
        AnnotationVisitor data1 = annotation.visitArray("d1");
        for (String item : metadata.d1()) data1.visit(null, item);
        data1.visitEnd();
        AnnotationVisitor data2 = annotation.visitArray("d2");
        for (String item : metadata.d2()) data2.visit(null, item);
        data2.visitEnd(); annotation.visit("xi", metadata.xi()); annotation.visitEnd();
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC, "getIdentifier", "()Lnet/minecraft/class_2960;", null, null);
        method.visitCode(); method.visitInsn(ACONST_NULL); method.visitInsn(ARETURN); method.visitMaxs(1, 1); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    @Test void realKotlinReflectionResolvesTheRemappedPropertyGetter() throws Exception {
        Remapper mapper = new SimpleRemapper(Map.of("net/minecraft/class_2960", "demo/ResourceLocation"));
        ClassWriter output = new ClassWriter(0);
        new ClassReader(propertyClass()).accept(new ClassRemapper(output, mapper), 0);
        byte[] javaRemapped = output.toByteArray();
        ClassWriter resource = new ClassWriter(0);
        resource.visit(V21, ACC_PUBLIC, "demo/ResourceLocation", null, "java/lang/Object", null); resource.visitEnd();
        class Types extends ClassLoader {
            Types() { super(FabricKotlinMetadataTest.class.getClassLoader()); }
            Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        }
        Types broken = new Types(); broken.define("demo.ResourceLocation", resource.toByteArray());
        Class<?> before = broken.define("demo.Properties", javaRemapped);
        var beforeProperty = KClasses.getMemberProperties(JvmClassMappingKt.getKotlinClass(before)).iterator().next();
        assertThrows(ClassNotFoundException.class, () -> ReflectJvmMapping.getJavaGetter(beforeProperty));

        Types fixed = new Types(); Class<?> expected = fixed.define("demo.ResourceLocation", resource.toByteArray());
        Class<?> after = fixed.define("demo.Properties", FabricKotlinMetadata.remap(javaRemapped, mapper));
        var afterProperty = KClasses.getMemberProperties(JvmClassMappingKt.getKotlinClass(after)).iterator().next();
        assertEquals(expected, ReflectJvmMapping.getJavaGetter(afterProperty).getReturnType());
        assertEquals("identifier", afterProperty.getName());
    }
}
