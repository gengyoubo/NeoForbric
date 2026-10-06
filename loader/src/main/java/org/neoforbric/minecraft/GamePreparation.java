package org.neoforbric.minecraft;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.*;
import org.neoforbric.loader.*;

/** Offline preparation tools. Never runs Mojang's bundler or loads a Minecraft Class. */
public final class GamePreparation {
    private GamePreparation() {}
    public static byte[] lockBytes() throws IOException {
        try (InputStream in = GamePreparation.class.getResourceAsStream("1.21.1-inputs.json")) {
            if (in == null) throw new IOException("Missing embedded Minecraft input lock");
            return in.readAllBytes();
        }
    }
    public static JsonObject lock() throws IOException { return JsonParser.parseString(new String(lockBytes(), StandardCharsets.UTF_8)).getAsJsonObject(); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("prepare")) prepare(Path.of(args[1]));
        else if (args.length == 4 && args[0].equals("remap-probe")) {
            RuntimeInputs inputs = RuntimeInputs.read(Path.of(args[1]).resolve("runtime.json"), new AuditLog());
            remap(Path.of(args[2]), Path.of(args[3]), inputs, "mojang", "intermediary");
        } else throw new Failure("ARGUMENTS", "GamePreparation: prepare <directory> | remap-probe <directory> <input.jar> <output.jar>");
    }

    public static void prepare(Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("downloads"));
        JsonObject lock = lock();
        Path version = fetch(root.resolve("downloads/version.json"), lock.get("versionUrl").getAsString(), lock.get("versionSha1").getAsString(), "SHA-1");
        JsonObject versionJson = JsonParser.parseString(Files.readString(version)).getAsJsonObject();
        if (!versionJson.get("id").getAsString().equals("1.21.1") || versionJson.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() != 21)
            throw new Failure("GAME_VERSION", "Version manifest is not Minecraft 1.21.1 / Java 21");
        Path bundle = fetch(root.resolve("downloads/server.jar"), lock.get("serverUrl").getAsString(), lock.get("serverSha256").getAsString(), "SHA-256");
        Path mappings = fetch(root.resolve("downloads/server-mappings.txt"), lock.get("mappingsUrl").getAsString(), lock.get("mappingsSha256").getAsString(), "SHA-256");
        Path intermediary = fetch(root.resolve("downloads/intermediary.jar"), lock.get("intermediaryUrl").getAsString(), lock.get("intermediarySha256").getAsString(), "SHA-256");
        if (Files.exists(root.resolve("runtime.json"))) {
            try { RuntimeInputs.read(root.resolve("runtime.json"), new AuditLog()); System.out.println("Minecraft inputs already verified: " + root); return; }
            catch (Failure | IOException invalid) { System.out.println("Rebuilding prepared inputs: " + invalid.getMessage()); }
        }
        List<Map<String, String>> files = new ArrayList<>();
        add(files, root, mappings, "mappings", lock.get("mappingsSha256").getAsString(), "mojang-server-mappings");
        add(files, root, intermediary, "intermediary-mappings", lock.get("intermediarySha256").getAsString(), "intermediary:1.21.1");
        List<Path> libraries = new ArrayList<>();
        Path rawDirectory = Files.createDirectories(root.resolve("raw"));
        Path libraryDirectory = Files.createDirectories(root.resolve("libraries"));
        Path rawGame;
        try (JarFile jar = new JarFile(bundle.toFile())) {
            if (!text(jar, "META-INF/main-class").strip().equals("net.minecraft.server.Main")) throw new Failure("BUNDLE_MAIN", "Unexpected bundled main");
            List<String> versions = text(jar, "META-INF/versions.list").lines().filter(s -> !s.isBlank()).toList();
            if (versions.size() != 1) throw new Failure("BUNDLE_VERSION", "Expected one bundled version");
            String[] game = versions.getFirst().split("\t", -1);
            if (game.length != 3 || !game[1].equals("1.21.1")) throw new Failure("BUNDLE_VERSION", "Unexpected bundle version");
            rawGame = rawDirectory.resolve("server-1.21.1.jar");
            extract(jar, "META-INF/versions/" + game[2], rawGame, game[0]);
            Set<String> coordinates = new HashSet<>();
            for (String line : text(jar, "META-INF/libraries.list").lines().filter(s -> !s.isBlank()).toList()) {
                String[] library = line.split("\t", -1);
                if (library.length != 3 || !coordinates.add(library[1])) throw new Failure("BUNDLE_LIBRARY", "Invalid / duplicate library row");
                String filename = library[0].substring(0, 12) + "-" + Path.of(library[2]).getFileName();
                Path raw = rawDirectory.resolve(filename), normalized = libraryDirectory.resolve(filename);
                extract(jar, "META-INF/libraries/" + library[2], raw, library[0]);
                int selected = normalize(raw, normalized);
                libraries.add(normalized);
                add(files, root, normalized, "library", library[0], library[1]);
                files.getLast().put("multiReleaseSelections", Integer.toString(selected));
            }
            Path named = root.resolve("server-mojang.jar"), inter = root.resolve("server-intermediary.jar");
            MemoryMappingTree tree = mappings(mappings, intermediary);
            remap(rawGame, named, tree, "official", "mojang", libraries);
            remap(rawGame, inter, tree, "official", "intermediary", libraries);
            add(files, root, named, "game", game[0], "minecraft:1.21.1:mojang");
            add(files, root, inter, "game-intermediary", game[0], "minecraft:1.21.1:intermediary");
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schemaVersion", 1); plan.put("minecraft", "1.21.1"); plan.put("java", 21);
        plan.put("namespace", "mojang"); plan.put("inputLockSha256", Archive.sha256(lockBytes()));
        plan.put("serverBundleSha256", lock.get("serverSha256").getAsString()); plan.put("files", files);
        Path temporary = root.resolve("runtime.json.part");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(plan) + "\n");
        Files.move(temporary, root.resolve("runtime.json"), StandardCopyOption.REPLACE_EXISTING);
        RuntimeInputs.read(root.resolve("runtime.json"), new AuditLog());
        System.out.println("Prepared Minecraft 1.21.1: " + libraries.size() + " verified libraries, official → mojang / intermediary");
    }

    static Path fetch(Path file, String url, String expected, String algorithm) throws Exception {
        if (Files.exists(file)) {
            if (!hash(Files.readAllBytes(file), algorithm).equals(expected)) throw new Failure("INPUT_CHECKSUM", "Cached input differs: " + file);
            return file;
        }
        var connection = URI.create(url).toURL().openConnection(); connection.setConnectTimeout(30_000); connection.setReadTimeout(60_000);
        byte[] bytes;
        try (InputStream in = connection.getInputStream()) { bytes = in.readNBytes(70 * 1024 * 1024 + 1); }
        if (bytes.length > 70 * 1024 * 1024 || !hash(bytes, algorithm).equals(expected)) throw new Failure("INPUT_CHECKSUM", "Downloaded input differs: " + url);
        Files.write(file, bytes); return file;
    }
    static String hash(byte[] bytes, String algorithm) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String text(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name); if (entry == null) throw new Failure("BUNDLE_ENTRY", "Missing " + name);
        try (InputStream in = jar.getInputStream(entry)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private static void extract(JarFile jar, String name, Path file, String expected) throws IOException {
        if (Arrays.stream(name.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals("..") || s.equals(".")) || name.contains("\\")) throw new Failure("BUNDLE_PATH", name);
        var entry = jar.getJarEntry(name); if (entry == null) throw new Failure("BUNDLE_ENTRY", "Missing " + name);
        byte[] bytes;
        try (InputStream in = jar.getInputStream(entry)) { bytes = in.readNBytes(64 * 1024 * 1024 + 1); }
        if (bytes.length > 64 * 1024 * 1024 || !Archive.sha256(bytes).equals(expected)) throw new Failure("INPUT_CHECKSUM", "Bundle entry differs: " + name);
        Files.write(file, bytes);
    }

    /** Materializes the Java 21 MR view; preserves services/resources; strips module/sealing/signature containers. */
    static int normalize(Path input, Path output) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>(); Map<String, Integer> versions = new HashMap<>(); int total = 0;
        try (JarFile jar = new JarFile(input.toFile())) {
            boolean multiRelease = jar.getManifest() != null && "true".equalsIgnoreCase(jar.getManifest().getMainAttributes().getValue("Multi-Release"));
            for (var entry : Collections.list(jar.entries())) {
                if (entry.isDirectory()) continue;
                String name = entry.getName(); int version = 0;
                if (name.startsWith("META-INF/versions/")) {
                    String[] part = name.split("/", 4); if (!multiRelease || part.length != 4) continue;
                    version = Integer.parseInt(part[2]); if (version > 21 || version < 9) continue; name = part[3];
                }
                String upper = name.toUpperCase(Locale.ROOT);
                if (name.equals("module-info.class") || upper.equals("META-INF/MANIFEST.MF")
                        || upper.startsWith("META-INF/SIG-") || (upper.startsWith("META-INF/") && upper.matches(".*\\.(SF|RSA|DSA|EC)$"))) continue;
                if (version < versions.getOrDefault(name, -1)) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(16 * 1024 * 1024 + 1); total += bytes.length;
                    if (bytes.length > 16 * 1024 * 1024 || total > 128 * 1024 * 1024) throw new Failure("ARCHIVE_LIMIT", input.toString());
                    entries.put(name, bytes); versions.put(name, version);
                }
            }
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".part");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temporary))) {
            entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            for (var entry : entries.entrySet()) {
                JarEntry next = new JarEntry(entry.getKey()); next.setTime(0); out.putNextEntry(next); out.write(entry.getValue()); out.closeEntry();
            }
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        return (int) versions.values().stream().filter(v -> v > 0).count();
    }

    public static MemoryMappingTree mappings(Path mojang, Path intermediary) throws IOException {
        MemoryMappingTree named = new MemoryMappingTree(); MappingReader.read(mojang, named);
        named.setSrcNamespace("mojang"); named.setDstNamespaces(List.of("official"));
        MemoryMappingTree merged = new MemoryMappingTree(true); named.accept(new MappingSourceNsSwitch(merged, "official"));
        try (JarFile jar = new JarFile(intermediary.toFile()); InputStream in = jar.getInputStream(jar.getJarEntry("mappings/mappings.tiny"));
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) { MappingReader.read(reader, merged); }
        return merged;
    }
    public static void remap(Path input, Path output, RuntimeInputs inputs, String from, String to) throws IOException {
        List<Path> classpath = new ArrayList<>(inputs.libraries()); classpath.add(from.equals("intermediary") ? inputs.intermediaryGame() : inputs.game());
        remap(input, output, mappings(inputs.mappings(), inputs.intermediaryMappings()), from, to, classpath);
    }
    static void remap(Path input, Path output, MemoryMappingTree tree, String from, String to, List<Path> classpath) throws IOException {
        Path temporary = output.resolveSibling(output.getFileName() + ".remapping.jar"); Files.deleteIfExists(temporary);
        TinyRemapper remapper = TinyRemapper.newRemapper().withMappings(TinyUtils.createMappingProvider(tree, from, to)).threads(2).build();
        try {
            remapper.readClassPath(classpath.toArray(Path[]::new)); remapper.readInputs(input);
            try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(temporary).build()) {
                consumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper); remapper.apply(consumer);
            }
            normalize(temporary, output);
        } finally { remapper.finish(); Files.deleteIfExists(temporary); }
    }
    static void add(List<Map<String, String>> files, Path root, Path path, String role, String original, String coordinate) throws IOException {
        Map<String, String> entry = new LinkedHashMap<>(); entry.put("path", root.relativize(path).toString().replace('\\', '/'));
        entry.put("sha256", Archive.sha256(Files.readAllBytes(path))); entry.put("role", role); entry.put("originalSha256", original); entry.put("coordinate", coordinate); files.add(entry);
    }
}
