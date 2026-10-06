package org.neoforbric.installer;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;
import java.util.stream.Stream;

/** Installs a launcher version while keeping Minecraft classes off the bootstrap classpath. */
public final class Installer {
    public record Options(Path directory, String versionId, boolean profile, Path runtimeSource) {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private Installer() {}

    public static JsonObject catalog() throws IOException {
        return JsonParser.parseString(new String(resource("payload/catalog.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    public static Path install(Options options, Consumer<String> progress) throws Exception {
        if (Runtime.version().feature() != 21) throw new IOException("请使用 Java 21 运行安装器，当前为 Java " + Runtime.version().feature());
        if (!System.getProperty("os.name").startsWith("Windows") || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw new IOException("当前客户端仅支持 Windows x64。");
        validateId(options.versionId());
        Path root = options.directory().toAbsolutePath().normalize(); Files.createDirectories(root); root = root.toRealPath();
        Path version = contained(root, "versions/" + options.versionId());
        Path owned = contained(version, "neoforbric/install.json");
        if (Files.exists(version) && !Files.exists(owned)) {
            try (Stream<Path> entries = Files.list(version)) {
                if (entries.findAny().isPresent()) throw new IOException("该版本目录已有其他内容，请选择其他版本名称：" + version);
            }
        }
        if (Files.exists(owned)) {
            JsonObject marker = JsonParser.parseString(Files.readString(owned)).getAsJsonObject();
            if (!marker.has("installer") || !marker.get("installer").getAsString().equals("NeoForbric") || marker.get("schemaVersion").getAsInt() != 1)
                throw new IOException("该目录不是这个安装器创建的 NF 版本：" + version);
        }
        Files.createDirectories(owned.getParent());
        try (FileChannel lock = FileChannel.open(version.resolve("neoforbric/installer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lease;
            try { lease = lock.tryLock(); } catch (OverlappingFileLockException error) { lease = null; }
            if (lease == null) throw new IOException("这个版本正在被另一个安装器安装。");
            try (FileLock held = lease) {
                writeJson(owned, Map.of("installer", "NeoForbric", "schemaVersion", 1, "state", "preparing"));
                JsonObject payload = catalog();
                List<Path> classpath = new ArrayList<>();
                for (JsonElement value : payload.getAsJsonArray("libraries")) {
                    JsonObject library = value.getAsJsonObject();
                    boolean kernel = library.get("name").getAsString().startsWith("org.neoforbric:loader:");
                    Path target = kernel ? version.resolve(options.versionId() + ".jar") : contained(root.resolve("libraries"), library.get("path").getAsString());
                    installResource(library.get("resource").getAsString(), target, library.get("sha256").getAsString(), kernel);
                    classpath.add(target);
                }
                Path ui = version.resolve("neoforbric/client-ui.jar");
                JsonObject uiSpec = payload.getAsJsonObject("ui");
                installResource(uiSpec.get("resource").getAsString(), ui, uiSpec.get("sha256").getAsString(), true);
                progress.accept("NF 加载器和 Fabric Loader " + payload.get("fabricLoader").getAsString() + " 已写入。正在准备 Minecraft 1.21.1……");
                Path runtime = version.resolve("neoforbric/runtime"); Files.createDirectories(runtime);
                if (options.runtimeSource() != null) seedRuntime(options.runtimeSource(), runtime, progress);
                prepare(classpath, runtime, root, progress);
                JsonObject vanilla = JsonParser.parseString(Files.readString(runtime.resolve("downloads/version.json"))).getAsJsonObject();
                publishAssets(root, runtime, options.versionId(), vanilla);
                JsonObject specification = versionJson(root, version, options.versionId(), payload, vanilla);
                Path versionJson = version.resolve(options.versionId() + ".json");
                writeJson(versionJson, specification);
                Files.createDirectories(version.resolve("mods"));
                if (options.profile()) updateProfiles(root, version, options.versionId());
                writeJson(owned, Map.of("installer", "NeoForbric", "schemaVersion", 1, "state", "complete",
                        "minecraft", "1.21.1", "fabricLoader", payload.get("fabricLoader").getAsString(), "installedAt", Instant.now().toString()));
                progress.accept("安装完成：" + versionJson);
                return versionJson;
            }
        }
    }
    static void validateId(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,100}") || Set.of("CON", "PRN", "AUX", "NUL", "COM1", "LPT1").contains(id.toUpperCase(Locale.ROOT)))
            throw new IOException("版本名称只能包含英文字母、数字、点、下划线、加号和短横线。");
    }
    static Path contained(Path root, String name) throws IOException {
        Path relative = Path.of(name), target = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !target.startsWith(root.normalize())) throw new IOException("Invalid payload path: " + name);
        Path existing = target;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing != null && Files.exists(root) && !existing.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("Payload path follows a link outside its directory: " + name);
        return target;
    }
    private static byte[] resource(String name) throws IOException {
        try (InputStream stream = Installer.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("安装器缺少资源：" + name);
            return stream.readAllBytes();
        }
    }
    private static String digest(byte[] bytes, String algorithm) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String digest(Path path, String algorithm) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[65536]; int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void installResource(String name, Path target, String expected, boolean owned) throws IOException {
        byte[] bytes = resource(name);
        if (!digest(bytes, "SHA-256").equals(expected)) throw new IOException("安装器内置资源校验失败：" + name);
        if (Files.isRegularFile(target) && digest(target, "SHA-256").equals(expected)) return;
        if (Files.exists(target) && !owned)
            throw new IOException("已有依赖库的校验值不同，请修复该库或选择其他 Minecraft 目录：" + target);
        atomicWrite(target, bytes);
    }
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".nf-install-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void writeJson(Path target, Object value) throws IOException {
        atomicWrite(target, (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void seedRuntime(Path source, Path target, Consumer<String> progress) throws IOException {
        Path actual = source.toRealPath();
        if (target.toAbsolutePath().normalize().startsWith(actual) || actual.startsWith(target.toAbsolutePath().normalize()))
            throw new IOException("Runtime cache and installation directory must be separate");
        progress.accept("复用已有游戏缓存，并重新校验所有文件……");
        for (String name : List.of("downloads", "libraries", "natives", "assets", "client-mojang.jar", "client-intermediary.jar", "runtime.json")) {
            Path input = actual.resolve(name);
            if (!Files.exists(input)) throw new IOException("游戏缓存不完整：" + input);
            if (Files.isDirectory(input)) {
                try (Stream<Path> paths = Files.walk(input)) {
                    for (Path path : paths.filter(Files::isRegularFile).toList()) {
                        if (!path.toRealPath().startsWith(actual)) throw new IOException("Cache contains an external link: " + path);
                        copyCache(path, contained(target, actual.relativize(path).toString()));
                    }
                }
            } else copyCache(input, target.resolve(name));
        }
    }
    private static void copyCache(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) return;
        try { Files.createLink(target, source); }
        catch (IOException | UnsupportedOperationException error) { Files.copy(source, target); }
    }
    private static void prepare(List<Path> libraries, Path runtime, Path minecraft, Consumer<String> progress) throws Exception {
        String classpath = String.join(File.pathSeparator, libraries.stream().map(Path::toString).toList());
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process process = new ProcessBuilder(java.toString(), "-Xmx2G", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dneoforbric.minecraftCache=" + minecraft, "-cp", classpath,
                "org.neoforbric.minecraft.ClientPreparation", runtime.toString()).redirectErrorStream(true).start();
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line; while ((line = reader.readLine()) != null) progress.accept(line);
        } finally {
            if (Thread.currentThread().isInterrupted() && process.isAlive()) process.destroyForcibly();
        }
        if (process.waitFor() != 0) throw new IOException("Minecraft 文件准备失败；请查看上方日志后重新安装。");
    }
    private static void publishAssets(Path root, Path runtime, String id, JsonObject vanilla) throws IOException {
        String index = vanilla.getAsJsonObject("assetIndex").get("id").getAsString();
        JsonObject assets = JsonParser.parseString(Files.readString(runtime.resolve("assets/indexes/" + index + ".json"))).getAsJsonObject();
        Set<String> hashes = new TreeSet<>();
        assets.getAsJsonObject("objects").entrySet().forEach(entry -> hashes.add(entry.getValue().getAsJsonObject().get("hash").getAsString()));
        for (String hash : hashes) {
            String path = "objects/" + hash.substring(0, 2) + "/" + hash;
            Path target = root.resolve("assets").resolve(path);
            if (Files.exists(target) && !digest(target, "SHA-1").equals(hash)) throw new IOException("Minecraft 资源缓存校验失败：" + target);
            copyCache(runtime.resolve("assets").resolve(path), target);
        }
        atomicWrite(root.resolve("assets/indexes/" + id + ".json"), Files.readAllBytes(runtime.resolve("assets/indexes/" + index + ".json")));
    }
    static JsonObject versionJson(Path root, Path version, String id, JsonObject payload, JsonObject vanilla) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id); json.addProperty("type", "release");
        json.addProperty("time", Instant.now().toString()); json.add("releaseTime", vanilla.get("releaseTime"));
        json.addProperty("mainClass", "org.neoforbric.bootstrap.InstalledClient"); json.addProperty("minimumLauncherVersion", 21);
        json.add("javaVersion", vanilla.getAsJsonObject("javaVersion").deepCopy());
        JsonObject index = vanilla.getAsJsonObject("assetIndex").deepCopy(); index.addProperty("id", id);
        json.add("assetIndex", index); json.addProperty("assets", id);
        JsonArray libraries = new JsonArray();
        for (JsonElement value : payload.getAsJsonArray("libraries")) {
            JsonObject library = value.getAsJsonObject(), artifact = new JsonObject(), item = new JsonObject(), downloads = new JsonObject();
            if (library.get("name").getAsString().startsWith("org.neoforbric:loader:")) continue;
            item.add("name", library.get("name"));
            for (String key : List.of("path", "sha1", "size")) artifact.add(key, library.get(key));
            String repository = library.get("name").getAsString().startsWith("net.fabricmc:") ? "https://maven.fabricmc.net/" : "https://repo.maven.apache.org/maven2/";
            artifact.addProperty("url", repository + library.get("path").getAsString());
            downloads.add("artifact", artifact); item.add("downloads", downloads); libraries.add(item);
        }
        json.add("libraries", libraries);
        JsonObject arguments = new JsonObject(); JsonArray game = new JsonArray(), jvm = new JsonArray();
        for (String value : List.of("--nf-runtime", version.resolve("neoforbric/runtime/runtime.json").toString(),
                "--nf-client-ui", version.resolve("neoforbric/client-ui.jar").toString(),
                "--username", "${auth_player_name}", "--version", "1.21.1", "--gameDir", "${game_directory}",
                "--assetsDir", "${assets_root}", "--assetIndex", "${assets_index_name}", "--uuid", "${auth_uuid}",
                "--accessToken", "${auth_access_token}", "--userType", "${user_type}", "--versionType", "${version_type}")) game.add(value);
        // Standard launchers consume these JVM arguments when constructing their Java command.
        for (String value : List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", "${classpath}")) jvm.add(value);
        arguments.add("game", game); arguments.add("jvm", jvm); json.add("arguments", arguments);
        return json;
    }
    static void updateProfiles(Path root, Path version, String id) throws IOException {
        List<Path> targets = new ArrayList<>();
        for (String name : List.of("launcher_profiles.json", "launcher_profiles_microsoft_store.json"))
            if (Files.exists(root.resolve(name))) targets.add(root.resolve(name));
        if (targets.isEmpty()) targets.add(root.resolve("launcher_profiles.json"));
        for (Path target : targets) {
            JsonObject document;
            try { document = Files.exists(target) ? JsonParser.parseString(Files.readString(target)).getAsJsonObject() : new JsonObject(); }
            catch (RuntimeException invalid) { throw new IOException("启动器配置无法解析，原文件已保留：" + target); }
            JsonObject profiles = document.has("profiles") ? document.getAsJsonObject("profiles") : new JsonObject();
            String key = "neoforbric-" + id;
            JsonObject profile = profiles.has(key) ? profiles.getAsJsonObject(key) : new JsonObject();
            profile.addProperty("name", "NeoForbric 1.21.1"); profile.addProperty("type", "custom"); profile.addProperty("lastVersionId", id);
            if (!profile.has("created")) profile.addProperty("created", Instant.now().toString());
            if (!profile.has("gameDir")) profile.addProperty("gameDir", version.toString());
            if (!profile.has("javaArgs")) profile.addProperty("javaArgs", "-Xmx3G -Dfile.encoding=UTF-8");
            profile.addProperty("javaDir", Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString());
            profiles.add(key, profile); document.add("profiles", profiles);
            if (Files.exists(target)) Files.copy(target, target.resolveSibling(target.getFileName() + ".neoforbric-backup-" + UUID.randomUUID()), StandardCopyOption.COPY_ATTRIBUTES);
            writeJson(target, document);
        }
    }
}
