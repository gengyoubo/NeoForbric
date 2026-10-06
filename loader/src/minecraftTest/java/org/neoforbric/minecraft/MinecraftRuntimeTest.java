package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftRuntimeTest {
    @TempDir Path temporary;
    private record Launch(int status, String output, JsonObject audit, Path working) {}

    @Test void intermediaryFabricJarRegistersRealItemAndKeepsHolderAndStackIdentity() throws Exception {
        Launch launch = launch(false, Path.of(System.getProperty("minecraft.runtime")));
        assertEquals(0, launch.status(), launch.output());
        assertTrue(launch.output().contains("MINECRAFT_PROBE_OK version=1.21.1"), launch.output());
        assertTrue(launch.output().contains("holderIdentity=true keyIdentity=true frozen=true lateRejected=true libraryIsolation=true gameLoader=NeoForbric-Game"));
        assertEquals("SUCCESS", launch.audit().get("outcome").getAsString());
        assertEquals("minecraft-1.21.1-server-settings", launch.audit().get("mode").getAsString());
        var events = events(launch);
        assertEquals(1, events.stream().filter(e -> e.get("type").getAsString().equals("main-start")).count());
        assertEquals(1, events.stream().filter(e -> e.get("type").getAsString().equals("main-complete")).count());
        assertEquals(List.of("main", "server"), events.stream().filter(e -> e.get("type").getAsString().equals("entrypoint-complete"))
                .map(e -> e.getAsJsonObject("details").get("group").getAsString()).toList());
        assertEquals(List.of("open", "frozen"), events.stream().filter(e -> e.get("type").getAsString().equals("registry-window"))
                .map(e -> e.getAsJsonObject("details").get("state").getAsString()).toList());
        assertTrue(events.stream().anyMatch(e -> e.get("type").getAsString().equals("class-defined") && e.get("subject").getAsString().equals("net.minecraft.world.item.Item")));
        assertTrue(events.stream().anyMatch(e -> e.get("type").getAsString().equals("mod-remap") && e.getAsJsonObject("details").get("from").getAsString().equals("intermediary")));
        assertTrue(Files.exists(launch.working().resolve("server.properties")));
        assertTrue(Files.readString(launch.working().resolve("eula.txt")).contains("eula=false"));
        assertFalse(Files.exists(launch.working().resolve("world")));
    }

    @Test void vanillaMainCannotSwallowAFabricInitializerFailureAndReportSuccess() throws Exception {
        Launch launch = launch(true, Path.of(System.getProperty("minecraft.runtime")));
        assertEquals(1, launch.status(), launch.output());
        assertTrue(launch.output().contains("intentional Fabric probe failure"));
        assertFalse(launch.output().contains("MINECRAFT_PROBE_OK"));
        assertEquals("FAILED", launch.audit().get("outcome").getAsString());
        var failure = events(launch).stream().filter(e -> e.get("type").getAsString().equals("failure") && e.get("subject").getAsString().equals("launch")).findFirst().orElseThrow();
        assertEquals("LAUNCH_FAILED", failure.getAsJsonObject("details").get("code").getAsString());
        assertEquals("true", failure.getAsJsonObject("details").get("stateTainted").getAsString());
        assertFalse(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("verification-complete")));
        assertFalse(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("entrypoint-start") && e.getAsJsonObject("details").get("group").getAsString().equals("server")));
    }

    @Test void tamperedPreparedInputIsRejectedBeforeGameClassDefinition() throws Exception {
        Path source = Path.of(System.getProperty("minecraft.runtime"));
        JsonObject plan = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
        // Change a hash in the real plan, keeping its root and all other inputs valid.
        plan.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", "0".repeat(64));
        Path tampered = source.resolveSibling("tampered-test-" + UUID.randomUUID() + ".json");
        try {
            Files.writeString(tampered, plan.toString());
            Launch launch = launch(false, tampered);
            assertEquals(1, launch.status());
            assertTrue(launch.output().contains("INPUT_CHECKSUM"), launch.output());
            assertFalse(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("class-defined")));
        } finally { Files.deleteIfExists(tampered); }
    }

    private Launch launch(boolean fail, Path runtime) throws Exception {
        Path working = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        Path report = working.resolve("audit.json"), output = working.resolve("output.txt");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx2g"));
        if (fail) command.add("-Dneoforbric.probe.fail=true");
        command.addAll(List.of("-cp", System.getProperty("loader.runtimeClasspath"), "org.neoforbric.bootstrap.Main", "--minecraft-server", "--runtime", runtime.toString(),
                "--mods", System.getProperty("minecraft.mods"), "--verify", "demo.fabricprobe.ItemProbe", "--audit", report.toString(), "--", "--initSettings"));
        Process process = new ProcessBuilder(command).directory(working.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Minecraft bootstrap did not terminate");
            return new Launch(process.exitValue(), Files.readString(output), JsonParser.parseString(Files.readString(report)).getAsJsonObject(), working);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private List<JsonObject> events(Launch launch) { return launch.audit().getAsJsonArray("events").asList().stream().map(JsonElement::getAsJsonObject).toList(); }
}
