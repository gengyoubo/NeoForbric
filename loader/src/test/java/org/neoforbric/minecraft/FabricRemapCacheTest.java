package org.neoforbric.minecraft;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class FabricRemapCacheTest {
    @TempDir Path temporary;

    private Path jar(String name, Map<String, byte[]> files) throws Exception {
        Path path = temporary.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : files.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return path;
    }
    private byte[] game(String name, String field) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, field, "I", null, null).visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private byte[] mod() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V21, ACC_PUBLIC, "example/Reader", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "(Lnet/minecraft/class_1;)I", null, null);
        method.visitCode(); method.visitVarInsn(ALOAD, 0);
        method.visitFieldInsn(GETFIELD, "net/minecraft/class_1", "field_1", "I");
        method.visitInsn(IRETURN); method.visitMaxs(1, 1); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private String remappedField(Path jar) throws Exception {
        ClassNode node = new ClassNode();
        try (JarFile input = new JarFile(jar.toFile())) {
            new ClassReader(input.getInputStream(input.getJarEntry("example/Reader.class"))).accept(node, 0);
        }
        return Arrays.stream(node.methods.getFirst().instructions.toArray()).filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast).findFirst().orElseThrow().name;
    }

    @Test void cacheRejectsTamperingAndInvalidatesWhenGameMappingsChange() throws Exception {
        String named = "net/minecraft/world/item/ItemStack";
        Path game = jar("game.jar", Map.of(named + ".class", game(named, "count")));
        Path intermediary = jar("intermediary-game.jar", Map.of("net/minecraft/class_1.class", game("net/minecraft/class_1", "field_1")));
        Path mappings = temporary.resolve("mappings.txt");
        Files.writeString(mappings, "net.minecraft.world.item.ItemStack -> a:\n    int count -> b\n");
        Path intermediaryMappings = jar("intermediary.jar", Map.of("mappings/mappings.tiny",
                "tiny\t2\t0\tofficial\tintermediary\nc\ta\tnet/minecraft/class_1\n\tf\tI\tb\tfield_1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        RuntimeInputs inputs = new RuntimeInputs(game, intermediary, mappings, intermediaryMappings, List.of(), "", "client", null, null);
        Path source = jar("source.jar", Map.of("example/Reader.class", mod(), "fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"cache_probe\",\"version\":\"1.0.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        byte[] original = Files.readAllBytes(source);
        Path cache = temporary.resolve("cache"), output = temporary.resolve("output.jar");
        var mods = List.of(new GamePreparation.FabricInput(source, output, null));
        FabricRemapCache.remap(mods, inputs, cache);
        assertEquals("count", remappedField(output));
        byte[] first = Files.readAllBytes(output);
        Files.write(source, original); Files.delete(output);
        FabricRemapCache.remap(mods, inputs, cache);
        assertArrayEquals(first, Files.readAllBytes(output));
        Path cached;
        try (var files = Files.walk(cache)) { cached = files.filter(p -> p.getFileName().toString().equals("0.jar")).findFirst().orElseThrow(); }
        Files.writeString(cached, "tampered derived archive");
        Files.write(source, original);
        FabricRemapCache.remap(mods, inputs, cache);
        assertEquals("count", remappedField(output));
        jar("game.jar", Map.of(named + ".class", game(named, "total")));
        Files.writeString(mappings, "net.minecraft.world.item.ItemStack -> a:\n    int total -> b\n");
        Files.write(source, original);
        FabricRemapCache.remap(mods, inputs, cache);
        assertEquals("total", remappedField(output));
    }
}
