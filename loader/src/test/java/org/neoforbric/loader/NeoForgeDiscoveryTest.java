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
    @Test void auditsSelectedDescriptorsForBothNativeAndFabricRoots() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        byte[] fabric = TestJars.text("{\"schemaVersion\":1,\"id\":\"fabric_mod\",\"version\":\"1\"}");
        TestJars.jar(mods.resolve("fabric.jar"), Map.of("fabric.mod.json", fabric));
        TestJars.jar(mods.resolve("universal.jar"), Map.of("META-INF/neoforge.mods.toml", descriptor("native_mod", "1"), "fabric.mod.json", fabric));
        AuditLog audit = new AuditLog();
        var discovered = NeoForgeDiscovery.discover(mods, audit);
        assertEquals(List.of(Metadata.Ecosystem.FABRIC, Metadata.Ecosystem.NEOFORGE), discovered.mods().stream().map(mod -> mod.metadata().ecosystem()).toList());
        var events = audit.events().stream().filter(event -> event.type().equals("mod-discovered")).toList();
        assertEquals(List.of("fabric_mod", "native_mod"), events.stream().map(AuditLog.Event::subject).toList());
        assertEquals(List.of("fabric.mod.json", "META-INF/neoforge.mods.toml"), events.stream().map(event -> event.details().get("descriptor")).toList());
        assertEquals(List.of("FABRIC", "NEOFORGE"), events.stream().map(event -> event.details().get("ecosystem")).toList());
    }
    @Test void nestedFabricModsKeepTheirEntrypointsInsteadOfBecomingPassiveLibraries() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        byte[] child = Files.readAllBytes(TestJars.jar(root.resolve("fabric-child.jar"), Map.of("fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"fabric_child","version":"1","entrypoints":{"main":["demo.RegisterContent"],"client":["demo.RegisterModels"]}}
                """))));
        wrapper(mods, "owner", "fabric-child", "1", "[1,)", child);
        AuditLog audit = new AuditLog();
        var discovered = NeoForgeDiscovery.discover(mods, audit);
        assertEquals(1, discovered.mods().size()); assertEquals(1, discovered.libraries().size());
        var candidate = discovered.mods().getFirst();
        assertEquals(Metadata.Ecosystem.FABRIC, candidate.metadata().ecosystem());
        assertEquals(List.of("main", "client"), org.neoforbric.minecraft.FabricAdmission.entries(candidate, "client").stream().map(entry -> entry.group()).toList());
        assertTrue(audit.events().stream().anyMatch(event -> event.subject().equals("fabric_child") && "fabric.mod.json".equals(event.details().get("descriptor"))));
    }
    @Test void explicitFabricLibraryDoesNotExecuteModEntrypoints() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("FMLModType", "LIBRARY");
        byte[] child = Files.readAllBytes(TestJars.jar(root.resolve("fabric-library.jar"), Map.of("fabric.mod.json",
                TestJars.text("{\"schemaVersion\":1,\"id\":\"fabric_library\",\"version\":\"1\",\"entrypoints\":{\"main\":[\"demo.Library\"]}}")), manifest));
        wrapper(mods, "owner", "fabric-library", "1", "[1,)", child);
        var discovered = NeoForgeDiscovery.discover(mods, new AuditLog());
        assertTrue(discovered.mods().isEmpty()); assertEquals(2, discovered.libraries().size());
    }
    @Test void aRootFabricModProvidesTheNestedCopyWithoutDuplicateInitialization() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        byte[] fabric = TestJars.text("{\"schemaVersion\":1,\"id\":\"fabric_child\",\"version\":\"1\"}");
        Path provided = TestJars.jar(mods.resolve("provided.jar"), Map.of("fabric.mod.json", fabric));
        byte[] child = Files.readAllBytes(TestJars.jar(root.resolve("child.jar"), Map.of("fabric.mod.json", fabric, "nested.txt", new byte[]{1})));
        wrapper(mods, "owner", "fabric-child", "1", "[1,)", child);
        var discovered = NeoForgeDiscovery.discover(mods, new AuditLog());
        assertEquals(List.of(provided), discovered.mods().stream().map(mod -> mod.archive().path()).toList());
    }
    @Test void nestedFabricAliasesRespectAllDeclaredVersionRanges() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        for (String version : List.of("1", "2")) {
            byte[] child = Files.readAllBytes(TestJars.jar(root.resolve("fabric-" + version + ".jar"), Map.of("fabric.mod.json",
                    TestJars.text("{\"schemaVersion\":1,\"id\":\"fabric_child\",\"version\":\"" + version + "\"}"))));
            wrapper(mods, "owner-" + version, "alias-" + version, version, version.equals("1") ? "[1,2)" : "[2,)", child);
        }
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
