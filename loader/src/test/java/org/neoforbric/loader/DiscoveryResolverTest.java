package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryResolverTest {
    @TempDir Path temporary;

    @Test void discoveryDoesNotExecuteClassInitializers() throws Exception {
        String marker = "neoforbric.test.discovery";
        System.clearProperty(marker);
        TestJars.jar(temporary.resolve("mod.jar"), Map.of("neoforbric.mod.json", TestJars.metadata("sample", "1.0.0", "demo.Sample", Map.of(), "*"),
                "demo/Sample.class", TestJars.initializer("demo.Sample", marker, false)));
        var found = Discovery.discover(temporary, new AuditLog());
        assertEquals("sample", found.getFirst().metadata().id());
        assertNull(System.getProperty(marker));
    }

    @Test void nativeIdentityIsDiscoverableButNeverSilentlyExecuted() throws Exception {
        Map<String, String> nativeMetadata = Map.of(
                "fabric.mod.json", "{\"id\":\"fabric_sample\",\"version\":\"1.0.0\",\"schemaVersion\":1}",
                "META-INF/mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[52,)\"\n[[mods]]\nmodId=\"forge_sample\"\nversion=\"1.0.0\"\n",
                "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n[[mods]]\nmodId=\"neo_sample\"\nversion=\"1.0.0\"\n");
        int index = 0;
        for (var entry : nativeMetadata.entrySet()) TestJars.jar(temporary.resolve("native" + index++ + ".jar"), Map.of(entry.getKey(), TestJars.text(entry.getValue())));
        var found = Discovery.discover(temporary, new AuditLog());
        assertEquals(Set.of(Metadata.Ecosystem.FABRIC, Metadata.Ecosystem.FORGE, Metadata.Ecosystem.NEOFORGE),
                new HashSet<>(found.stream().map(c -> c.metadata().ecosystem()).toList()));
        assertEquals("NATIVE_RUNTIME_UNSUPPORTED", assertThrows(Failure.class, () -> Resolver.resolve(found, "server", new AuditLog())).code());
    }

    @Test void malformedAndMissingMetadataIsRejected() throws Exception {
        for (String json : List.of("{\"id\":\"first\",\"id\":\"second\"}", "{ /* comment */ \"id\":\"first\" }", "{} {}", "[]")) {
            var archive = Archive.read(TestJars.jar(temporary.resolve("invalid.jar"), Map.of("neoforbric.mod.json", TestJars.text(json))));
            assertThrows(Failure.class, () -> Metadata.read(archive), json);
        }
        var missing = Archive.read(TestJars.jar(temporary.resolve("missing.jar"), Map.of("pack.mcmeta", TestJars.text("{}"))));
        assertEquals("METADATA_DESCRIPTOR", assertThrows(Failure.class, () -> Metadata.read(missing)).code());
    }

    @Test void universalJarUsesFabricOnceAndRecordsItsDescriptor() throws Exception {
        TestJars.jar(temporary.resolve("universal.jar"), Map.of(
                "fabric.mod.json", TestJars.text("{\"schemaVersion\":1,\"id\":\"explorify\",\"version\":\"1.6.5\"}"),
                "META-INF/mods.toml", TestJars.text("[[mods]]\nmodId=\"explorify\"\nversion=\"1.6.5\"\n"),
                "META-INF/neoforge.mods.toml", TestJars.text("[[mods]]\nmodId=\"explorify\"\nversion=\"1.6.5\"\n")));
        AuditLog audit = new AuditLog();
        var found = Discovery.discover(temporary, audit);
        assertEquals(1, found.size());
        assertEquals("explorify", found.getFirst().metadata().id());
        assertEquals(Metadata.Ecosystem.FABRIC, found.getFirst().metadata().ecosystem());
        assertTrue(audit.events().stream().anyMatch(event -> event.type().equals("mod-discovered")
                && "fabric.mod.json".equals(event.details().get("descriptor"))));
    }

    @Test void descriptorPriorityIsPrototypeThenFabricThenNeoForgeThenForge() throws Exception {
        Map<String, byte[]> descriptors = new HashMap<>(Map.of(
                "neoforbric.mod.json", TestJars.metadata("prototype_sample", "1.0.0", "demo.Sample", Map.of(), "*"),
                "fabric.mod.json", TestJars.text("{\"id\":\"fabric_sample\",\"version\":\"1.0.0\"}"),
                "META-INF/neoforge.mods.toml", TestJars.text("[[mods]]\nmodId=\"neo_sample\"\nversion=\"1.0.0\"\n"),
                "META-INF/mods.toml", TestJars.text("[[mods]]\nmodId=\"forge_sample\"\nversion=\"1.0.0\"\n")));
        List<String> priority = List.of("neoforbric.mod.json", "fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml");
        List<Metadata.Ecosystem> ecosystems = List.of(Metadata.Ecosystem.PROTOTYPE, Metadata.Ecosystem.FABRIC, Metadata.Ecosystem.NEOFORGE, Metadata.Ecosystem.FORGE);
        for (int index = 0; index < priority.size(); index++) {
            Archive archive = Archive.read(TestJars.jar(temporary.resolve("priority.jar"), descriptors));
            assertEquals(priority.get(index), Metadata.descriptor(archive));
            assertEquals(ecosystems.get(index), Metadata.read(archive).getFirst().ecosystem());
            descriptors.remove(priority.get(index));
        }
    }

    @Test void invalidPreferredDescriptorDoesNotFallBackToValidLowerPriorityMetadata() throws Exception {
        Archive archive = Archive.read(TestJars.jar(temporary.resolve("invalid-preferred.jar"), Map.of(
                "fabric.mod.json", TestJars.text("{}"),
                "META-INF/mods.toml", TestJars.text("[[mods]]\nmodId=\"forge_sample\"\nversion=\"1.0.0\"\n"))));
        assertEquals("fabric.mod.json", Metadata.descriptor(archive));
        assertEquals("METADATA_INVALID", assertThrows(Failure.class, () -> Metadata.read(archive)).code());
    }

    @Test void dependencyOrderDoesNotDependOnDiscoveryOrder() throws Exception {
        var a = TestJars.candidate(temporary.resolve("z.jar"), "a", Map.of());
        var b = TestJars.candidate(temporary.resolve("b.jar"), "b", Map.of("a", ">=1.0.0 <2.0.0"));
        var c = TestJars.candidate(temporary.resolve("a.jar"), "c", Map.of("b", "1.0.0"));
        assertEquals(List.of("a", "b", "c"), Resolver.resolve(List.of(c, b, a), "server", new AuditLog()).stream().map(m -> m.metadata().id()).toList());
    }

    @Test void missingMismatchDuplicateAndCyclesGiveDistinctFailures() throws Exception {
        var missing = TestJars.candidate(temporary.resolve("missing.jar"), "missing", Map.of("absent", "*"));
        assertEquals("MISSING_DEPENDENCY", assertThrows(Failure.class, () -> Resolver.resolve(List.of(missing), "server", new AuditLog())).code());
        var mismatch = TestJars.candidate(temporary.resolve("mismatch.jar"), "mismatch", Map.of("minecraft", ">=1.22.0"));
        assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class, () -> Resolver.resolve(List.of(mismatch), "server", new AuditLog())).code());
        var a = TestJars.candidate(temporary.resolve("a.jar"), "a", Map.of("b", "*"));
        var b = TestJars.candidate(temporary.resolve("b.jar"), "b", Map.of("a", "*"));
        assertEquals("ORDER_CYCLE", assertThrows(Failure.class, () -> Resolver.resolve(List.of(a, b), "server", new AuditLog())).code());
        assertEquals("DUPLICATE_MOD_ID", assertThrows(Failure.class, () -> Resolver.resolve(List.of(a, a), "server", new AuditLog())).code());
    }

    @Test void sideExclusionDoesNotSatisfyRequiredDependencies() throws Exception {
        var client = TestJars.candidate(temporary.resolve("client.jar"), "client_only", "1.0.0", Map.of(), "client");
        var server = TestJars.candidate(temporary.resolve("server.jar"), "needs_client", Map.of("client_only", "*"));
        assertTrue(Resolver.resolve(List.of(client), "server", new AuditLog()).isEmpty());
        assertEquals("MISSING_DEPENDENCY", assertThrows(Failure.class, () -> Resolver.resolve(List.of(client, server), "server", new AuditLog())).code());
    }

    @Test void prereleaseOrderingAndUnsupportedNativeRangeSyntaxStayExplicit() {
        assertTrue(Version.matches(">1.0.0-rc.9 <2.0.0", "1.0.0"));
        assertTrue(Version.parse("1.0.0-rc.10").compareTo(Version.parse("1.0.0-rc.9")) > 0);
        assertEquals(0, Version.parse("1.0.0+build.1").compareTo(Version.parse("1.0.0+build.2")));
        assertThrows(Failure.class, () -> Version.matches("[1.0,2.0)", "1.0.0"));
        assertThrows(Failure.class, () -> Version.parse("1.0.0-01"));
    }
}
