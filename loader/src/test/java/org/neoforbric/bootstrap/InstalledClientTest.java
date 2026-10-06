package org.neoforbric.bootstrap;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.neoforbric.loader.Failure;
import static org.junit.jupiter.api.Assertions.*;

class InstalledClientTest {
    @Test void launcherPathsAndCredentialsReachTheirIntendedDomains() {
        String[] game = {"--username", "Tester", "--accessToken", "test-token", "--gameDir", "game folder", "--width", "960"};
        List<String> input = new ArrayList<>(List.of("--nf-runtime", "runtime folder/runtime.json", "--nf-client-ui", "ui folder/client-ui.jar",
                "--nf-verify", "demo.clientprobe.ClientProbe", "--nf-stop-after-frames", "5"));
        input.addAll(List.of(game));
        LaunchOptions options = LaunchOptions.parse(InstalledClient.bootstrapArguments(input.toArray(String[]::new)));
        assertTrue(options.client()); assertArrayEquals(game, options.gameArguments().toArray(String[]::new));
        assertEquals(Path.of("game folder/mods"), options.mods()); assertEquals(Path.of("game folder/neoforbric-audit.json"), options.audit());
        assertEquals(Path.of("runtime folder/runtime.json"), options.runtime()); assertEquals(Path.of("ui folder/client-ui.jar"), options.clientUi());
        assertEquals(5, options.stopAfterFrames()); assertEquals("demo.clientprobe.ClientProbe", options.verifier());
    }
    @Test void malformedOrRepeatedInstallerArgumentsFailBeforeLaunching() {
        for (String[] args : List.of(new String[]{"--nf-runtime"}, new String[]{"--nf-unknown", "x"},
                new String[]{"--nf-runtime", "a", "--nf-runtime", "b"}, new String[]{"--nf-runtime", "a"}))
            assertEquals("ARGUMENTS", assertThrows(Failure.class, () -> InstalledClient.bootstrapArguments(args)).code());
    }
}
