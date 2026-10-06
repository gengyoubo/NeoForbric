package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftClientRuntimeTest {
    @TempDir Path temporary;
    private record Launch(int status, String output, JsonObject audit) {}
    @Test void nativeWindowLoadsResourcesRendersMainMenuAndClosesAfterFiveFrames() throws Exception {
        Launch launch = launch(false);
        assertEquals(0, launch.status(), launch.output()); assertEquals("SUCCESS", launch.audit().get("outcome").getAsString());
        assertEquals("minecraft-1.21.1-client", launch.audit().get("mode").getAsString());
        assertTrue(launch.output().contains("CLIENT_PROBE_OK main=1 client=1 menu=TitleScreen resourcesLoaded=true itemIdentity=true gameLoader=NeoForbric-Game"), launch.output());
        var events = events(launch);
        assertEquals(List.of("main", "client"), events.stream().filter(e -> e.get("type").getAsString().equals("entrypoint-complete")).map(e -> e.getAsJsonObject("details").get("group").getAsString()).toList());
        assertEquals(List.of("main-menu", "stop-requested", "destroyed"), events.stream().filter(e -> e.get("type").getAsString().equals("client-lifecycle")).map(e -> e.get("subject").getAsString()).toList());
        assertTrue(events.stream().anyMatch(e -> e.get("type").getAsString().equals("client-complete") && e.getAsJsonObject("details").get("framesAfterMenu").getAsString().equals("5")));
        var image = ImageIO.read(temporary.resolve("screenshots/neoforbric-main-menu.png").toFile());
        assertTrue(image.getWidth() >= 800 && image.getHeight() >= 450);
        Set<Integer> colors = new HashSet<>();
        for (int x = 0; x < image.getWidth(); x += 16) for (int y = 0; y < image.getHeight(); y += 16) colors.add(image.getRGB(x, y));
        assertTrue(colors.size() > 100, "Framebuffer contains no rendered menu");
    }
    @Test void frameFailureCannotEscapeTheKernelAuditThroughVanillaSystemExit() throws Exception {
        Launch launch = launch(true);
        assertEquals(1, launch.status(), launch.output()); assertEquals("FAILED", launch.audit().get("outcome").getAsString());
        assertTrue(launch.output().contains("intentional client frame failure"), launch.output());
        assertFalse(events(launch).stream().anyMatch(e -> e.get("type").getAsString().equals("client-complete")));
        assertTrue(Files.exists(temporary.resolve("crash-reports")));
    }
    @Test void modsButtonShowsFinalEcosystemDecisionsWithIconsAndReturnsToTitle() throws Exception {
        Path mods = Files.createDirectories(temporary.resolve("mods"));
        try (var probes = Files.list(Path.of(System.getProperty("minecraft.clientMods")))) {
            for (Path jar : probes.toList()) Files.copy(jar, mods.resolve(jar.getFileName()));
        }
        for (String ecosystem : List.of("forge", "neoforge")) {
            try (var output = new JarOutputStream(Files.newOutputStream(mods.resolve(ecosystem + ".jar")))) {
                output.putNextEntry(new JarEntry("META-INF/" + (ecosystem.equals("forge") ? "mods.toml" : "neoforge.mods.toml")));
                output.write(("[[mods]]\nmodId=\"" + ecosystem + "_ui_probe\"\nversion=\"1.0.0\"\ndisplayName=\"" + ecosystem + " Adapter Probe\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        try (var output = new JarOutputStream(Files.newOutputStream(mods.resolve("server-only.jar")))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("{\"schemaVersion\":1,\"id\":\"server_only\",\"version\":\"1.0.0\",\"environment\":\"server\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        try (var output = new JarOutputStream(Files.newOutputStream(mods.resolve("00-diagnostics.jar")))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                    {"schemaVersion":1,"id":"diagnostic_ui_probe","name":"Unsupported Fabric Probe","version":"1.0.0",
                     "mixins":["diagnostic.mixins.json",{"config":"diagnostic.client.mixins.json","environment":"client"}],
                     "accessWidener":"diagnostic.accesswidener","jars":[{"file":"META-INF/jars/example.jar"}],
                     "languageAdapters":{"kotlin":"example.KotlinAdapter"},"depends":{"fabric-api":">=0.102.0"},
                     "entrypoints":{"client":["example.Client::create"],"jei_mod_plugin":["example.Plugin"]}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        Launch launch = launch(false, mods);
        assertEquals(0, launch.status(), launch.output());
        assertTrue(launch.output().contains("MODS_SCREEN_OK loaded=2 listed=6 sourceAndRuntime=true icons=true"), launch.output());
        assertTrue(launch.output().contains("MODS_DIAGNOSTICS_OK scroll=true tooltip=true blockers=7"), launch.output());
        assertTrue(launch.output().contains("MODS_BACK_OK title=TitleScreen buttons=1"), launch.output());
        var decisions = events(launch).stream().filter(e -> e.get("type").getAsString().equals("mod-status")).toList();
        var diagnostic = decisions.stream().filter(e -> e.get("subject").getAsString().equals("diagnostic_ui_probe")).findFirst().orElseThrow().getAsJsonObject("details");
        assertTrue(diagnostic.get("reason").getAsString().contains("diagnostic.accesswidener"));
        assertEquals(8, JsonParser.parseString(diagnostic.get("diagnostics").getAsString()).getAsJsonArray().size());
        for (String id : List.of("forge_ui_probe", "neoforge_ui_probe"))
            assertTrue(decisions.stream().anyMatch(e -> e.get("subject").getAsString().equals(id) && e.getAsJsonObject("details").get("status").getAsString().equals("UNSUPPORTED")));
        assertTrue(decisions.stream().anyMatch(e -> e.get("subject").getAsString().equals("server_only") && e.getAsJsonObject("details").get("status").getAsString().equals("DISABLED")));
        Path evidence = Files.createDirectories(Path.of(System.getProperty("minecraft.clientEvidence")));
        for (String screenshot : List.of("neoforbric-main-menu.png", "neoforbric-mods.png", "neoforbric-mod-diagnostics.png", "neoforbric-mod-tooltip.png")) {
            Path source = temporary.resolve("screenshots").resolve(screenshot);
            var image = ImageIO.read(source.toFile()); assertTrue(image.getWidth() >= 800);
            Files.copy(source, evidence.resolve(screenshot), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private Launch launch(boolean fail) throws Exception {
        return launch(fail, null);
    }
    private Launch launch(boolean fail, Path mods) throws Exception {
        Files.writeString(temporary.resolve("options.txt"), "onboardAccessibility:false\nrenderDistance:4\n");
        Path java = Path.of(System.getProperty("java.home"), "bin/java.exe");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx2g", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        if (fail) command.add("-Dneoforbric.probe.client.fail=true");
        if (mods != null) command.add("-Dneoforbric.probe.mods=true");
        if (mods != null) command.add("-Dneoforbric.probe.diagnostics=true");
        command.addAll(List.of("-cp", System.getProperty("loader.runtimeClasspath"), "org.neoforbric.bootstrap.Main", "--minecraft-client", "--runtime", System.getProperty("minecraft.clientRuntime"),
                "--mods", mods == null ? System.getProperty("minecraft.clientMods") : mods.toString(), "--client-ui", System.getProperty("minecraft.clientUi"), "--verify", "demo.clientprobe.ClientProbe", "--stop-after-frames", mods == null ? "5" : "40", "--audit", temporary.resolve("audit.json").toString(),
                "--", "--username", "NeoForbricTest", "--uuid", "00000000-0000-0000-0000-000000000001", "--accessToken", "0", "--version", "1.21.1", "--gameDir", temporary.toString(),
                "--assetsDir", System.getProperty("minecraft.clientAssets"), "--assetIndex", "17", "--width", "960", "--height", "540"));
        Process process = new ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(temporary.resolve("output.txt").toFile()).start();
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Client did not exit: " + Files.readString(temporary.resolve("output.txt")));
            Path evidence = Files.createDirectories(Path.of(System.getProperty("minecraft.clientEvidence")));
            String label = mods != null ? "mods" : fail ? "failure" : "native";
            Files.copy(temporary.resolve("output.txt"), evidence.resolve(label + "-output.txt"), StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(temporary.resolve("audit.json"))) Files.copy(temporary.resolve("audit.json"), evidence.resolve(label + "-audit.json"), StandardCopyOption.REPLACE_EXISTING);
            return new Launch(process.exitValue(), Files.readString(temporary.resolve("output.txt")), JsonParser.parseString(Files.readString(temporary.resolve("audit.json"))).getAsJsonObject());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private List<JsonObject> events(Launch launch) { return launch.audit().getAsJsonArray("events").asList().stream().map(JsonElement::getAsJsonObject).toList(); }
}
