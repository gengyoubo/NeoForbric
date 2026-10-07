package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.neoforge.NeoForgeMetadata;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgeMetadataTest {
    @TempDir Path root;
    private Discovery.Candidate mod(String id, String version, String tail) throws Exception {
        Archive archive = Archive.read(TestJars.jar(root.resolve(id + ".jar"), Map.of("META-INF/neoforge.mods.toml", TestJars.text(
                "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"Test\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n" + tail))));
        return new Discovery.Candidate(archive, Metadata.read(archive).getFirst());
    }
    private String dependency(String owner, String target, String type, String range, String order, String side) {
        return "[[dependencies." + owner + "]]\nmodId=\"" + target + "\"\ntype=\"" + type + "\"\nversionRange=\"" + range + "\"\nordering=\"" + order + "\"\nside=\"" + side + "\"\n";
    }
    @Test void readsWholeFileAndKeepsMixinAndExplicitMultipleAccessRules() throws Exception {
        var candidate = mod("sample", "1.0", "displayName=\"Sample\"\n[[mods]]\nmodId=\"second\"\nversion=\"${file.release}\"\n[properties]\nrelease=\"2.1\"\n[[mixins]]\nconfig=\"sample.mixins.json\"\n[[accessTransformers]]\nfile=\"one.cfg\"\n[[accessTransformers]]\nfile=\"two.cfg\"\n");
        var file = NeoForgeMetadata.read(candidate.archive());
        assertEquals("javafml", file.modLoader()); assertEquals("[4,)", file.loaderVersion()); assertEquals("Test", file.license());
        assertEquals(List.of("1.0", "2.1"), file.mods().stream().map(Metadata::version).toList());
        assertEquals(List.of("sample.mixins.json"), file.mixins()); assertEquals(List.of("one.cfg", "two.cfg"), file.accessTransformers());
        assertThrows(UnsupportedOperationException.class, () -> file.fields().put("license", "Changed"));
    }
    @Test void nativeProvidesAreExplicitAndFabricVersionsStillConstrainNativeDependencies() throws Exception {
        var provided = mod("fabric_api_base", "0.4.42", "provides=[\"fabric-api-base\"]\n");
        assertEquals(List.of("fabric-api-base"), NeoForgeMetadata.read(provided.archive()).provides("fabric_api_base"));
        assertTrue(NeoForgeMetadata.read(provided.archive()).provides("other").isEmpty());
        assertEquals("METADATA_INVALID", assertThrows(Failure.class, () -> mod("invalid", "1.0", "provides=[42]\n")).code());
        var dependent = mod("native_sample", "1.0", dependency("native_sample", "fabric_feature", "required", "[1,2)", "AFTER", "BOTH"));
        assertEquals(List.of(dependent), Resolver.plan(List.of(dependent), "client", new AuditLog(), false, true, false, Map.of("fabric_feature", "1.5")).mods());
        assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class, () -> Resolver.plan(List.of(dependent), "client", new AuditLog(), false, true, false, Map.of("fabric_feature", "2.0"))).code());
        assertEquals("MISSING_DEPENDENCY", assertThrows(Failure.class, () -> Resolver.plan(List.of(dependent), "client", new AuditLog(), false, true)).code());
    }
    @Test void presenceDoesNotImplicitlyCreateOrderingButBeforeAndAfterDo() throws Exception {
        var a = mod("aa", "1.0", dependency("aa", "bb", "required", "[1,2)", "NONE", "BOTH"));
        var b = mod("bb", "1.5", "");
        var plan = Resolver.plan(List.of(b, a), "client", new AuditLog(), false, true);
        assertEquals(List.of("aa", "bb"), plan.mods().stream().map(c -> c.metadata().id()).toList()); assertTrue(plan.predecessors().get("aa").isEmpty());
        a = mod("aa", "1.0", dependency("aa", "bb", "required", "[1,2)", "AFTER", "BOTH"));
        assertEquals(List.of("bb", "aa"), Resolver.resolve(List.of(a,b), "client", new AuditLog(), false,true).stream().map(c -> c.metadata().id()).toList());
        b = mod("bb", "1.5", dependency("bb", "aa", "required", "", "AFTER", "BOTH"));
        var cycle = List.of(a,b); assertEquals("ORDER_CYCLE", assertThrows(Failure.class, () -> Resolver.resolve(cycle,"client",new AuditLog(),false,true)).code());
    }
    @Test void validatesOptionalIncompatibleDiscouragedSidesAndBuiltinVersions() throws Exception {
        var b = mod("bb", "1.5", "");
        var optional = mod("aa", "1.0", dependency("aa", "bb", "optional", "[2,)", "NONE", "BOTH"));
        assertEquals(1, Resolver.resolve(List.of(optional),"client",new AuditLog(),false,true).size());
        assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class, () -> Resolver.resolve(List.of(optional,b),"client",new AuditLog(),false,true)).code());
        var incompatible = mod("aa", "1.0", dependency("aa", "bb", "incompatible", "[1,2)", "NONE", "BOTH"));
        assertEquals("INCOMPATIBLE_DEPENDENCY", assertThrows(Failure.class, () -> Resolver.resolve(List.of(incompatible,b),"client",new AuditLog(),false,true)).code());
        var client = mod("aa", "1.0", dependency("aa", "missing", "required", "", "NONE", "SERVER"));
        assertEquals(1, Resolver.resolve(List.of(client),"client",new AuditLog(),false,true).size());
        var warning = mod("aa", "1.0", dependency("aa", "bb", "discouraged", "[1,2)", "NONE", "BOTH"));
        AuditLog audit = new AuditLog(); Resolver.resolve(List.of(warning,b),"client",audit,false,true);
        assertTrue(audit.events().stream().anyMatch(event -> event.type().equals("dependency-warning")));
        var builtin = mod("aa", "1.0", dependency("aa", "neoforge", "required", "[21.1.249,)", "NONE", "BOTH"));
        assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class, () -> Resolver.resolve(List.of(builtin),"client",new AuditLog(),false,true)).code());
    }
    @Test void mavenRangesIncludeUnionsBoundsAndReleaseQualifiers() {
        assertTrue(MavenVersions.matches("(,1.0],[1.2,)", "1.3")); assertFalse(MavenVersions.matches("(,1.0],[1.2,)", "1.1"));
        assertTrue(MavenVersions.matches("[1.0]", "1.0.0")); assertFalse(MavenVersions.matches("[1.0,)", "1.0-rc1"));
        assertThrows(Failure.class, () -> MavenVersions.matches("[2,1)", "0"));
    }
    @Test void readsUnscopedDependenciesForSingleModButRejectsAmbiguousOwners() throws Exception {
        var single = mod("arseng", "2.1.1-beta", "[[dependencies]]\nmodId=\"ae2\"\nversionRange=\"[19,)\"\n");
        assertEquals("ae2", single.metadata().constraints().getFirst().id());
        assertEquals(Dependency.Kind.REQUIRED, single.metadata().constraints().getFirst().kind());
        assertThrows(Failure.class, () -> mod("ambiguous", "1", "[[mods]]\nmodId=\"second\"\n[[dependencies]]\nmodId=\"ae2\"\n"));
    }
    @Test void appliesExplicitPackDependencyOverridesAndBuiltinCompatibilityVersions() throws Exception {
        var a = mod("aa", "1", dependency("aa", "missing", "required", "[1,)", "NONE", "BOTH")
                + dependency("aa", "minecraft", "required", "[1.21,1.21.1)", "NONE", "BOTH"));
        var b = mod("bb", "1", "");
        Files.createDirectories(root.resolve("config")); Files.writeString(root.resolve("config/fml.toml"), "[dependencyOverrides]\naa=[\"-missing\",\"+bb\"]\n");
        var plan = org.neoforbric.neoforge.NeoForgeOverrides.apply(List.of(a,b), root, new AuditLog());
        assertEquals(List.of("bb", "aa"), Resolver.resolve(plan, "client", new AuditLog(), false, true).stream().map(mod -> mod.metadata().id()).toList());
        assertEquals(2, a.metadata().constraints().size());
    }
}
