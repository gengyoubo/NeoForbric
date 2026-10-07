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
public final class NeoForgePreparation {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private NeoForgePreparation() {}

    public static JsonObject lock() throws IOException {
        try (var input = NeoForgePreparation.class.getResourceAsStream("/META-INF/neoforbric/neoforge-1.21.1.lock.json")) {
            if (input == null) throw new Failure("NEOFORGE_LOCK", "Missing NeoForge input lock");
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new Failure("ARGUMENTS", "NeoForgePreparation <vanilla-client-directory> <output-directory>");
        prepare(Path.of(args[0]), Path.of(args[1]));
    }

    static Path contained(Path root, String relative) {
        if (relative.contains("\\") || relative.startsWith("/") || relative.contains(":"))
            throw new Failure("NEOFORGE_PATH", relative);
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new Failure("NEOFORGE_PATH", relative);
        return result;
    }

    static String artifactPath(String coordinate) {
        String[] extension = coordinate.split("@", -1);
        String[] parts = extension[0].split(":", -1);
        if (extension.length > 2 || parts.length < 3 || parts.length > 4
                || Arrays.stream(parts).anyMatch(p -> !p.matches("[A-Za-z0-9_.+\\-]+") || p.equals(".") || p.equals("..")))
            throw new Failure("NEOFORGE_COORDINATE", coordinate);
        String suffix = extension.length == 2 ? extension[1] : "jar";
        if (!suffix.matches("[a-z]+")) throw new Failure("NEOFORGE_COORDINATE", coordinate);
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2]
                + (parts.length == 4 ? "-" + parts[3] : "") + "." + suffix;
    }

    private static JsonObject object(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null) throw new Failure("NEOFORGE_PROFILE", "Missing " + name);
        try (var input = jar.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Path extract(JarFile jar, String name, Path output) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null) throw new Failure("NEOFORGE_PROFILE", "Missing installer entry " + name);
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
        Path plan = root.resolve("neoforge-runtime.json");
        if (Files.isRegularFile(plan) && JsonParser.parseString(Files.readString(plan)).getAsJsonObject().get("schemaVersion").getAsInt() == 2) {
            verify(plan);
            System.out.println("NeoForge inputs already verified: " + root);
            return;
        }
        try (JarFile jar = new JarFile(installer.toFile())) {
            JsonObject profile = object(jar, "install_profile.json"), version = object(jar, "version.json");
            if (!profile.get("minecraft").getAsString().equals(lock.get("minecraft").getAsString()))
                throw new Failure("NEOFORGE_PROFILE", "Installer targets a different Minecraft version");
            Map<String, JsonObject> artifacts = new LinkedHashMap<>();
            for (JsonObject source : List.of(profile, version)) for (JsonElement item : source.getAsJsonArray("libraries")) {
                JsonObject library = item.getAsJsonObject();
                artifacts.put(library.get("name").getAsString(), library.getAsJsonObject("downloads").getAsJsonObject("artifact"));
            }
            Map<String, Path> paths = new LinkedHashMap<>();
            for (var entry : artifacts.entrySet()) {
                JsonObject artifact = entry.getValue();
                String relative = artifact.get("path").getAsString();
                if (!relative.equals(artifactPath(entry.getKey()))) throw new Failure("NEOFORGE_PROFILE", "Coordinate/path mismatch: " + entry.getKey());
                paths.put(entry.getKey(), contained(root.resolve("maven"), relative));
            }
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Path>> jobs = new ArrayList<>();
                for (var entry : artifacts.entrySet()) jobs.add(() -> {
                    JsonObject artifact = entry.getValue(); Path target = paths.get(entry.getKey());
                    Files.createDirectories(target.getParent());
                    String url = artifact.get("url").getAsString(), sha1 = artifact.get("sha1").getAsString();
                    if (url.isEmpty()) {
                        synchronized (jar) { extract(jar, "maven/" + artifact.get("path").getAsString(), target); }
                        if (!GamePreparation.hash(Files.readAllBytes(target), "SHA-1").equals(sha1))
                            throw new Failure("INPUT_CHECKSUM", "Embedded NeoForge artifact differs: " + target);
                        return target;
                    }
                    return GamePreparation.fetch(target, url, sha1, "SHA-1");
                });
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
            }
            Path game = root.resolve("client-neoforge.jar");
            merge(List.of(Path.of(variables.get("MC_SRG")), Path.of(variables.get("PATCHED")), Path.of(variables.get("MC_EXTRA"))), game);
            List<Map<String, String>> files = new ArrayList<>();
            GamePreparation.add(files, root, installer, "installer", lock.get("installerSha256").getAsString(), "neoforge-installer");
            GamePreparation.add(files, root, game, "game", lock.get("installerSha256").getAsString(), "net.neoforged:neoforge:" + lock.get("neoforge").getAsString() + ":client");
            for (JsonElement item : version.getAsJsonArray("libraries")) {
                String coordinate = item.getAsJsonObject().get("name").getAsString(); Path raw = paths.get(coordinate);
                Path normalized = contained(root, "libraries/" + artifactPath(coordinate));
                Files.createDirectories(normalized.getParent()); GamePreparation.normalize(raw, normalized);
                GamePreparation.add(files, root, normalized, "library", Archive.sha256(Files.readAllBytes(raw)), coordinate);
            }
            String universalCoordinate = "net.neoforged:neoforge:" + lock.get("neoforge").getAsString() + ":universal";
            Path universal = paths.get(universalCoordinate);
            if (universal == null) throw new Failure("NEOFORGE_PROFILE", "Missing NeoForge universal artifact");
            Path normalized = root.resolve("libraries/neoforge-universal.jar"); GamePreparation.normalize(universal, normalized);
            GamePreparation.add(files, root, normalized, "neoforge", Archive.sha256(Files.readAllBytes(universal)), universalCoordinate);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", 2); result.put("minecraft", "1.21.1"); result.put("neoforge", lock.get("neoforge").getAsString());
            result.put("fml", lock.get("fml").getAsString()); result.put("namespace", "mojang");
            result.put("installerSha256", lock.get("installerSha256").getAsString()); result.put("files", files);
            Path temporary = root.resolve("neoforge-runtime.json.part"); Files.writeString(temporary, JSON.toJson(result) + "\n");
            verify(temporary); Files.move(temporary, plan, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("NEOFORGE_PREPARED version=" + lock.get("neoforge").getAsString() + " game=" + game);
        }
    }

    static String resolve(String value, Map<String, Path> artifacts, Path root, Map<String, String> variables) {
        if (value.startsWith("[") && value.endsWith("]")) {
            String coordinate = value.substring(1, value.length() - 1);
            return artifacts.getOrDefault(coordinate, contained(root.resolve("maven"), artifactPath(coordinate))).toString();
        }
        if (value.startsWith("'") && value.endsWith("'")) return value.substring(1, value.length() - 1);
        for (var variable : variables.entrySet()) value = value.replace("{" + variable.getKey() + "}", variable.getValue());
        if (value.contains("{") || value.contains("}")) throw new Failure("NEOFORGE_PROFILE", "Unresolved processor argument " + value);
        return value;
    }

    private static void run(JsonObject processor, Map<String, Path> artifacts, Path root, Map<String, String> variables) throws Exception {
        String coordinate = processor.get("jar").getAsString();
        if (!Set.of("net.neoforged.installertools:installertools:2.1.2", "net.neoforged.installertools:jarsplitter:2.1.2",
                "net.neoforged:AutoRenamingTool:2.0.3:all", "net.neoforged.installertools:binarypatcher:2.1.2:fatjar").contains(coordinate))
            throw new Failure("NEOFORGE_PROCESSOR", "Unapproved preparation tool " + coordinate);
        String main;
        try (JarFile jar = new JarFile(artifacts.get(coordinate).toFile())) { main = jar.getManifest().getMainAttributes().getValue("Main-Class"); }
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx2g", "-cp"));
        command.add(String.join(File.pathSeparator, processor.getAsJsonArray("classpath").asList().stream().map(v -> artifacts.get(v.getAsString()).toString()).toList()));
        command.add(main);
        for (JsonElement arg : processor.getAsJsonArray("args")) command.add(resolve(arg.getAsString(), artifacts, root, variables));
        System.out.println("[NeoForge prepare] " + coordinate);
        Path log = root.resolve("processor-" + coordinate.split(":")[1] + ".log");
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(5, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new Failure("NEOFORGE_PROCESSOR", "Timed out: " + coordinate); }
        if (process.exitValue() != 0) throw new Failure("NEOFORGE_PROCESSOR", coordinate + " failed; see " + log);
    }

    static void merge(List<Path> inputs, Path output) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        for (Path input : inputs) try (JarFile jar = new JarFile(input.toFile())) {
            for (var entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF") || name.equals("module-info.class")
                        || name.startsWith("META-INF/versions/") || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA)")) continue;
                byte[] bytes; try (var stream = jar.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                byte[] previous = input.equals(inputs.get(1)) ? entries.put(name, bytes) : entries.putIfAbsent(name, bytes);
                if (!input.equals(inputs.get(1)) && previous != null && !Arrays.equals(previous, bytes)) throw new Failure("DUPLICATE_CLASS", "Conflicting patched game entry " + name);
            }
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) {
            for (var entry : entries.entrySet()) { JarEntry member = new JarEntry(entry.getKey()); member.setTime(0); jar.putNextEntry(member); jar.write(entry.getValue()); jar.closeEntry(); }
        }
    }

    public static JsonObject verify(Path plan) throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(plan)).getAsJsonObject(), lock = lock();
        if (json.get("schemaVersion").getAsInt() != 2 || !json.get("minecraft").getAsString().equals("1.21.1")
                || !json.get("neoforge").equals(lock.get("neoforge")) || !json.get("fml").equals(lock.get("fml"))
                || !json.get("namespace").getAsString().equals("mojang") || !json.get("installerSha256").equals(lock.get("installerSha256")))
            throw new Failure("NEOFORGE_PLAN", "NeoForge runtime differs from pinned contract");
        Path root = plan.toAbsolutePath().normalize().getParent().toRealPath(); Set<Path> seen = new HashSet<>();
        Map<String, Integer> roles = new HashMap<>();
        for (JsonElement value : json.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject(); Path path = contained(root, file.get("path").getAsString()).toRealPath();
            String role = file.get("role").getAsString();
            if (!Set.of("installer", "game", "library", "neoforge").contains(role)) throw new Failure("NEOFORGE_PLAN", "Unknown input role " + role);
            if (role.equals("installer") && !file.get("sha256").equals(lock.get("installerSha256")))
                throw new Failure("NEOFORGE_PLAN", "Installer input differs from pinned contract");
            roles.merge(role, 1, Integer::sum);
            if (!path.startsWith(root) || !seen.add(path)) throw new Failure("NEOFORGE_PATH", path.toString());
            if (!Archive.sha256(Files.readAllBytes(path)).equals(file.get("sha256").getAsString())) throw new Failure("INPUT_CHECKSUM", "Prepared NeoForge input differs: " + path);
        }
        if (!Objects.equals(roles.get("installer"), 1) || !Objects.equals(roles.get("game"), 1) || !Objects.equals(roles.get("neoforge"), 1) || !roles.containsKey("library"))
            throw new Failure("NEOFORGE_PLAN", "Missing or duplicate required NeoForge inputs");
        return json;
    }
}
