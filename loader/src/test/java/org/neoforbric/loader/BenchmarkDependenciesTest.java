package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkDependenciesTest {
    @TempDir Path directory;
    private Path fabric(String filename, String id, String version, String extra) throws Exception {
        return TestJars.jar(directory.resolve(filename), Map.of("fabric.mod.json", TestJars.text(
                "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"" + extra + "}")));
    }
    private Map<String, Object> resolve(List<Path> roots, Path... pool) throws Exception {
        return new BenchmarkDependencies(directory.resolve("scratch")).resolve(roots, List.of(pool), "fabric");
    }
    @SuppressWarnings("unchecked") private List<String> files(Map<String, Object> result) {
        return ((List<Map<String, Object>>)result.get("inputs")).stream().map(item -> Path.of((String)item.get("path")).getFileName().toString()).toList();
    }

    @Test void expandsTransitiveRequiredDependenciesAndIgnoresOptionalAbsentMods() throws Exception {
        Path target = fabric("target.jar", "target", "1.0.0", ",\"depends\":{\"core\":\">=2\"},\"recommends\":{\"optional\":\"*\"}");
        Path core = fabric("core.jar", "core", "2.1.0", ",\"depends\":{\"library\":\"~1.2\"}");
        Path library = fabric("library.jar", "library", "1.2.3", ",\"depends\":{\"minecraft\":\"1.21.1\",\"java\":\">=21\",\"fabricloader\":\">=0.19\"}");
        Path unrelated = fabric("unrelated.jar", "unrelated", "1.0.0", "");
        var result = resolve(List.of(target), core, library, unrelated);
        assertEquals("READY", result.get("status"));
        assertEquals(List.of("target.jar", "core.jar", "library.jar"), files(result));
    }

    @Test void missingDependencyIsAnInputErrorWithTheTransitiveOwner() throws Exception {
        Path target = fabric("target.jar", "target", "1.0.0", ",\"depends\":{\"core\":\"*\"}");
        Path core = fabric("core.jar", "core", "1.0.0", ",\"depends\":{\"missing_library\":\">=2\"}");
        var result = resolve(List.of(target), core);
        assertEquals("INPUT_ERROR", result.get("status"));
        assertEquals("INPUT_DEPENDENCY", result.get("failure_code"));
        assertTrue(result.get("reason").toString().contains("core -> missing_library"));
    }

    @Test void backtracksVersionsToSatisfyTheWholeClosure() throws Exception {
        Path target = fabric("target.jar", "target", "1.0.0", ",\"depends\":{\"core\":\">=1 <3\",\"library\":\"1.x\"}");
        Path newest = fabric("core2.jar", "core", "2.0.0", ",\"depends\":{\"library\":\"2.x\"}");
        Path older = fabric("core1.jar", "core", "1.0.0", ",\"depends\":{\"library\":\"1.x\"}");
        Path library1 = fabric("library1.jar", "library", "1.0.0", "");
        Path library2 = fabric("library2.jar", "library", "2.0.0", "");
        var result = resolve(List.of(target), newest, older, library1, library2);
        assertEquals("READY", result.get("status"));
        assertEquals(List.of("target.jar", "core1.jar", "library1.jar"), files(result));
    }

    @Test void explicitVersionCannotBeSilentlyReplaced() throws Exception {
        Path target = fabric("target.jar", "target", "1.0.0", ",\"depends\":{\"core\":\"2.x\"}");
        Path explicit = fabric("core1.jar", "core", "1.0.0", "");
        Path pool = fabric("core2.jar", "core", "2.0.0", "");
        assertEquals("INPUT_ERROR", resolve(List.of(target, explicit), pool).get("status"));
    }

    @Test void choosesTheMatchingEcosystemBeforeAHigherVersionFromAnotherPlatform() throws Exception {
        Path target = fabric("target.jar", "target", "1.0.0", ",\"depends\":{\"core\":\">=1\"}");
        Path fabric = fabric("fabric-core.jar", "core", "1.0.0", "");
        Path nativeCore = TestJars.jar(directory.resolve("neo-core.jar"), Map.of("META-INF/neoforge.mods.toml", TestJars.text("""
                modLoader="javafml"
                loaderVersion="[4,)"
                license="MIT"
                [[mods]]
                modId="core"
                version="2.0.0"
                """)));
        assertEquals(List.of("target.jar", "fabric-core.jar"), files(resolve(List.of(target), nativeCore, fabric)));
        var mixed = new BenchmarkDependencies(directory.resolve("scratch")).resolve(List.of(target), List.of(nativeCore, fabric), "neoforge");
        assertEquals(List.of("target.jar", "fabric-core.jar"), files(mixed));
    }

    @Test void fabricArrayRangesAliasesAndBundledJarsAreRecognized() throws Exception {
        Path dependency = fabric("dependency.jar", "real_core", "2.0.0", ",\"provides\":[\"core_alias\"]");
        byte[] nested = Files.readAllBytes(dependency);
        Path target = TestJars.jar(directory.resolve("target.jar"), Map.of("fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"target","version":"1.0.0","depends":{"core_alias":["1.x","2.x"]},"jars":[{"file":"META-INF/jars/core.jar"}]}
                """), "META-INF/jars/core.jar", nested));
        var result = resolve(List.of(target));
        assertEquals("READY", result.get("status"));
        assertEquals(List.of("target.jar"), files(result));
    }

    @Test void handlesCyclesWithoutRecursingForeverAndRejectsBreaks() throws Exception {
        Path a = fabric("aa.jar", "aa", "1.0.0", ",\"depends\":{\"bb\":\"*\"}");
        Path b = fabric("bb.jar", "bb", "1.0.0", ",\"depends\":{\"aa\":\"*\"}");
        assertEquals("READY", resolve(List.of(a), b).get("status"));
        Path broken = fabric("broken.jar", "broken", "1.0.0", ",\"depends\":{\"bb\":\"*\"},\"breaks\":{\"bb\":\"*\"}");
        assertEquals("INPUT_ERROR", resolve(List.of(broken), b, a).get("status"));
    }

    @Test void nativeRequiredClientDependenciesUseMavenRangesAndIgnoreServerOnly() throws Exception {
        Path target = TestJars.jar(directory.resolve("target.jar"), Map.of("META-INF/neoforge.mods.toml", TestJars.text("""
                modLoader="javafml"
                loaderVersion="[4,)"
                license="MIT"
                [[mods]]
                modId="target"
                version="1.0.0"
                [[dependencies.target]]
                modId="core"
                type="required"
                versionRange="[2,3)"
                side="CLIENT"
                [[dependencies.target]]
                modId="server_only"
                type="required"
                versionRange="[1,)"
                side="SERVER"
                [[dependencies.target]]
                modId="optional"
                type="optional"
                versionRange="[1,)"
                """)));
        Path core = TestJars.jar(directory.resolve("core.jar"), Map.of("META-INF/neoforge.mods.toml", TestJars.text("""
                modLoader="javafml"
                loaderVersion="[4,)"
                license="MIT"
                [[mods]]
                modId="core"
                version="2.1.0"
                """)));
        var result = new BenchmarkDependencies(directory.resolve("scratch")).resolve(List.of(target), List.of(core), "neoforge");
        assertEquals("READY", result.get("status"));
        assertEquals(List.of("target.jar", "core.jar"), files(result));
    }
}
