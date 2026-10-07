package org.neoforbric.minecraft;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.loader.Failure;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgePreparationTest {
    @TempDir Path root;
    private Path jar(String file, Map<String, String> entries) throws Exception {
        Path path = root.resolve(file);
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) { jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue().getBytes()); jar.closeEntry(); }
        }
        return path;
    }
    @Test void patchOverlayRetainsUnpatchedClassesAndExtraResources() throws Exception {
        Path clean = jar("clean.jar", Map.of("Unchanged.class", "original", "Changed.class", "before"));
        Path patch = jar("patch.jar", Map.of("Changed.class", "after", "Added.class", "added"));
        Path extra = jar("extra.jar", Map.of("version.json", "1.21.1"));
        Path merged = root.resolve("merged.jar"); NeoForgePreparation.merge(List.of(clean, patch, extra), merged);
        try (var jar = new JarFile(merged.toFile())) {
            for (var entry : Map.of("Unchanged.class", "original", "Changed.class", "after", "Added.class", "added", "version.json", "1.21.1").entrySet())
                try (var stream = jar.getInputStream(jar.getJarEntry(entry.getKey()))) { assertEquals(entry.getValue(), new String(stream.readAllBytes())); }
        }
    }
    @Test void extraCannotReplacePatchedGameClasses() throws Exception {
        var inputs = List.of(jar("clean.jar", Map.of()), jar("patch.jar", Map.of("Changed.class", "patched")), jar("extra.jar", Map.of("Changed.class", "conflict")));
        assertEquals("DUPLICATE_CLASS", assertThrows(Failure.class, () -> NeoForgePreparation.merge(inputs, root.resolve("merged.jar"))).code());
    }
    @Test void artifactAndOutputPathsCannotEscapeTheirWorkspace() {
        assertEquals("net/neoforged/neoforge/21.1.244/neoforge-21.1.244-universal.jar", NeoForgePreparation.artifactPath("net.neoforged:neoforge:21.1.244:universal"));
        for (String value : List.of("../outside", "C:/outside", "/outside", "sub\\outside"))
            assertEquals("NEOFORGE_PATH", assertThrows(Failure.class, () -> NeoForgePreparation.contained(root, value)).code());
        for (String value : List.of("net.neoforged:../outside:1", "bad", "g:a:1@../jar"))
            assertThrows(Failure.class, () -> NeoForgePreparation.artifactPath(value));
    }
}
