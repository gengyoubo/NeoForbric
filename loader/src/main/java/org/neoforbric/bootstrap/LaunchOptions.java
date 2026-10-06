package org.neoforbric.bootstrap;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.*;
import org.neoforbric.loader.Failure;

public record LaunchOptions(Path game, Path mods, String mainClass, String side, Path audit,
                            boolean inspect, List<String> gameArguments, Path runtime, String verifier, boolean runServer, int stopAfterTicks) {
    public LaunchOptions(Path game, Path mods, String mainClass, String side, Path audit, boolean inspect, List<String> gameArguments, Path runtime, String verifier) {
        this(game, mods, mainClass, side, audit, inspect, gameArguments, runtime, verifier, false, 0);
    }
    public LaunchOptions(Path game, Path mods, String mainClass, String side, Path audit, boolean inspect, List<String> gameArguments) {
        this(game, mods, mainClass, side, audit, inspect, gameArguments, null, null);
    }
    public boolean minecraft() { return runtime != null; }
    public LaunchOptions {
        Objects.requireNonNull(mods);
        Objects.requireNonNull(audit);
        gameArguments = List.copyOf(gameArguments);
        if (!Set.of("client", "server").contains(side)) throw new Failure("ARGUMENTS", "side must be client or server");
        if (!inspect && runtime == null && (game == null || mainClass == null || mainClass.isBlank())) throw new Failure("ARGUMENTS", "Fixture launch requires game and main");
        if (runtime != null && (!side.equals("server") || runServer == gameArguments.contains("--initSettings")))
            throw new Failure("ARGUMENTS", "Choose settings initialization or --run-server without --initSettings");
        if ((runServer && runtime == null) || stopAfterTicks < 0 || stopAfterTicks > 20000 || (stopAfterTicks != 0 && !runServer))
            throw new Failure("ARGUMENTS", "stop-after-ticks requires --run-server and a value from 1 to 20000");
    }

    public static LaunchOptions parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        boolean fixture = false, inspect = false, minecraft = false, runServer = false;
        List<String> tail = List.of();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--")) { tail = List.of(Arrays.copyOfRange(args, i + 1, args.length)); break; }
            if (arg.equals("--fixture")) { if (fixture) throw new Failure("ARGUMENTS", "Duplicate --fixture"); fixture = true; continue; }
            if (arg.equals("--inspect")) { if (inspect) throw new Failure("ARGUMENTS", "Duplicate --inspect"); inspect = true; continue; }
            if (arg.equals("--minecraft-server")) { if (minecraft) throw new Failure("ARGUMENTS", "Duplicate --minecraft-server"); minecraft = true; continue; }
            if (arg.equals("--run-server")) { if (runServer) throw new Failure("ARGUMENTS", "Duplicate --run-server"); runServer = true; continue; }
            if (!Set.of("--game", "--mods", "--main", "--side", "--audit", "--runtime", "--verify", "--stop-after-ticks").contains(arg) || i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new Failure("ARGUMENTS", "Unknown option or missing value: " + arg);
            if (values.putIfAbsent(arg, args[++i]) != null) throw new Failure("ARGUMENTS", "Duplicate " + arg);
        }
        if ((fixture ? 1 : 0) + (inspect ? 1 : 0) + (minecraft ? 1 : 0) != 1) throw new Failure("ARGUMENTS", "Choose --fixture, --inspect or --minecraft-server");
        if (!values.containsKey("--mods")) throw new Failure("ARGUMENTS", "Missing --mods directory");
        if (fixture && (!values.containsKey("--game") || !values.containsKey("--main"))) throw new Failure("ARGUMENTS", "Fixture requires --game and --main");
        if (minecraft && (!values.containsKey("--runtime") || values.containsKey("--game") || values.containsKey("--main"))) throw new Failure("ARGUMENTS", "Minecraft profile requires --runtime and owns its game/main selection");
        if (!minecraft && (values.containsKey("--runtime") || values.containsKey("--verify"))) throw new Failure("ARGUMENTS", "runtime/verify require --minecraft-server");
        if (runServer && !minecraft) throw new Failure("ARGUMENTS", "--run-server requires --minecraft-server");
        int stopAfterTicks = 0;
        if (values.containsKey("--stop-after-ticks")) {
            try { stopAfterTicks = Integer.parseInt(values.get("--stop-after-ticks")); }
            catch (NumberFormatException invalid) { throw new Failure("ARGUMENTS", "Invalid --stop-after-ticks", invalid); }
            if (!runServer || stopAfterTicks < 1 || stopAfterTicks > 20000) throw new Failure("ARGUMENTS", "stop-after-ticks requires --run-server and a value from 1 to 20000");
        }
        if (minecraft && tail.isEmpty()) tail = List.of(runServer ? "nogui" : "--initSettings");
        if (runServer && !tail.contains("nogui") && !tail.contains("--nogui")) { tail = new ArrayList<>(tail); tail.add("nogui"); }
        try {
            return new LaunchOptions(values.containsKey("--game") ? Path.of(values.get("--game")) : null, Path.of(values.get("--mods")),
                    minecraft ? "net.minecraft.server.Main" : values.get("--main"), values.getOrDefault("--side", "server"), Path.of(values.getOrDefault("--audit", "build/launch-audit.json")), inspect, tail,
                    minecraft ? Path.of(values.get("--runtime")) : null, values.get("--verify"), runServer, stopAfterTicks);
        } catch (InvalidPathException invalid) {
            throw new Failure("ARGUMENTS", "Invalid path: " + invalid.getReason(), invalid);
        }
    }
}
