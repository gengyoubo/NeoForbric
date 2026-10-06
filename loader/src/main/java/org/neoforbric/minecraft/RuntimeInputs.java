package org.neoforbric.minecraft;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.neoforbric.loader.*;

public record RuntimeInputs(Path game, Path intermediaryGame, Path mappings, Path intermediaryMappings, List<Path> libraries, String registryClassSha256) {
    public RuntimeInputs { libraries = List.copyOf(libraries); }
    public static RuntimeInputs read(Path plan, AuditLog audit) throws IOException {
        Path actual = plan.toRealPath(), root = actual.getParent();
        JsonObject json = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        JsonObject lock = GamePreparation.lock();
        if (json.get("schemaVersion").getAsInt() != 1 || !json.get("minecraft").getAsString().equals("1.21.1")
                || json.get("java").getAsInt() != 21 || !json.get("namespace").getAsString().equals("mojang")
                || !json.get("inputLockSha256").getAsString().equals(Archive.sha256(GamePreparation.lockBytes()))
                || !json.get("serverBundleSha256").getAsString().equals(lock.get("serverSha256").getAsString()))
            throw new Failure("GAME_PLAN", "Runtime plan differs from the embedded 1.21.1 input contract");
        Map<String, Path> unique = new HashMap<>(); List<Path> libraries = new ArrayList<>(); Set<Path> seen = new HashSet<>();
        for (JsonElement value : json.getAsJsonArray("files")) {
            JsonObject entry = value.getAsJsonObject(); Path relative = Path.of(entry.get("path").getAsString());
            if (relative.isAbsolute()) throw new Failure("GAME_PLAN_PATH", relative.toString());
            Path path = root.resolve(relative).normalize().toRealPath();
            if (!path.startsWith(root) || !seen.add(path)) throw new Failure("GAME_PLAN_PATH", "Outside runtime directory / duplicate input: " + path);
            String sha = Archive.sha256(Files.readAllBytes(path));
            if (!sha.equals(entry.get("sha256").getAsString())) throw new Failure("INPUT_CHECKSUM", "Prepared input differs: " + path);
            String role = entry.get("role").getAsString();
            if (role.equals("library")) libraries.add(path);
            else if (!Set.of("game", "game-intermediary", "mappings", "intermediary-mappings").contains(role) || unique.putIfAbsent(role, path) != null)
                throw new Failure("GAME_PLAN", "Unknown / duplicate role " + role);
            audit.record("PREPARE", "runtime-input", entry.get("coordinate").getAsString(), Map.of("role", role, "path", path.toString(), "sha256", sha, "originalSha256", entry.get("originalSha256").getAsString()));
        }
        if (unique.size() != 4 || libraries.size() != 30) throw new Failure("GAME_PLAN", "Expected two games, two mappings and 30 bundled libraries");
        if (!Archive.sha256(Files.readAllBytes(unique.get("mappings"))).equals(lock.get("mappingsSha256").getAsString())
                || !Archive.sha256(Files.readAllBytes(unique.get("intermediary-mappings"))).equals(lock.get("intermediarySha256").getAsString()))
            throw new Failure("INPUT_CHECKSUM", "Mapping inputs differ from embedded lock");
        try (JarFile game = new JarFile(unique.get("game").toFile())) {
            var version = game.getJarEntry("version.json"); if (version == null) throw new Failure("GAME_VERSION", "Game has no version.json");
            try (var stream = game.getInputStream(version)) {
                if (!JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString().equals("1.21.1"))
                    throw new Failure("GAME_VERSION", "Game JAR is not 1.21.1");
            }
            byte[] registry;
            try (var stream = game.getInputStream(game.getJarEntry("net/minecraft/core/registries/BuiltInRegistries.class"))) { registry = stream.readAllBytes(); }
            return new RuntimeInputs(unique.get("game"), unique.get("game-intermediary"), unique.get("mappings"), unique.get("intermediary-mappings"), libraries, Archive.sha256(registry));
        }
    }
}
