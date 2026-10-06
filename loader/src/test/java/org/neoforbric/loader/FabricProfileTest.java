package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.bootstrap.*;
import org.neoforbric.minecraft.FabricAdmission;
import static org.junit.jupiter.api.Assertions.*;

class FabricProfileTest {
    @TempDir Path temporary;
    private Discovery.Candidate candidate(String json) throws Exception {
        Archive archive = Archive.read(TestJars.jar(temporary.resolve("native.jar"), Map.of("fabric.mod.json", TestJars.text(json))));
        return new Discovery.Candidate(archive, Metadata.read(archive).getFirst());
    }
    @Test void diagnosticsCollectEveryBlockerAndKeepDependencyRequirementsSeparate() throws Exception {
        var mod = candidate("""
                {"schemaVersion":1,"id":"diagnostic_probe","version":"1.0.0",
                 "mixins":["probe.mixins.json",{"config":"probe.client.mixins.json","environment":"client"}],
                 "accessWidener":"probe.aw","jars":[{"file":"META-INF/jars/library.jar"}],
                 "languageAdapters":{"kotlin":"demo.Adapter"},"breaks":{"other_mod":"*"},"conflicts":{"another_mod":"*"},
                 "recommends":{"recommended_mod":"*"},"suggests":{"suggested_mod":"*"},
                 "depends":{"fabric-api":">=0.102.0","alternative_mod":["1.0.0","2.0.0"]},
                 "entrypoints":{"client":["demo.Client::create",{"adapter":"kotlin","value":"demo.Client"}],"jei_mod_plugin":["demo.Plugin"]}}
                """);
        Failure failure = assertThrows(Failure.class, () -> FabricAdmission.admit(mod));
        assertEquals("FABRIC_FEATURE_UNSUPPORTED", failure.code());
        var blockers = failure.diagnostics().stream().filter(d -> d.kind() == org.neoforbric.api.ModDiagnostic.Kind.UNSUPPORTED_FEATURE).toList();
        assertEquals(13, blockers.size());
        for (String value : List.of("probe.mixins.json", "probe.client.mixins.json", "probe.aw", "META-INF/jars/library.jar", "jei_mod_plugin", "demo.Client::create", "demo.Adapter"))
            assertTrue(failure.getMessage().contains(value), failure.getMessage());
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.kind() == org.neoforbric.api.ModDiagnostic.Kind.REQUIRED_DEPENDENCY && d.subject().equals("fabric-api") && d.value().equals(">=0.102.0")));
        assertTrue(failure.getMessage().contains("Required dependencies (not evaluated)"));
        assertThrows(UnsupportedOperationException.class, () -> failure.diagnostics().clear());
        try (var catalog = new ModCatalog(List.of(mod), new AuditLog())) {
            assertTrue(catalog.selectClient(List.of(mod)).isEmpty());
            var info = org.neoforbric.api.LoadedMods.snapshot().get(1);
            assertEquals(org.neoforbric.api.LoadStatus.UNSUPPORTED, info.status());
            assertEquals(failure.diagnostics(), info.diagnostics());
        }
    }
    @Test void realFabricVersionPredicatesAndEntryGroupsAreUsed() throws Exception {
        var mod = FabricAdmission.admit(candidate("""
                {"schemaVersion":1,"id":"native_sample","version":"1.0.0","depends":{"java":">=21","minecraft":"~1.21.1","fabricloader":">=0.16.10"},
                 "entrypoints":{"server":["demo.Server"],"client":["demo.Client"],"main":[{"adapter":"default","value":"demo.Main"}]}}
                """));
        assertEquals(List.of("main", "server"), FabricAdmission.entries(mod).stream().map(FabricAdmission.Entry::group).toList());
        assertEquals(List.of("main", "client"), FabricAdmission.entries(mod, "client").stream().map(FabricAdmission.Entry::group).toList());
        assertEquals(List.of("native_sample"), Resolver.resolve(List.of(mod), "server", new AuditLog(), true).stream().map(m -> m.metadata().id()).toList());
        assertTrue(FabricAdmission.matches(">=21", "21.0.0"));
        assertFalse(FabricAdmission.matches("~1.21.1", "1.22.0"));
        assertEquals("NATIVE_RUNTIME_UNSUPPORTED", assertThrows(Failure.class, () -> Resolver.resolve(List.of(mod), "server", new AuditLog())).code());
    }
    @Test void loaderVersionFloorUsesTheUpgradedSpiAndStillRejectsFutureVersions() throws Exception {
        for (String floor : List.of(">=0.17", ">=0.19.5", ">=0.20")) {
            var mod = FabricAdmission.admit(candidate("""
                    {"schemaVersion":1,"id":"loader_floor","version":"1.0.0","depends":{"fabricloader":"%s"}}
                    """.formatted(floor)));
            if (floor.equals(">=0.20")) {
                assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class,
                        () -> Resolver.resolve(List.of(mod), "client", new AuditLog(), true)).code());
            } else {
                assertEquals(List.of(mod), Resolver.resolve(List.of(mod), "client", new AuditLog(), true));
            }
        }
    }
    @Test void unsupportedFabricFeaturesFailBeforeOpeningTheGamePlan() throws Exception {
        for (String feature : List.of("\"mixins\":[\"sample.mixins.json\"]", "\"accessWidener\":\"sample.aw\"", "\"jars\":[{\"file\":\"nested.jar\"}]",
                "\"languageAdapters\":{\"kotlin\":\"demo.KotlinAdapter\"}")) {
            candidate("{\"schemaVersion\":1,\"id\":\"native_sample\",\"version\":\"1.0.0\"," + feature + "}");
            Bootstrap bootstrap = new Bootstrap();
            LaunchOptions options = new LaunchOptions(null, temporary, "net.minecraft.server.Main", "server", temporary.resolve("audit.json"),
                    false, List.of("--initSettings"), temporary.resolve("missing-runtime.json"), null);
            assertEquals("FABRIC_FEATURE_UNSUPPORTED", assertThrows(Failure.class, () -> bootstrap.run(options)).code());
            assertFalse(bootstrap.audit().events().stream().anyMatch(e -> e.type().equals("game-input") || e.type().equals("class-defined")));
        }
    }
    @Test void settingsProfileOwnsMainAndRejectsUnmanagedPersistentServerLaunch() {
        var options = LaunchOptions.parse(new String[]{"--minecraft-server", "--runtime", "runtime.json", "--mods", "mods"});
        assertTrue(options.minecraft()); assertEquals("net.minecraft.server.Main", options.mainClass()); assertEquals(List.of("--initSettings"), options.gameArguments());
        assertEquals("ARGUMENTS", assertThrows(Failure.class, () -> LaunchOptions.parse(new String[]{"--minecraft-server", "--runtime", "runtime.json", "--mods", "mods", "--", "nogui"})).code());
        assertEquals("ARGUMENTS", assertThrows(Failure.class, () -> LaunchOptions.parse(new String[]{"--minecraft-server", "--runtime", "runtime.json", "--mods", "mods", "--main", "other.Main"})).code());
    }
    @Test void persistentServerRequiresExplicitModeAndValidProbeBounds() {
        var options = LaunchOptions.parse(new String[]{"--minecraft-server", "--run-server", "--runtime", "runtime.json", "--mods", "mods", "--stop-after-ticks", "5"});
        assertTrue(options.runServer()); assertEquals(5, options.stopAfterTicks()); assertEquals(List.of("nogui"), options.gameArguments());
        assertEquals("ARGUMENTS", assertThrows(Failure.class, () -> LaunchOptions.parse(new String[]{"--minecraft-server", "--run-server", "--runtime", "runtime.json", "--mods", "mods", "--", "--initSettings"})).code());
        for (String value : List.of("0", "20001", "invalid")) {
            assertEquals("ARGUMENTS", assertThrows(Failure.class, () -> LaunchOptions.parse(new String[]{"--minecraft-server", "--run-server", "--runtime", "runtime.json", "--mods", "mods", "--stop-after-ticks", value})).code());
        }
    }
    @Test void clientOwnsItsMainSideAndFrameProbe() {
        var options = LaunchOptions.parse(new String[]{"--minecraft-client", "--runtime", "runtime.json", "--mods", "mods", "--stop-after-frames", "5"});
        assertTrue(options.client()); assertFalse(options.runServer()); assertEquals("client", options.side());
        assertEquals("net.minecraft.client.main.Main", options.mainClass()); assertEquals(5, options.stopAfterFrames());
        for (String[] bad : List.of(new String[]{"--run-server"}, new String[]{"--side", "server"}, new String[]{"--stop-after-frames", "0"}, new String[]{"--stop-after-ticks", "5"})) {
            List<String> args = new ArrayList<>(List.of("--minecraft-client", "--runtime", "runtime.json", "--mods", "mods")); args.addAll(List.of(bad));
            assertEquals("ARGUMENTS", assertThrows(Failure.class, () -> LaunchOptions.parse(args.toArray(String[]::new))).code());
        }
    }
}
