package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.neoforge.NeoForgeDiscovery;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgeDiscoveryTest {
    @TempDir Path root;
    private byte[] descriptor(String id, String version) { return TestJars.text("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"Test\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n"); }
    private byte[] nested(String version) throws Exception {
        return Files.readAllBytes(TestJars.jar(root.resolve("child-" + version + ".jar"), Map.of("META-INF/neoforge.mods.toml", descriptor("nested_mod", version))));
    }
    private Path wrapper(Path mods, String name, String artifact, String version, String range, byte[] child) throws Exception {
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("FMLModType", "LIBRARY");
        String json = "{\"jars\":[{\"identifier\":{\"group\":\"test\",\"artifact\":\"" + artifact + "\"},\"version\":{\"artifactVersion\":\"" + version + "\",\"range\":\"" + range + "\"},\"path\":\"META-INF/jarjar/child.jar\"}]}";
        return TestJars.jar(mods.resolve(name + ".jar"), Map.of("META-INF/jarjar/metadata.json", TestJars.text(json), "META-INF/jarjar/child.jar", child), manifest);
    }
    @Test void prefersNativeDescriptorAndSelectsCommonNestedVersionWithoutExecutingClasses() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        TestJars.jar(mods.resolve("universal.jar"), Map.of("META-INF/neoforge.mods.toml", descriptor("native_mod", "1"), "fabric.mod.json", TestJars.text("{\"schemaVersion\":1,\"id\":\"fabric_mod\",\"version\":\"1\"}")));
        wrapper(mods, "old", "nested", "1", "[1,3)", nested("1"));
        wrapper(mods, "new", "nested", "2", "[2,3)", nested("2"));
        var discovered = NeoForgeDiscovery.discover(mods, new AuditLog());
        assertEquals(List.of("native_mod", "nested_mod"), discovered.mods().stream().map(mod -> mod.metadata().id()).toList());
        assertEquals("2", discovered.mods().getLast().metadata().version()); assertEquals(2, discovered.libraries().size());
    }
    @Test void mergesNestedCoordinateAliasesOnlyWhenAllRangesAllowTheSelectedVersion() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        wrapper(mods, "old", "renamed-old", "1", "[1,)", nested("1"));
        wrapper(mods, "new", "renamed-new", "2", "[2,)", nested("2"));
        assertEquals("2", NeoForgeDiscovery.discover(mods, new AuditLog()).mods().getFirst().metadata().version());
        wrapper(mods, "old", "renamed-old", "1", "[1]", nested("1"));
        assertEquals("JARJAR_VERSION", assertThrows(Failure.class, () -> NeoForgeDiscovery.discover(mods, new AuditLog())).code());
    }
    @Test void discoversCrashAssistantRuntimeWithoutLaunchingItsHelper() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        byte[] child = Files.readAllBytes(TestJars.jar(root.resolve("runtime.jar"), Map.of("META-INF/neoforge.mods.toml", descriptor("crash_assistant", "1"), "crash_assistant.mixins.json", TestJars.text("{}"))));
        Path wrapper = TestJars.jar(mods.resolve("wrapper.jar"), Map.of("META-INF/neoforge.mods.toml", descriptor("crash_assistant", "1"), "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator", TestJars.text("dev.kostromdan.mods.crash_assistant.core_mod.services.CrashAssistantDependencyLocator\n"), "META-INF/jarjar/crash_assistant-neoforge.jar", child, "META-INF/jarjar/app.jar", new byte[]{1,2,3}));
        var discovered = NeoForgeDiscovery.discover(mods, new AuditLog());
        assertEquals(1, discovered.mods().size()); assertNotNull(discovered.mods().getFirst().archive().read("crash_assistant.mixins.json"));
        assertEquals(List.of(wrapper), discovered.libraries().stream().map(Archive::path).toList());
    }
    @Test void retainsSodiumRuntimeInsteadOfSuppressingItAsADuplicateRootMod() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path outer = wrapper(mods, "sodium", "sodium", "1", "[1,)", nested("1"));
        try (var jar = new java.util.jar.JarFile(outer.toFile())) {
            Map<String, byte[]> entries = new HashMap<>();
            for (var entry : java.util.Collections.list(jar.entries())) if (!entry.isDirectory()) try (var stream = jar.getInputStream(entry)) { entries.put(entry.getName(), stream.readAllBytes()); }
            entries.remove("META-INF/MANIFEST.MF");
            entries.put("META-INF/neoforge.mods.toml", descriptor("nested_mod", "1"));
            entries.put("META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator", TestJars.text("net.caffeinemc.mods.sodium.service.SodiumServiceModLocator"));
            TestJars.jar(root.resolve("rewritten.jar"), entries);
        }
        Files.move(root.resolve("rewritten.jar"), outer, StandardCopyOption.REPLACE_EXISTING);
        var discovered = NeoForgeDiscovery.discover(mods, new AuditLog());
        assertEquals(1, discovered.mods().size()); assertNotEquals(outer, discovered.mods().getFirst().archive().path());
        assertEquals(List.of(outer), discovered.libraries().stream().map(Archive::path).toList());
    }
    @Test void loadsOnlyLibrariesDeclaredByPackagedDependencies() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        byte[] child = Files.readAllBytes(TestJars.jar(root.resolve("library.jar"), Map.of("demo/Library.class", TestJars.type("demo.Library"))));
        TestJars.jar(mods.resolve("owner.jar"), Map.of("META-INF/neoforge.mods.toml", descriptor("native_mod", "1"),
                "META-INF/packageddependencies.json", TestJars.text("{\"paths\":[\"META-INF/packageddependencies/library.jar\"]}"),
                "META-INF/packageddependencies/library.jar", child, "META-INF/undeclared.jar", new byte[]{1,2,3}));
        var discovered = NeoForgeDiscovery.discover(mods, new AuditLog());
        assertEquals(1, discovered.mods().size()); assertEquals(1, discovered.libraries().size());
        assertNotNull(discovered.libraries().getFirst().read("demo/Library.class"));
    }
}
