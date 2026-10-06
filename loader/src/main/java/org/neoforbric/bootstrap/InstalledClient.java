package org.neoforbric.bootstrap;

import java.nio.file.Path;
import java.util.*;
import org.neoforbric.loader.Failure;

/** Launcher-facing bridge. Minecraft arguments, including account credentials, pass through unchanged. */
public final class InstalledClient {
    private InstalledClient() {}
    public static void main(String[] args) {
        try { Main.main(bootstrapArguments(args)); }
        catch (Failure failure) { System.err.println("[" + failure.code() + "] " + failure.getMessage()); System.exit(2); }
    }
    static String[] bootstrapArguments(String[] args) {
        Map<String, String> settings = new HashMap<>();
        List<String> game = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--nf-")) { game.add(key); continue; }
            if (!Set.of("--nf-runtime", "--nf-client-ui", "--nf-verify", "--nf-stop-after-frames").contains(key)
                    || i + 1 >= args.length || args[i + 1].startsWith("--") || settings.putIfAbsent(key, args[++i]) != null)
                throw new Failure("ARGUMENTS", "Invalid or duplicate installer argument: " + key);
        }
        if (!settings.containsKey("--nf-runtime") || !settings.containsKey("--nf-client-ui"))
            throw new Failure("ARGUMENTS", "Installed NeoForbric requires its runtime and client UI paths");
        Path gameDir = Path.of(".").toAbsolutePath();
        for (int i = 0; i + 1 < game.size(); i++) if (game.get(i).equals("--gameDir")) gameDir = Path.of(game.get(i + 1));
        List<String> result = new ArrayList<>(List.of("--minecraft-client", "--runtime", settings.get("--nf-runtime"),
                "--client-ui", settings.get("--nf-client-ui"), "--mods", gameDir.resolve("mods").toString(),
                "--audit", gameDir.resolve("neoforbric-audit.json").toString()));
        if (settings.containsKey("--nf-verify")) result.addAll(List.of("--verify", settings.get("--nf-verify")));
        if (settings.containsKey("--nf-stop-after-frames")) result.addAll(List.of("--stop-after-frames", settings.get("--nf-stop-after-frames")));
        result.add("--"); result.addAll(game); return result.toArray(String[]::new);
    }
}
