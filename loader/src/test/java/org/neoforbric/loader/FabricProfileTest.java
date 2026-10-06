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
