package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MinecraftServerRuntimeTest {
    @TempDir Path temporary;
    private record Launch(int status, String output, JsonObject audit) {}

    @Test void unacceptedEulaStopsBeforeDefiningGameOrCreatingAWorld() throws Exception {
        Launch launch = finish(start(temporary, 3, false, 0), temporary);
        assertEquals(1, launch.status(), launch.output()); assertTrue(launch.output().contains("EULA_REQUIRED"));
        assertFalse(Files.exists(temporary.resolve("world")));
        assertFalse(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("class-defined")));
    }
    @Test void worldAndRegisteredItemStackSurviveSaveAndSecondJvmLaunch() throws Exception {
        prepareWorld();
        Launch first = finish(start(temporary, 3, false, 0), temporary);
        successful(first); assertTrue(first.output().contains("previousLaunches=0"), first.output());
        assertTrue(Files.exists(temporary.resolve("world/level.dat")));
        assertTrue(Files.exists(temporary.resolve("world/data/neoforbric_item_probe.dat")));
        Launch second = finish(start(temporary, 3, false, 0), temporary);
        successful(second); assertTrue(second.output().contains("previousLaunches=1"), second.output());
        assertTrue(second.output().contains("savedStackIdentity=true"));
    }
    @Test void consoleStopSavesTheWorldAndOnlyThenClosesTheGameLoader() throws Exception {
        prepareWorld(); Process process = start(temporary, 0, false, 0);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!Files.exists(temporary.resolve("audit.json")) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(process.isAlive(), Files.readString(temporary.resolve("output.txt")));
            assertEquals("RUNNING", JsonParser.parseString(Files.readString(temporary.resolve("audit.json"))).getAsJsonObject().get("outcome").getAsString());
            process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8)); process.getOutputStream().flush();
            successful(finish(process, temporary));
            assertTrue(Files.exists(temporary.resolve("world/level.dat")));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test void tickFailureIsFatalEvenWhenVanillaCatchesItAndSavesTheWorld() throws Exception {
        prepareWorld(); Launch launch = finish(start(temporary, 3, true, 0), temporary);
        assertEquals(1, launch.status(), launch.output()); assertEquals("FAILED", launch.audit().get("outcome").getAsString());
        assertTrue(launch.output().contains("intentional server tick failure"), launch.output());
        assertFalse(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("server-complete")));
        assertTrue(events(launch).stream().anyMatch(e -> e.get("subject").getAsString().equals("thread-ended")));
    }
    @Test void occupiedPortCannotBeReportedAsASuccessfulServer() throws Exception {
        prepareWorld();
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Launch launch = finish(start(temporary, 3, false, socket.getLocalPort()), temporary);
            assertEquals(1, launch.status(), launch.output()); assertEquals("FAILED", launch.audit().get("outcome").getAsString());
            assertFalse(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("server-complete")));
        }
    }
    private void prepareWorld() throws Exception {
        assumeTrue(Boolean.getBoolean("minecraft.acceptEula"), "Use -PacceptEula=true only after accepting the Minecraft EULA");
        Files.writeString(temporary.resolve("eula.txt"), "eula=true\n");
        Files.writeString(temporary.resolve("server.properties"), "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenforce-secure-profile=false\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nlevel-seed=0\n");
    }
    private Process start(Path working, int ticks, boolean fail, int port) throws Exception {
        Files.deleteIfExists(working.resolve("audit.json"));
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx2g"));
        if (fail) command.add("-Dneoforbric.probe.server.fail=true");
        command.addAll(List.of("-cp", System.getProperty("loader.runtimeClasspath"), "org.neoforbric.bootstrap.Main", "--minecraft-server", "--run-server", "--runtime", System.getProperty("minecraft.runtime"),
                "--mods", System.getProperty("minecraft.mods"), "--verify", "demo.fabricprobe.ItemProbe", "--audit", working.resolve("audit.json").toString()));
        if (ticks != 0) command.addAll(List.of("--stop-after-ticks", Integer.toString(ticks)));
        command.addAll(List.of("--", "nogui", "--port", Integer.toString(port)));
        return new ProcessBuilder(command).directory(working.toFile()).redirectErrorStream(true).redirectOutput(working.resolve("output.txt").toFile()).start();
    }
    private Launch finish(Process process, Path working) throws Exception {
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Server did not terminate; output: " + Files.readString(working.resolve("output.txt")));
            return new Launch(process.exitValue(), Files.readString(working.resolve("output.txt")), JsonParser.parseString(Files.readString(working.resolve("audit.json"))).getAsJsonObject());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private List<JsonObject> events(Launch launch) { return launch.audit().getAsJsonArray("events").asList().stream().map(JsonElement::getAsJsonObject).toList(); }
    private void successful(Launch launch) {
        assertEquals(0, launch.status(), launch.output()); assertEquals("SUCCESS", launch.audit().get("outcome").getAsString());
        assertEquals("minecraft-1.21.1-server", launch.audit().get("mode").getAsString());
        var lifecycle = events(launch).stream().filter(e -> e.get("type").getAsString().equals("server-lifecycle")).map(e -> e.get("subject").getAsString()).toList();
        assertTrue(lifecycle.indexOf("bound") < lifecycle.indexOf("started"));
        assertTrue(lifecycle.indexOf("first-tick") < lifecycle.indexOf("saved"));
        assertTrue(lifecycle.indexOf("saved") < lifecycle.indexOf("thread-ended"));
        long ended = events(launch).stream().filter(e -> e.get("subject").getAsString().equals("thread-ended")).findFirst().orElseThrow().get("sequence").getAsLong();
        long restored = events(launch).stream().filter(e -> e.get("type").getAsString().equals("tccl-restored")).findFirst().orElseThrow().get("sequence").getAsLong();
        assertTrue(ended < restored);
        assertTrue(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("server-complete")));
    }
}
