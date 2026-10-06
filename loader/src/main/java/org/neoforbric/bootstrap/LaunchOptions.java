package org.neoforbric.bootstrap;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.*;
import org.neoforbric.loader.Failure;

public record LaunchOptions(Path game, Path mods, String mainClass, String side, Path audit,
                            boolean inspect, List<String> gameArguments) {
    public LaunchOptions {
        Objects.requireNonNull(mods);
        Objects.requireNonNull(audit);
        gameArguments = List.copyOf(gameArguments);
        if (!Set.of("client", "server").contains(side)) throw new Failure("ARGUMENTS", "side must be client or server");
        if (!inspect && (game == null || mainClass == null || mainClass.isBlank())) throw new Failure("ARGUMENTS", "Fixture launch requires game and main");
    }

    public static LaunchOptions parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        boolean fixture = false, inspect = false;
        List<String> tail = List.of();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--")) { tail = List.of(Arrays.copyOfRange(args, i + 1, args.length)); break; }
            if (arg.equals("--fixture")) { if (fixture) throw new Failure("ARGUMENTS", "Duplicate --fixture"); fixture = true; continue; }
            if (arg.equals("--inspect")) { if (inspect) throw new Failure("ARGUMENTS", "Duplicate --inspect"); inspect = true; continue; }
            if (!Set.of("--game", "--mods", "--main", "--side", "--audit").contains(arg) || i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new Failure("ARGUMENTS", "Unknown option or missing value: " + arg);
            if (values.putIfAbsent(arg, args[++i]) != null) throw new Failure("ARGUMENTS", "Duplicate " + arg);
        }
        if (fixture == inspect) throw new Failure("ARGUMENTS", "Choose --fixture or --inspect; native game execution is not implemented");
        if (!values.containsKey("--mods")) throw new Failure("ARGUMENTS", "Missing --mods directory");
        if (fixture && (!values.containsKey("--game") || !values.containsKey("--main"))) throw new Failure("ARGUMENTS", "Fixture requires --game and --main");
        try {
            return new LaunchOptions(values.containsKey("--game") ? Path.of(values.get("--game")) : null, Path.of(values.get("--mods")),
                    values.get("--main"), values.getOrDefault("--side", "server"), Path.of(values.getOrDefault("--audit", "build/launch-audit.json")), inspect, tail);
        } catch (InvalidPathException invalid) {
            throw new Failure("ARGUMENTS", "Invalid path: " + invalid.getReason(), invalid);
        }
    }
}
