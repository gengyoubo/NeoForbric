package org.neoforbric.minecraft;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.neoforbric.loader.*;

public record RuntimeInputs(Path game, Path intermediaryGame, Path mappings, Path intermediaryMappings, List<Path> libraries, String registryClassSha256, String side, Path assets, Path natives) {
    public RuntimeInputs { libraries = List.copyOf(libraries); }
    public static RuntimeInputs read(Path plan, AuditLog audit) throws IOException {
        Path actual = plan.toRealPath(), root = actual.getParent();
        JsonObject json = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        JsonObject lock = GamePreparation.lock();
        boolean client = json.has("side") && json.get("side").getAsString().equals("client");
        if (json.has("side") && !Set.of("server", "client").contains(json.get("side").getAsString())) throw new Failure("GAME_PLAN", "Unknown runtime side");
        if (json.get("schemaVersion").getAsInt() != 1 || !json.get("minecraft").getAsString().equals("1.21.1")
                || json.get("java").getAsInt() != 21 || !json.get("namespace").getAsString().equals("mojang")
                || !json.get("inputLockSha256").getAsString().equals(Archive.sha256(GamePreparation.lockBytes()))
                || (client ? !json.get("versionSha1").getAsString().equals(lock.get("versionSha1").getAsString())
                           : !json.get("serverBundleSha256").getAsString().equals(lock.get("serverSha256").getAsString())))
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
            else if (client && Set.of("native", "native-archive").contains(role)) { /* Checked files, not Java class owners. */ }
            else if (!(client ? Set.of("game", "game-intermediary", "mappings", "intermediary-mappings", "asset-index") : Set.of("game", "game-intermediary", "mappings", "intermediary-mappings")).contains(role) || unique.putIfAbsent(role, path) != null)
                throw new Failure("GAME_PLAN", "Unknown / duplicate role " + role);
            audit.record("PREPARE", "runtime-input", entry.get("coordinate").getAsString(), Map.of("role", role, "path", path.toString(), "sha256", sha, "originalSha256", entry.get("originalSha256").getAsString()));
        }
        JsonObject clientVersion = client ? ClientPreparation.version(root) : null;
        long expectedLibraries = client ? clientVersion.getAsJsonArray("libraries").asList().stream().map(JsonElement::getAsJsonObject).filter(ClientPreparation::selected).filter(entry -> !entry.get("name").getAsString().endsWith(":natives-windows")).count() : 30;
        if (unique.size() != (client ? 5 : 4) || libraries.size() != expectedLibraries) throw new Failure("GAME_PLAN", "Unexpected game, mapping or library count");
        if (!(client ? GamePreparation.hash(Files.readAllBytes(unique.get("mappings")), "SHA-1").equals(clientVersion.getAsJsonObject("downloads").getAsJsonObject("client_mappings").get("sha1").getAsString())
                     : Archive.sha256(Files.readAllBytes(unique.get("mappings"))).equals(lock.get("mappingsSha256").getAsString()))
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
            if (client) {
                ClientPreparation.platform();
                try {
                    String indexHash = clientVersion.getAsJsonObject("assetIndex").get("sha1").getAsString();
                    if (!GamePreparation.hash(Files.readAllBytes(unique.get("asset-index")), "SHA-1").equals(indexHash)) throw new Failure("INPUT_CHECKSUM", "Client asset index differs from manifest");
                    JsonObject objects = JsonParser.parseString(Files.readString(unique.get("asset-index"))).getAsJsonObject().getAsJsonObject("objects");
                    var verification = ClientAssets.verify(root.resolve("assets"), unique.get("asset-index"), indexHash,
                            Boolean.getBoolean("neoforbric.assets.fullVerification"));
                    audit.record("PREPARE", "client-assets", "assets", Map.of("entries", Integer.toString(objects.size()), "indexSha1", indexHash,
                            "hashed", Integer.toString(verification.hashed()), "reused", Integer.toString(verification.reused())));
                } catch (IOException | Failure error) { throw error; }
                catch (Exception error) { throw new IOException("Could not verify assets", error); }
            }
            return new RuntimeInputs(unique.get("game"), unique.get("game-intermediary"), unique.get("mappings"), unique.get("intermediary-mappings"), libraries, Archive.sha256(registry), client ? "client" : "server", client ? root.resolve("assets") : null, client ? root.resolve("natives") : null);
        }
    }
}
