package org.neoforbric.minecraft;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import org.neoforbric.loader.*;

/** Prepare the pinned patched game without starting BootstrapLauncher or ModLauncher. */
public final class ForgePreparation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private ForgePreparation() {}

    public static JsonObject lock() throws IOException {
        try (var input = ForgePreparation.class.getResourceAsStream("/META-INF/neoforbric/forge-1.21.1.lock.json")) {
            if (input == null) throw new Failure("FORGE_LOCK", "Missing Forge input lock");
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new Failure("ARGUMENTS", "ForgePreparation <vanilla-client-directory> <output-directory>");
        prepare(Path.of(args[0]), Path.of(args[1]));
    }

    static Path contained(Path root, String relative) {
        if (relative.contains("\\") || relative.startsWith("/") || relative.contains(":"))
            throw new Failure("FORGE_PATH", relative);
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new Failure("FORGE_PATH", relative);
        return result;
    }

    static String artifactPath(String coordinate) {
        String[] extension = coordinate.split("@", -1);
        String[] parts = extension[0].split(":", -1);
        if (extension.length > 2 || parts.length < 3 || parts.length > 4
                || Arrays.stream(parts).anyMatch(p -> !p.matches("[A-Za-z0-9_.+\\-]+") || p.equals(".") || p.equals("..")))
            throw new Failure("FORGE_COORDINATE", coordinate);
        String suffix = extension.length == 2 ? extension[1] : "jar";
        if (!suffix.matches("[a-z]+")) throw new Failure("FORGE_COORDINATE", coordinate);
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2]
                + (parts.length == 4 ? "-" + parts[3] : "") + "." + suffix;
    }

    private static JsonObject object(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null) throw new Failure("FORGE_PROFILE", "Missing " + name);
        try (var input = jar.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Path extract(JarFile jar, String name, Path output) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null) throw new Failure("FORGE_PROFILE", "Missing installer entry " + name);
        Files.createDirectories(output.getParent());
        try (var input = jar.getInputStream(entry)) { Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING); }
        return output;
    }

    public static void prepare(Path vanillaDirectory, Path directory) throws Exception {
        Path vanilla = vanillaDirectory.toAbsolutePath().normalize(), root = directory.toAbsolutePath().normalize();
        RuntimeInputs.read(vanilla.resolve("runtime.json"), new AuditLog());
        Files.createDirectories(root.resolve("downloads"));
        JsonObject lock = lock();
        Path installer = GamePreparation.fetch(root.resolve("downloads/installer.jar"), lock.get("installerUrl").getAsString(),
                lock.get("installerSha256").getAsString(), "SHA-256");
        Path plan = root.resolve("forge-runtime.json");
        if (Files.isRegularFile(plan) && JsonParser.parseString(Files.readString(plan)).getAsJsonObject().get("schemaVersion").getAsInt() == 1) {
            verify(plan);
            System.out.println("Forge inputs already verified: " + root);
            return;
        }
        try (JarFile jar = new JarFile(installer.toFile())) {
            JsonObject profile = object(jar, "install_profile.json"), version = object(jar, "version.json");
            if (!profile.get("minecraft").getAsString().equals(lock.get("minecraft").getAsString()))
                throw new Failure("FORGE_PROFILE", "Installer targets a different Minecraft version");
            Map<String, JsonObject> artifacts = new LinkedHashMap<>();
            for (JsonObject source : List.of(profile, version)) for (JsonElement item : source.getAsJsonArray("libraries")) {
                JsonObject library = item.getAsJsonObject();
                artifacts.put(library.get("name").getAsString(), library.getAsJsonObject("downloads").getAsJsonObject("artifact"));
            }
            Map<String, Path> paths = new LinkedHashMap<>();
            for (var entry : artifacts.entrySet()) {
                JsonObject artifact = entry.getValue();
                String relative = artifact.get("path").getAsString();
                if (!relative.equals(artifactPath(entry.getKey()))) throw new Failure("FORGE_PROFILE", "Coordinate/path mismatch: " + entry.getKey());
                paths.put(entry.getKey(), contained(root.resolve("maven"), relative));
            }
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Path>> jobs = new ArrayList<>();
                for (var entry : artifacts.entrySet()) {
                    // Forge produces the patched client JAR; it is not embedded in the installer.
                    if (entry.getKey().equals("net.minecraftforge:forge:" + lock.get("fml").getAsString() + ":client")) continue;
                    jobs.add(() -> {
                    JsonObject artifact = entry.getValue(); Path target = paths.get(entry.getKey());
                    Files.createDirectories(target.getParent());
                    String url = artifact.get("url").getAsString(), sha1 = artifact.get("sha1").getAsString();
                    if (url.isEmpty()) {
                        synchronized (jar) { extract(jar, "maven/" + artifact.get("path").getAsString(), target); }
                        if (!GamePreparation.hash(Files.readAllBytes(target), "SHA-1").equals(sha1))
                            throw new Failure("INPUT_CHECKSUM", "Embedded Forge artifact differs: " + target);
                        return target;
                    }
                    return GamePreparation.fetch(target, url, sha1, "SHA-1");
                    });
                }
                for (Future<Path> job : executor.invokeAll(jobs)) job.get();
            } finally { executor.shutdownNow(); }

            Map<String, String> variables = new HashMap<>();
            variables.put("SIDE", "client"); variables.put("ROOT", root.toString());
            variables.put("INSTALLER", installer.toString()); variables.put("MINECRAFT_JAR", vanilla.resolve("downloads/client.jar").toString());
            for (var entry : profile.getAsJsonObject("data").entrySet()) {
                String value = entry.getValue().getAsJsonObject().get("client").getAsString();
                if (value.startsWith("/")) value = extract(jar, value.substring(1), contained(root, "installer-data/" + value.substring(1))).toString();
                variables.put(entry.getKey(), resolve(value, paths, root, variables));
            }
            for (JsonElement value : profile.getAsJsonArray("processors")) {
                JsonObject processor = value.getAsJsonObject();
                if (processor.has("sides") && processor.getAsJsonArray("sides").asList().stream().noneMatch(v -> v.getAsString().equals("client"))) continue;
                run(processor, paths, root, variables);
                for (var output : processor.getAsJsonObject("outputs").entrySet()) {
                    Path target = Path.of(resolve(output.getKey(), paths, root, variables));
                    if (!GamePreparation.hash(Files.readAllBytes(target), "SHA-1").equals(resolve(output.getValue().getAsString(), paths, root, variables)))
                        throw new Failure("INPUT_CHECKSUM", "Forge processor output differs: " + target);
                }
            }
            Path game = root.resolve("client-forge.jar");
            GamePreparation.normalize(Path.of(variables.get("PATCHED")), game);
            List<Map<String, String>> files = new ArrayList<>();
            GamePreparation.add(files, root, installer, "installer", lock.get("installerSha256").getAsString(), "forge-installer");
            GamePreparation.add(files, root, game, "game", Archive.sha256(Files.readAllBytes(Path.of(variables.get("PATCHED")))), "net.minecraftforge:forge:" + lock.get("fml").getAsString() + ":client");
            for (JsonElement item : version.getAsJsonArray("libraries")) {
                String coordinate = item.getAsJsonObject().get("name").getAsString();
                if (coordinate.equals("net.minecraftforge:forge:" + lock.get("fml").getAsString() + ":universal")) continue;
                if (coordinate.equals("net.minecraftforge:forge:" + lock.get("fml").getAsString() + ":client") || coordinate.endsWith(":srg2off")) continue;
                if (files.stream().anyMatch(file -> file.get("coordinate").equals(coordinate))) continue;
                Path raw = paths.get(coordinate);
                Path normalized = contained(root, "libraries/" + artifactPath(coordinate));
                Files.createDirectories(normalized.getParent()); GamePreparation.normalize(raw, normalized);
                GamePreparation.add(files, root, normalized, "library", Archive.sha256(Files.readAllBytes(raw)), coordinate);
            }
            String universalCoordinate = "net.minecraftforge:forge:" + lock.get("fml").getAsString() + ":universal";
            Path universal = paths.get(universalCoordinate);
            if (universal == null) throw new Failure("FORGE_PROFILE", "Missing Forge universal artifact");
            Path normalized = root.resolve("libraries/forge-universal.jar"); GamePreparation.normalize(universal, normalized);
            GamePreparation.add(files, root, normalized, "forge", Archive.sha256(Files.readAllBytes(universal)), universalCoordinate);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", 1); result.put("minecraft", "1.21.1"); result.put("forge", lock.get("forge").getAsString());
            result.put("fml", lock.get("fml").getAsString()); result.put("namespace", "mojang");
            result.put("modlauncher", lock.get("modlauncher").getAsString()); result.put("mixin", lock.get("mixin").getAsString());
            result.put("installerSha256", lock.get("installerSha256").getAsString()); result.put("files", files);
            Path temporary = root.resolve("forge-runtime.json.part"); Files.writeString(temporary, JSON.toJson(result) + "\n");
            verify(temporary); Files.move(temporary, plan, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("FORGE_PREPARED version=" + lock.get("forge").getAsString() + " game=" + game);
        }
    }

    static String resolve(String value, Map<String, Path> artifacts, Path root, Map<String, String> variables) {
        if (value.startsWith("[") && value.endsWith("]")) {
            String coordinate = value.substring(1, value.length() - 1);
            return artifacts.getOrDefault(coordinate, contained(root.resolve("maven"), artifactPath(coordinate))).toString();
        }
        if (value.startsWith("'") && value.endsWith("'")) return value.substring(1, value.length() - 1);
        for (var variable : variables.entrySet()) value = value.replace("{" + variable.getKey() + "}", variable.getValue());
        if (value.contains("{") || value.contains("}")) throw new Failure("FORGE_PROFILE", "Unresolved processor argument " + value);
        return value;
    }

    private static void run(JsonObject processor, Map<String, Path> artifacts, Path root, Map<String, String> variables) throws Exception {
        String coordinate = processor.get("jar").getAsString();
        if (!Set.of("net.minecraftforge:installertools:1.4.3", "net.minecraftforge:ForgeAutoRenamingTool:1.0.6",
                "net.minecraftforge:binarypatcher:1.2.0").contains(coordinate))
            throw new Failure("FORGE_PROCESSOR", "Unapproved preparation tool " + coordinate);
        String main;
        try (JarFile jar = new JarFile(artifacts.get(coordinate).toFile())) { main = jar.getManifest().getMainAttributes().getValue("Main-Class"); }
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx2g", "-cp"));
        List<String> classpath = new ArrayList<>(); classpath.add(artifacts.get(coordinate).toString());
        classpath.addAll(processor.getAsJsonArray("classpath").asList().stream().map(v -> artifacts.get(v.getAsString()).toString()).toList());
        command.add(String.join(File.pathSeparator, classpath));
        command.add(main);
        for (JsonElement arg : processor.getAsJsonArray("args")) command.add(resolve(arg.getAsString(), artifacts, root, variables));
        System.out.println("[Forge prepare] " + coordinate);
        Path log = root.resolve("processor-" + coordinate.split(":")[1] + ".log");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(5, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new Failure("FORGE_PROCESSOR", "Timed out: " + coordinate); }
        if (process.exitValue() != 0) throw new Failure("FORGE_PROCESSOR", coordinate + " failed; see " + log);
    }

    public static JsonObject verify(Path plan) throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(plan)).getAsJsonObject(), lock = lock();
        if (json.get("schemaVersion").getAsInt() != 1 || !json.get("minecraft").getAsString().equals("1.21.1")
                || !json.get("forge").equals(lock.get("forge")) || !json.get("fml").equals(lock.get("fml"))
                || !json.get("modlauncher").equals(lock.get("modlauncher")) || !json.get("mixin").equals(lock.get("mixin"))
                || !json.get("namespace").getAsString().equals("mojang") || !json.get("installerSha256").equals(lock.get("installerSha256")))
            throw new Failure("FORGE_PLAN", "Forge runtime differs from pinned contract");
        Path root = plan.toAbsolutePath().normalize().getParent().toRealPath(); Set<Path> seen = new HashSet<>();
        Map<String, Integer> roles = new HashMap<>();
        Map<String, JsonObject> expected = new HashMap<>();
        for (JsonElement value : lock.getAsJsonArray("preparedFiles")) {
            JsonObject file = value.getAsJsonObject(); expected.put(file.get("coordinate").getAsString(), file);
        }
        Set<String> coordinates = new HashSet<>();
        for (JsonElement value : json.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject(); Path path = contained(root, file.get("path").getAsString()).toRealPath();
            String role = file.get("role").getAsString();
            String coordinate = file.get("coordinate").getAsString(); JsonObject pinned = expected.get(coordinate);
            if (pinned == null || !coordinates.add(coordinate) || !file.get("role").equals(pinned.get("role")) || !file.get("sha256").equals(pinned.get("sha256")))
                throw new Failure("FORGE_PLAN", "Input differs from pinned Forge inventory: " + coordinate);
            if (!Set.of("installer", "game", "library", "forge").contains(role)) throw new Failure("FORGE_PLAN", "Unknown input role " + role);
            if (role.equals("installer") && !file.get("sha256").equals(lock.get("installerSha256")))
                throw new Failure("FORGE_PLAN", "Installer input differs from pinned contract");
            roles.merge(role, 1, Integer::sum);
            if (!path.startsWith(root) || !seen.add(path)) throw new Failure("FORGE_PATH", path.toString());
            if (!Archive.sha256(Files.readAllBytes(path)).equals(file.get("sha256").getAsString())) throw new Failure("INPUT_CHECKSUM", "Prepared Forge input differs: " + path);
        }
        if (!Objects.equals(roles.get("installer"), 1) || !Objects.equals(roles.get("game"), 1) || !Objects.equals(roles.get("forge"), 1) || !roles.containsKey("library"))
            throw new Failure("FORGE_PLAN", "Missing or duplicate required Forge inputs");
        if (!coordinates.equals(expected.keySet())) throw new Failure("FORGE_PLAN", "Incomplete pinned Forge input inventory");
        Path naming = contained(root, "maven/de/oceanlabs/mcp/mcp_config/1.21.1-20240808.132146/mcp_config-1.21.1-20240808.132146-srg2off.jar").toRealPath();
        if (!naming.startsWith(root) || !Archive.sha256(Files.readAllBytes(naming)).equals(lock.get("srg2offSha256").getAsString()))
            throw new Failure("INPUT_CHECKSUM", "Forge SRG naming input differs");
        return json;
    }
}
