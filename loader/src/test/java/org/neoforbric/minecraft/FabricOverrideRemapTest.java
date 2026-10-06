package org.neoforbric.minecraft;

import java.nio.charset.StandardCharsets;
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
import static org.objectweb.asm.Opcodes.*;

class FabricOverrideRemapTest {
    @TempDir Path temporary;
    private Path jar(String name, Map<String, byte[]> classes) throws Exception {
        Path path = temporary.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : classes.entrySet()) { out.putNextEntry(new JarEntry(entry.getKey())); out.write(entry.getValue()); out.closeEntry(); }
        }
        return path;
    }
    private byte[] parent() {
        ClassWriter out = new ClassWriter(0); out.visit(V21, ACC_PUBLIC, "net/minecraft/class_1", null, "java/lang/Object", null);
        var field = out.visitField(ACC_PUBLIC, "observed", "I", null, null);
        field.visitAttribute(new Attribute("FixtureFieldAttribute") {
            @Override protected ByteVector write(ClassWriter writer, byte[] code, int length, int maxStack, int maxLocals) {
                return new ByteVector().putByte(1);
            }
        });
        field.visitEnd();
        var ctor = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); ctor.visitVarInsn(ALOAD, 0); ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_1", "method_1", "()I", false);
        ctor.visitFieldInsn(PUTFIELD, "net/minecraft/class_1", "observed", "I"); ctor.visitInsn(RETURN); ctor.visitMaxs(2, 1); ctor.visitEnd();
        var method = out.visitMethod(ACC_PRIVATE, "method_1", "()I", null, null); method.visitCode();
        method.visitInsn(ICONST_1); method.visitInsn(ICONST_0); method.visitInsn(IDIV); method.visitInsn(IRETURN); method.visitMaxs(2, 1); method.visitEnd();
        integer(out, ACC_PRIVATE, "method_2", 9); out.visitEnd(); return out.toByteArray();
    }
    private void integer(ClassWriter out, int access, String name, int value) {
        var method = out.visitMethod(access, name, "()I", null, null); method.visitCode(); method.visitLdcInsn(value); method.visitInsn(IRETURN); method.visitMaxs(1, 1); method.visitEnd();
    }
    private byte[] child() {
        ClassWriter out = new ClassWriter(0); out.visit(V21, ACC_PUBLIC, "example/Child", null, "net/minecraft/class_1", null);
        var ctor = out.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null); ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "net/minecraft/class_1", "<init>", "()V", false); ctor.visitInsn(RETURN); ctor.visitMaxs(1, 1); ctor.visitEnd();
        integer(out, ACC_PROTECTED, "method_1", 7); integer(out, ACC_PUBLIC, "method_2", 3);
        var call = out.visitMethod(ACC_PUBLIC, "call", "()I", null, null); call.visitCode(); call.visitVarInsn(ALOAD, 0);
        call.visitMethodInsn(INVOKEVIRTUAL, "example/Child", "method_1", "()I", false); call.visitInsn(IRETURN); call.visitMaxs(1, 1); call.visitEnd();
        out.visitEnd(); return out.toByteArray();
    }
    @Test void declaredAccessChangesPreserveConstructorDispatchAndDoNotPropagateUnchangedPrivateMethods() throws Exception {
        var tree = new MemoryMappingTree(); tree.visitNamespaces("intermediary", List.of("mojang")); tree.visitClass("net/minecraft/class_1");
        tree.visitDstName(MappedElementKind.CLASS, 0, "net/minecraft/fixture/SpriteContents"); tree.visitElementContent(MappedElementKind.CLASS);
        for (String[] method : List.of(new String[]{"method_1", "createAnimatedTexture"}, new String[]{"method_2", "privateProbe"})) {
            tree.visitMethod(method[0], "()I"); tree.visitDstName(MappedElementKind.METHOD, 0, method[1]); tree.visitElementContent(MappedElementKind.METHOD);
        }
        tree.visitEnd();
        for (String rule : List.of("accessWidener v1 intermediary\nextendable method net/minecraft/class_1 method_1 ()I\n",
                "classTweaker v1 mojang\nextendable method net/minecraft/fixture/SpriteContents createAnimatedTexture ()I\n")) {
            var access = new FabricRemapAccess(tree); access.read(rule.getBytes(StandardCharsets.UTF_8));
            Path parent = jar("game.jar", Map.of("net/minecraft/class_1.class", parent()));
            Path child = jar("mod.jar", Map.of("example/Child.class", child())), output = temporary.resolve("mapped.jar");
            var remapper = GamePreparation.fabricRemapper(tree, new HashSet<>(), access);
            byte[] runtimeParent;
            try {
                remapper.readClassPath(parent); remapper.readInputs(child);
                try (var consumer = new OutputConsumerPath.Builder(output).build()) { remapper.apply(consumer); }
                ClassWriter widened = new ClassWriter(0); new ClassReader(parent()).accept(access.analyze(0, "net/minecraft/class_1", widened), 0);
                ClassWriter named = new ClassWriter(0); new ClassReader(widened.toByteArray()).accept(new org.objectweb.asm.commons.ClassRemapper(named, remapper.getRemapper()), 0);
                runtimeParent = named.toByteArray();
            } finally { remapper.finish(); }
            byte[] runtimeChild; try (var jar = new JarFile(output.toFile()); var input = jar.getInputStream(jar.getJarEntry("example/Child.class"))) { runtimeChild = input.readAllBytes(); }
            ClassNode node = new ClassNode(); new ClassReader(runtimeChild).accept(node, 0);
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("createAnimatedTexture")));
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("method_2"))); // No blanket private propagation.
            class FixtureLoader extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
            var loader = new FixtureLoader(); Class<?> base = loader.define(runtimeParent), subclass = loader.define(runtimeChild);
            Object instance = subclass.getConstructor().newInstance();
            assertEquals(7, base.getField("observed").get(instance)); assertEquals(7, subclass.getMethod("call").invoke(instance));
        }
    }
}
