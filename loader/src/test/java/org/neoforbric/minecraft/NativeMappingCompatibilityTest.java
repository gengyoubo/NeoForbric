package org.neoforbric.minecraft;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeMappingCompatibilityTest {
    @TempDir Path root;
    @Test void compilerRenumberingRequiresAnUnambiguousOriginalAndNativeLambda() throws Exception {
        var tree = new MemoryMappingTree(); tree.visitNamespaces("intermediary", List.of("mojang"));
        tree.visitClass("class_1"); tree.visitDstName(MappedElementKind.CLASS, 0, "example/Target"); tree.visitElementContent(MappedElementKind.CLASS);
        Map<String, String> names = Map.of("method_1", "lambda$bed$7", "method_2", "lambda$duplicate$1", "method_3", "lambda$duplicate$2", "method_4", "lambda$missing$1");
        for (var entry : names.entrySet()) { tree.visitMethod(entry.getKey(), "()Z"); tree.visitDstName(MappedElementKind.METHOD, 0, entry.getValue()); tree.visitElementContent(MappedElementKind.METHOD); }
        tree.visitEnd();
        var writer = new ClassWriter(0); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/Target", null, "java/lang/Object", null);
        for (String name : List.of("lambda$bed$10", "lambda$duplicate$2")) {
            var method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, "()Z", null, null);
            method.visitCode(); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(1, 0); method.visitEnd();
        }
        writer.visitEnd(); Path game = root.resolve("game.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(game))) { jar.putNextEntry(new JarEntry("example/Target.class")); jar.write(writer.toByteArray()); jar.closeEntry(); }
        NativeMappingCompatibility.reconcile(tree, game);
        assertEquals("lambda$bed$10", tree.getMethod("class_1", "method_1", "()Z", -1).getName(0));
        assertEquals("lambda$duplicate$1", tree.getMethod("class_1", "method_2", "()Z", -1).getName(0), "Removed lambdas must not be merged into another method");
        assertEquals("lambda$duplicate$2", tree.getMethod("class_1", "method_3", "()Z", -1).getName(0));
        assertEquals("lambda$missing$1", tree.getMethod("class_1", "method_4", "()Z", -1).getName(0), "Missing methods must remain a real compatibility failure");
    }
}
