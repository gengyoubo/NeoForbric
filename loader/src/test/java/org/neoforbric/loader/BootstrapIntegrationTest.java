package org.neoforbric.loader;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.bootstrap.*;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapIntegrationTest {
    @TempDir Path directory;

    @Test void threeIndependentJarsOperateOnOneObjectAndRestoreContextLoader() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Bootstrap bootstrap = new Bootstrap();
        Path report = directory.resolve("success.json");
        bootstrap.run(fixtureOptions(report));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        JsonObject audit = report(report);
        assertEquals("SUCCESS", audit.get("outcome").getAsString());
        assertEquals(List.of("probe_a", "probe_b", "probe_c"), subjects(bootstrap, "entrypoint-complete"));
        assertEquals(List.of("demo.game.GameMain"), subjects(bootstrap, "main-complete"));
        assertTrue(bootstrap.audit().events().stream().filter(e -> e.type().equals("class-defined"))
                .allMatch(e -> e.details().get("loader").equals("G")));
        assertEquals("LAUNCH_ALREADY_USED", assertThrows(Failure.class, () -> bootstrap.run(fixtureOptions(report))).code());
    }

    @Test void realJvmStartsBootstrapWithoutGameOrModJarsOnParentClasspath() throws Exception {
        Path report = directory.resolve("fork.json");
        Path output = directory.resolve("fork-output.txt");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("loader.runtimeClasspath"),
                "org.neoforbric.bootstrap.Main", "--fixture", "--game", System.getProperty("fixture.game"),
                "--mods", System.getProperty("fixture.mods"), "--main", "demo.game.GameMain", "--audit", report.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Fixture JVM did not terminate");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertTrue(Files.readString(output).contains("FIXTURE_OK mods=A,B,C sameGameLoader=true sharedValue=42"));
            assertEquals("SUCCESS", report(report).get("outcome").getAsString());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void failedEntrypointStopsLaterInitializationAndMainWithFatalAudit() throws Exception {
        String marker = "neoforbric.test." + UUID.randomUUID();
        Path mods = Files.createDirectory(directory.resolve("mods"));
        addMod(mods, "a", "demo.A", marker + ".a", false, Map.of());
        addMod(mods, "b", "demo.B", marker + ".b", true, Map.of("a", "1.0.0"));
        addMod(mods, "c", "demo.C", marker + ".c", false, Map.of("b", "1.0.0"));
        Path game = TestJars.jar(directory.resolve("game.jar"), Map.of("demo/Main.class", TestJars.main("demo.Main", marker + ".main")));
        Path report = directory.resolve("failure.json");
        Bootstrap bootstrap = new Bootstrap();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Failure failure = assertThrows(Failure.class, () -> bootstrap.run(options(game, mods, report)));
            assertEquals("LAUNCH_FAILED", failure.code());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            assertEquals("yes", System.getProperty(marker + ".a.complete"));
            assertEquals("initialized", System.getProperty(marker + ".b"));
            assertNull(System.getProperty(marker + ".c"));
            assertNull(System.getProperty(marker + ".main"));
            assertEquals(List.of("a", "b"), subjects(bootstrap, "entrypoint-start"));
            assertEquals("FAILED", report(report).get("outcome").getAsString());
            var event = bootstrap.audit().events().stream().filter(e -> e.type().equals("failure") && e.subject().equals("launch")).findFirst().orElseThrow();
            assertEquals("INSTANCE_FATAL", event.details().get("severity"));
            assertEquals("true", event.details().get("stateTainted"));
            assertEquals("restart", event.details().get("recovery"));
        } finally { clearMarkers(marker); }
    }

    @Test void allEntrypointShapesAreCheckedBeforeAnyModIsInitialized() throws Exception {
        String marker = "neoforbric.test." + UUID.randomUUID();
        Path mods = Files.createDirectory(directory.resolve("mods"));
        addMod(mods, "a", "demo.A", marker + ".a", false, Map.of());
        TestJars.jar(mods.resolve("b.jar"), Map.of("neoforbric.mod.json", TestJars.metadata("b", "1.0.0", "demo.Invalid", Map.of("a", "1.0.0"), "*"),
                "demo/Invalid.class", TestJars.type("demo.Invalid")));
        Path game = TestJars.jar(directory.resolve("game.jar"), Map.of("demo/Main.class", TestJars.main("demo.Main", marker + ".main")));
        Bootstrap bootstrap = new Bootstrap();
        try {
            assertEquals("ENTRYPOINT_TYPE", assertThrows(Failure.class, () -> bootstrap.run(options(game, mods, directory.resolve("preflight.json")))).code());
            assertNull(System.getProperty(marker + ".a"));
            assertNull(System.getProperty(marker + ".main"));
            assertTrue(subjects(bootstrap, "entrypoint-start").isEmpty());
        } finally { clearMarkers(marker); }
    }

    @Test void nativeRuntimeIsRejectedBeforeOpeningGameArchiveOrCreatingGameLoader() throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods"));
        TestJars.jar(mods.resolve("native.jar"), Map.of("fabric.mod.json", TestJars.text("{\"schemaVersion\":1,\"id\":\"native_mod\",\"version\":\"1.0.0\"}")));
        Path report = directory.resolve("native.json");
        Bootstrap bootstrap = new Bootstrap();
        assertEquals("NATIVE_RUNTIME_UNSUPPORTED", assertThrows(Failure.class,
                () -> bootstrap.run(options(directory.resolve("missing-game.jar"), mods, report))).code());
        assertTrue(subjects(bootstrap, "game-input").isEmpty());
        assertTrue(subjects(bootstrap, "loader-created").isEmpty());
        assertTrue(subjects(bootstrap, "class-defined").isEmpty());
        assertEquals("FAILED", report(report).get("outcome").getAsString());
    }

    @Test void inspectNativeIdentityDoesNotInvokeEntrypoints() throws Exception {
        Path mods = Files.createDirectory(directory.resolve("mods"));
        TestJars.jar(mods.resolve("native.jar"), Map.of("fabric.mod.json", TestJars.text("{\"id\":\"native_mod\",\"version\":\"1.0.0\",\"entrypoints\":{\"main\":[\"missing.Entry\"]}}")));
        Path report = directory.resolve("inspect.json");
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.run(new LaunchOptions(null, mods, null, "server", report, true, List.of()));
        assertEquals("INSPECTED", report(report).get("outcome").getAsString());
        assertEquals(List.of("native_mod"), subjects(bootstrap, "mod-discovered"));
        assertTrue(subjects(bootstrap, "class-defined").isEmpty());
    }

    @Test void cliPassesGameHelpAndOtherArgumentsWithoutTreatingThemAsLoaderOptions() throws Exception {
        String marker = "neoforbric.test." + UUID.randomUUID();
        Path game = TestJars.jar(directory.resolve("arguments.jar"), Map.of("demo/Main.class", TestJars.argumentMain("demo.Main", marker)));
        Path mods = Files.createDirectory(directory.resolve("mods"));
        try {
            assertEquals(0, Main.execute(new String[] {"--fixture", "--game", game.toString(), "--mods", mods.toString(),
                    "--main", "demo.Main", "--audit", directory.resolve("arguments.json").toString(), "--", "--help", "--side", "client"}));
            assertEquals("--help,--side,client", System.getProperty(marker));
        } finally { System.clearProperty(marker); }
    }

    @Test void invalidCliPathsAreUsageErrors() {
        assertEquals(2, Main.execute(new String[] {"--inspect", "--mods", "bad\u0000path"}));
    }

    private LaunchOptions fixtureOptions(Path report) {
        return new LaunchOptions(Path.of(System.getProperty("fixture.game")), Path.of(System.getProperty("fixture.mods")), "demo.game.GameMain", "server", report, false, List.of());
    }
    private LaunchOptions options(Path game, Path mods, Path report) {
        return new LaunchOptions(game, mods, "demo.Main", "server", report, false, List.of());
    }
    private void addMod(Path mods, String id, String name, String marker, boolean fail, Map<String, String> deps) throws Exception {
        TestJars.jar(mods.resolve(id + ".jar"), Map.of("neoforbric.mod.json", TestJars.metadata(id, "1.0.0", name, deps, "*"),
                name.replace('.', '/') + ".class", TestJars.initializer(name, marker, fail)));
    }
    private JsonObject report(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private List<String> subjects(Bootstrap bootstrap, String type) {
        return bootstrap.audit().events().stream().filter(e -> e.type().equals(type)).map(AuditLog.Event::subject).toList();
    }
    private void clearMarkers(String prefix) {
        System.getProperties().stringPropertyNames().stream().filter(k -> k.startsWith(prefix)).forEach(System::clearProperty);
    }
}
