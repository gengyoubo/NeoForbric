package org.neoforbric.forge;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.neoforbric.loader.*;
import org.neoforbric.minecraft.*;

/** Independent Forge input / service adapter; no NeoForge runtime implementation is reused. */
public final class ForgeRuntime implements AutoCloseable {
    private final Path root, bridge;
    private final JsonObject plan;
    private Class<?> nativeBridge;
    private Class<?> nativeRuntime;
    private ForgeMixins mixins;
    public ForgeRuntime(Path plan, Path bridge) throws Exception {
        this.plan = ForgePreparation.verify(plan); this.root = plan.toAbsolutePath().normalize().getParent(); this.bridge = bridge.toRealPath();
    }
    public Path bridge() { return bridge; }
    public void packageMetadata(GameClassLoader loader) throws Exception {
        Path universal = root.resolve("maven/net/minecraftforge/forge/1.21.1-52.1.0/forge-1.21.1-52.1.0-universal.jar");
        var entry = plan.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject).filter(file -> file.get("role").getAsString().equals("forge")).findFirst().orElseThrow();
        if (!Archive.sha256(Files.readAllBytes(universal)).equals(entry.get("originalSha256").getAsString())) throw new Failure("INPUT_CHECKSUM", "Original Forge manifest source differs");
        try (var jar = new JarFile(universal.toFile()); var stream = jar.getInputStream(jar.getJarEntry("META-INF/MANIFEST.MF"))) { loader.packageManifests(Map.of(root.resolve("libraries/forge-universal.jar"), stream.readAllBytes())); }
    }
    public List<Archive> inputs(Path vanillaPlan, AuditLog audit) throws Exception {
        RuntimeInputs vanilla = RuntimeInputs.read(vanillaPlan, audit);
        JsonObject vanillaJson = JsonParser.parseString(Files.readString(vanillaPlan)).getAsJsonObject();
        Map<String, Path> libraries = new LinkedHashMap<>();
        for (JsonElement value : vanillaJson.getAsJsonArray("files")) {
            var file = value.getAsJsonObject();
            if (file.get("role").getAsString().equals("library")) libraries.put(key(file.get("coordinate").getAsString()), vanillaPlan.toAbsolutePath().getParent().resolve(file.get("path").getAsString()));
        }
        Path game = null;
        for (JsonElement value : plan.getAsJsonArray("files")) {
            var file = value.getAsJsonObject(); String role = file.get("role").getAsString(), coordinate = file.get("coordinate").getAsString();
            Path path = root.resolve(file.get("path").getAsString());
            audit.record("PREPARE", "runtime-input", coordinate, Map.of("ecosystem", "FORGE", "role", role, "path", path.toString(), "sha256", file.get("sha256").getAsString()));
            if (role.equals("game")) game = path;
            if (Set.of("library", "forge").contains(role) && !coordinate.startsWith("org.ow2.asm:")) libraries.put(key(coordinate), path);
        }
        List<Archive> inputs = new ArrayList<>(); inputs.add(Archive.readRuntimeGame(Objects.requireNonNull(game)));
        for (Path path : libraries.values()) inputs.add(Archive.read(path));
        inputs.add(Archive.read(bridge)); return List.copyOf(inputs);
    }
    private static String key(String coordinate) { String[] parts = coordinate.split(":"); return parts[0] + ":" + parts[1]; }
    public void install(TransformPipeline pipeline) {
        install(pipeline, false);
    }
    public void install(TransformPipeline pipeline, boolean mods) {
        Set<String> structural = new HashSet<>(pipeline.registeredIds());
        structural.retainAll(Set.of("minecraft-1.21.1-client-lifecycle", "minecraft-1.21.1-title-screen-ui"));
        pipeline.add(new ForgeHostHook(structural));
        String previous = "forge-native-host";
        if (mods) { pipeline.add(new ForgeLifecycleHook()); previous = "forge-lifecycle"; }
        for (var stage : List.of(Map.entry("forge-native-plugins", "plugins"), Map.entry("forge-access-transformers", "access"), Map.entry("forge-coremods", "coremods"))) {
            String prerequisite = previous;
            pipeline.add(new TransformPipeline.Transformer() {
                public String id() { return stage.getKey(); }
                public Set<String> after() { return Set.of(prerequisite); }
                public byte[] transform(TransformPipeline.Context context, byte[] bytes) throws Exception {
                    return nativeBridge == null ? bytes : (byte[])invoke(nativeBridge.getMethod(stage.getValue(), String.class, byte[].class), context.name(), bytes);
                }
            }); previous = stage.getKey();
        }
    }
    public RuntimeInputs inputs(RuntimeInputs vanilla, Path vanillaPlan, AuditLog audit) throws Exception {
        var archives = inputs(vanillaPlan, audit);
        return new RuntimeInputs(archives.getFirst().path(), vanilla.intermediaryGame(), vanilla.mappings(), vanilla.intermediaryMappings(),
                archives.subList(1, archives.size() - 1).stream().map(Archive::path).toList(), Archive.sha256(archives.getFirst().read("net/minecraft/core/registries/BuiltInRegistries.class")), vanilla.side(), vanilla.assets(), vanilla.natives());
    }
    public List<Discovery.Candidate> remap(List<Discovery.Candidate> mods, AuditLog audit) throws Exception {
        Map<String, Archive> mapped = new HashMap<>(); var names = ForgeRemapper.names(root); List<Discovery.Candidate> result = new ArrayList<>();
        for (var mod : mods) { Archive archive = mapped.get(mod.archive().hash()); if (archive == null) { archive = ForgeRemapper.remap(mod.archive(), root.resolve("remapped-mods"), names, audit); mapped.put(mod.archive().hash(), archive); } result.add(new Discovery.Candidate(archive, mod.metadata())); }
        return List.copyOf(result);
    }
    public void mixins(TransformPipeline pipeline, AuditLog audit, String side) { mixins = new ForgeMixins(pipeline, audit, side); }
    public GameClassLoader.Generated generated(String name, ClassIndex index) {
        if (nativeBridge != null) try {
            @SuppressWarnings("unchecked") var generated = (Map<String, Object>)invoke(nativeBridge.getMethod("generated", String.class), name);
            if (generated != null) {
                var owner = index.entry((String)generated.get("owner"));
                if (owner == null) throw new Failure("FORGE_EVENT_WRAPPER", "No admitted owner for " + name);
                return new GameClassLoader.Generated((byte[])generated.get("bytes"), owner.archive(), "ForgeEventBus:" + generated.get("owner"));
            }
        } catch (Exception error) { throw new Failure("FORGE_EVENT_WRAPPER", name, error); }
        return mixins.generated(name, index);
    }
    public void prepareMods(ClassIndex index, GameClassLoader loader, TransformPipeline pipeline, List<Archive> inputs,
            Path directory, String side, List<Discovery.Candidate> mods, java.util.function.Consumer<String> observer) throws Exception {
        prepare(loader, directory, side);
        nativeRuntime = Class.forName("org.neoforbric.forge.runtime.NativeForgeRuntime", true, loader);
        invoke(nativeRuntime.getMethod("observer", java.util.function.Consumer.class), observer);
        List<Path> paths = new ArrayList<>(), rules = new ArrayList<>(); Set<String> seen = new HashSet<>(); Set<String> configs = new LinkedHashSet<>();
        Map<Path, java.util.function.Consumer<java.util.function.Consumer<byte[]>>> scans = new HashMap<>();
        for (Archive archive : inputs) scans.put(archive.path(), consumer -> { for (String resource : new TreeSet<>(archive.names())) if (resource.endsWith(".class")) consumer.accept(archive.read(resource)); });
        for (var mod : mods) if (seen.add(mod.archive().hash())) {
            var archive = mod.archive(); paths.add(archive.path());
            byte[] at = archive.read("META-INF/accesstransformer.cfg"); if (at != null) { Path rule = root.resolve("at-" + archive.hash() + ".cfg"); Files.write(rule, at); rules.add(rule); }
            byte[] manifest = archive.read("META-INF/MANIFEST.MF");
            if (manifest != null) { String declared = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(manifest)).getMainAttributes().getValue("MixinConfigs"); if (declared != null) for (String config : declared.split(",")) { config = config.trim(); if (config.isEmpty() || archive.read(config) == null) throw new Failure("FORGE_MIXIN", "Missing config " + config); configs.add(config); } }
        }
        invoke(nativeRuntime.getMethod("prepare", Path.class, Path.class, List.class, List.class, ClassLoader.class, Map.class, List.class), inputs.getFirst().path(), root.resolve("libraries/forge-universal.jar"), paths, mods.stream().map(m -> m.metadata().id()).toList(), loader, scans, rules);
        mixins.bind(index, loader, pipeline, inputs, side); mixins.start(List.copyOf(configs));
    }
    public void close() throws Exception { if (nativeRuntime != null) { var type = nativeRuntime; nativeRuntime = null; invoke(type.getMethod("close")); } }
    @SuppressWarnings("unchecked")
    public Map<String, Object> prepare(GameClassLoader loader, Path directory, String side) throws Exception {
        var type = Class.forName("org.neoforbric.forge.runtime.ForgeBridge", true, loader);
        Path access = root.resolve("forge-access.cfg");
        try (var jar = new JarFile(root.resolve("libraries/forge-universal.jar").toFile()); var stream = jar.getInputStream(jar.getJarEntry("META-INF/accesstransformer.cfg"))) { Files.write(access, stream.readAllBytes()); }
        Map<String, String> mapping = new HashMap<>();
        Path names = root.resolve("maven/de/oceanlabs/mcp/mcp_config/1.21.1-20240808.132146/mcp_config-1.21.1-20240808.132146-srg2off.jar");
        try (var jar = new JarFile(names.toFile())) {
            for (String resource : List.of("fields.csv", "methods.csv")) try (var input = jar.getInputStream(jar.getJarEntry(resource))) {
                for (String row : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).toList()) {
                    String[] parts = row.split(",", -1); if (parts.length >= 2) mapping.put(parts[0], parts[1]);
                }
            }
        }
        var result = (Map<String, Object>)invoke(type.getMethod("prepare", Path.class, String.class, Path.class, Path.class, Map.class), directory, side, root.resolve("libraries/forge-universal.jar"), access, mapping);
        nativeBridge = type; return result;
    }
    public static Object invoke(Method method, Object... arguments) throws Exception {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception error) throw error;
            if (failed.getCause() instanceof Error error) throw error;
            throw failed;
        }
    }
}
