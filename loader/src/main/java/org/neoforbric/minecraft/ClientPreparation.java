package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.neoforbric.loader.*;

/** Windows x64 client inputs, all rooted in the pinned Mojang version manifest. */
public final class ClientPreparation {
    private ClientPreparation() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new Failure("ARGUMENTS", "ClientPreparation <directory>");
        prepare(Path.of(args[0]));
    }
    static JsonObject version(Path root) throws java.io.IOException {
        JsonObject lock = GamePreparation.lock();
        Path manifest = root.resolve("downloads/version.json");
        if (!GamePreparation.hash(Files.readAllBytes(manifest), "SHA-1").equals(lock.get("versionSha1").getAsString())) throw new Failure("INPUT_CHECKSUM", "Client manifest differs from embedded lock");
        return JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    }
    static boolean selected(JsonObject library) {
        String name = library.get("name").getAsString();
        if (name.contains(":natives-") && !name.endsWith(":natives-windows")) return false;
        boolean allowed = !library.has("rules");
        if (library.has("rules")) for (JsonElement value : library.getAsJsonArray("rules")) {
            JsonObject rule = value.getAsJsonObject(); boolean matches = true;
            if (rule.has("features")) throw new Failure("CLIENT_RULE", "Unsupported feature rule in " + name);
            if (rule.has("os")) {
                JsonObject os = rule.getAsJsonObject("os");
                matches = !os.has("name") || os.get("name").getAsString().equals("windows");
                if (os.has("arch")) matches &= System.getProperty("os.arch").matches(os.get("arch").getAsString());
                if (os.has("version")) matches &= System.getProperty("os.version").matches(os.get("version").getAsString());
            }
            if (matches) allowed = rule.get("action").getAsString().equals("allow");
        }
        return allowed;
    }
    static void platform() {
        if (!System.getProperty("os.name").startsWith("Windows") || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new Failure("CLIENT_PLATFORM", "Client profile currently supports Windows x64 only");
    }
    static Path artifact(Path target, JsonObject artifact) throws Exception {
        Files.createDirectories(target.getParent());
        return GamePreparation.fetch(target, artifact.get("url").getAsString(), artifact.get("sha1").getAsString(), "SHA-1");
    }
    public static void prepare(Path directory) throws Exception {
        platform(); Path root = directory.toAbsolutePath().normalize(); Files.createDirectories(root.resolve("downloads"));
        JsonObject manifestLock = GamePreparation.lock();
        GamePreparation.fetch(root.resolve("downloads/version.json"), manifestLock.get("versionUrl").getAsString(), manifestLock.get("versionSha1").getAsString(), "SHA-1");
        JsonObject version = version(root);
        Path client = artifact(root.resolve("downloads/client.jar"), version.getAsJsonObject("downloads").getAsJsonObject("client"));
        Path mappings = artifact(root.resolve("downloads/client-mappings.txt"), version.getAsJsonObject("downloads").getAsJsonObject("client_mappings"));
        JsonObject lock = GamePreparation.lock();
        Path intermediary = GamePreparation.fetch(root.resolve("downloads/intermediary.jar"), lock.get("intermediaryUrl").getAsString(), lock.get("intermediarySha256").getAsString(), "SHA-256");
        boolean cachedGame = Files.exists(root.resolve("runtime.json"));
        if (cachedGame) verifyCachedGames(root, Archive.sha256(Files.readAllBytes(client)));
        List<Map<String, String>> files = new ArrayList<>(); List<Path> libraries = new ArrayList<>();
        GamePreparation.add(files, root, mappings, "mappings", GamePreparation.hash(Files.readAllBytes(mappings), "SHA-256"), "mojang-client-mappings");
        GamePreparation.add(files, root, intermediary, "intermediary-mappings", lock.get("intermediarySha256").getAsString(), "intermediary:1.21.1");
        JsonArray selected = new JsonArray();
        for (JsonElement value : version.getAsJsonArray("libraries")) {
            JsonObject library = value.getAsJsonObject(); if (selected(library)) selected.add(library);
        }
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Path>> jobs = new ArrayList<>();
            for (JsonElement value : selected) {
                JsonObject artifact = value.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact");
                jobs.add(() -> artifact(root.resolve("downloads/libraries").resolve(artifact.get("path").getAsString()), artifact));
            }
            var fetched = executor.invokeAll(jobs); int index = 0;
            for (JsonElement value : selected) {
                JsonObject library = value.getAsJsonObject(); Path raw = fetched.get(index++).get();
                String coordinate = library.get("name").getAsString();
                String original = Archive.sha256(Files.readAllBytes(raw));
                if (coordinate.endsWith(":natives-windows")) {
                    GamePreparation.add(files, root, raw, "native-archive", original, coordinate);
                    try (JarFile jar = new JarFile(raw.toFile())) {
                        for (var entry : Collections.list(jar.entries())) {
                            if (entry.isDirectory() || !entry.getName().endsWith(".dll")) continue;
                            Path output = root.resolve("natives").resolve(Path.of(entry.getName()).getFileName()); Files.createDirectories(output.getParent());
                            byte[] bytes; try (var stream = jar.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                            if (Files.exists(output) && !Archive.sha256(Files.readAllBytes(output)).equals(Archive.sha256(bytes))) throw new Failure("DUPLICATE_NATIVE", output.toString());
                            if (!Files.exists(output)) Files.write(output, bytes);
                            GamePreparation.add(files, root, output, "native", original, coordinate + ":" + output.getFileName());
                        }
                    }
                } else {
                    Path normalized = root.resolve("libraries").resolve(raw.getFileName());
                    GamePreparation.normalize(raw, normalized); libraries.add(normalized);
                    GamePreparation.add(files, root, normalized, "library", original, coordinate);
                }
            }
            JsonObject assetIndex = version.getAsJsonObject("assetIndex");
            Path assets = root.resolve("assets"); Path indexFile = artifact(assets.resolve("indexes/" + assetIndex.get("id").getAsString() + ".json"), assetIndex);
            GamePreparation.add(files, root, indexFile, "asset-index", Archive.sha256(Files.readAllBytes(indexFile)), "assets:" + assetIndex.get("id").getAsString());
            JsonObject objects = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject().getAsJsonObject("objects");
            Set<String> hashes = new TreeSet<>(); objects.entrySet().forEach(entry -> hashes.add(entry.getValue().getAsJsonObject().get("hash").getAsString()));
            jobs.clear(); AtomicInteger downloaded = new AtomicInteger();
            String appData = System.getenv("APPDATA"); Path existing = appData == null ? null : Path.of(appData, ".minecraft/assets/objects");
            for (String hash : hashes) jobs.add(() -> {
                String relative = hash.substring(0, 2) + "/" + hash; Path target = assets.resolve("objects").resolve(relative); Files.createDirectories(target.getParent());
                if (!Files.exists(target) && existing != null && Files.isRegularFile(existing.resolve(relative))
                        && GamePreparation.hash(Files.readAllBytes(existing.resolve(relative)), "SHA-1").equals(hash)) Files.copy(existing.resolve(relative), target);
                GamePreparation.fetch(target, "https://resources.download.minecraft.net/" + relative, hash, "SHA-1");
                int count = downloaded.incrementAndGet(); if (count % 250 == 0 || count == hashes.size()) System.out.println("Client assets verified: " + count + "/" + hashes.size());
                return target;
            });
            for (Future<Path> future : executor.invokeAll(jobs)) future.get();
        } finally { executor.shutdownNow(); }
        Path named = root.resolve("client-mojang.jar"), inter = root.resolve("client-intermediary.jar");
        // Mapping input hashes invalidate both derived games. Libraries are always checked against the manifest.
        if (!cachedGame) {
            var tree = GamePreparation.mappings(mappings, intermediary);
            GamePreparation.remap(client, named, tree, "official", "mojang", libraries);
            GamePreparation.remap(client, inter, tree, "official", "intermediary", libraries);
        }
        String rawGame = Archive.sha256(Files.readAllBytes(client));
        GamePreparation.add(files, root, named, "game", rawGame, "minecraft:1.21.1:client:mojang");
        GamePreparation.add(files, root, inter, "game-intermediary", rawGame, "minecraft:1.21.1:client:intermediary");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", 1); plan.put("minecraft", "1.21.1"); plan.put("java", 21); plan.put("namespace", "mojang"); plan.put("side", "client");
        plan.put("inputLockSha256", Archive.sha256(GamePreparation.lockBytes())); plan.put("versionSha1", lock.get("versionSha1").getAsString());
        plan.put("files", files); Files.writeString(root.resolve("runtime.json"), new GsonBuilder().setPrettyPrinting().create().toJson(plan) + "\n");
        RuntimeInputs.read(root.resolve("runtime.json"), new AuditLog());
        System.out.println("Prepared Minecraft client 1.21.1: " + libraries.size() + " libraries, Windows x64 natives and complete assets");
    }
    /** Verify retained game bytes before regeneration; libraries are derived anew from verified official inputs. */
    static void verifyCachedGames(Path root, String rawGameHash) throws Exception {
        JsonObject plan = JsonParser.parseString(Files.readString(root.resolve("runtime.json"))).getAsJsonObject();
        JsonObject lock = GamePreparation.lock();
        if (plan.get("schemaVersion").getAsInt() != 1 || !plan.get("minecraft").getAsString().equals("1.21.1")
                || plan.get("java").getAsInt() != 21 || !plan.get("namespace").getAsString().equals("mojang")
                || !plan.get("side").getAsString().equals("client")
                || !plan.get("inputLockSha256").getAsString().equals(Archive.sha256(GamePreparation.lockBytes()))
                || !plan.get("versionSha1").getAsString().equals(lock.get("versionSha1").getAsString()))
            throw new Failure("GAME_PLAN", "Cached client plan differs from pinned inputs");
        Set<String> found = new HashSet<>();
        for (JsonElement value : plan.getAsJsonArray("files")) {
            JsonObject entry = value.getAsJsonObject(); String role = entry.get("role").getAsString();
            if (!Set.of("game", "game-intermediary").contains(role)) continue;
            String expected = role.equals("game") ? "client-mojang.jar" : "client-intermediary.jar";
            Path path = root.resolve(expected).toRealPath();
            if (!found.add(role) || !entry.get("path").getAsString().equals(expected) || !path.startsWith(root.toRealPath())
                    || !entry.get("originalSha256").getAsString().equals(rawGameHash))
                throw new Failure("GAME_PLAN", "Cached client game provenance differs: " + expected);
            if (!Archive.sha256(Files.readAllBytes(path)).equals(entry.get("sha256").getAsString()))
                throw new Failure("INPUT_CHECKSUM", "Prepared input differs: " + path);
        }
        if (found.size() != 2) throw new Failure("GAME_PLAN", "Cached client plan must contain both game artifacts");
    }
}
