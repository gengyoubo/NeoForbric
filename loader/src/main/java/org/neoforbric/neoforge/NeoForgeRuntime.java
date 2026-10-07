package org.neoforbric.neoforge;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.neoforbric.loader.*;
import org.neoforbric.minecraft.*;

/** Kernel-side plan; typed NeoForge services live exclusively in the game domain. */
public final class NeoForgeRuntime implements AutoCloseable {
    private final Path root, bridge, game, universal;
    private final JsonObject plan;
    private Path selectedGame;
    private NeoForgeAccessTransformers access;
    private final List<Path> snapshots = new ArrayList<>();
    private java.lang.reflect.Method closeBridge;
    private NeoForgeMixins mixins;
    private java.lang.reflect.Method transformEnums;
    private List<Archive> boundArchives = List.of();
    public void install(TransformPipeline pipeline, AuditLog audit) {
        install(pipeline, audit, null);
    }
    public void install(TransformPipeline pipeline, AuditLog audit, org.neoforbric.fabric.NativeFabricRuntime fabric) {
        Set<String> preceding = pipeline.registeredIds();
        pipeline.add(new TransformPipeline.Transformer() {
            public String id() { return "neoforge-enum-extension"; }
            public Set<String> after() { return preceding; }
            public byte[] transform(TransformPipeline.Context context, byte[] bytes) throws Exception {
                try { return transformEnums == null ? bytes : (byte[])transformEnums.invoke(null, context.name(), bytes); }
                catch (java.lang.reflect.InvocationTargetException error) {
                    if (error.getCause() instanceof Exception cause) throw cause;
                    if (error.getCause() instanceof Error fatal) throw fatal;
                    throw error;
                }
            }
        });
        mixins = new NeoForgeMixins(pipeline, audit, fabric);
    }
    public GameClassLoader.Generated generated(String name, ClassIndex index) { return mixins.generated(name, index); }
    public void bind(ClassIndex index, GameClassLoader loader, TransformPipeline pipeline, List<Archive> archives) { boundArchives = List.copyOf(archives); mixins.bind(index, loader, pipeline, archives); }
    public NeoForgeRuntime(Path plan, Path bridge) throws Exception {
        this.plan = NeoForgePreparation.verify(plan);
        root = plan.toAbsolutePath().normalize().getParent(); this.bridge = bridge.toRealPath();
        game = input("game"); universal = input("neoforge"); selectedGame = game;
    }
    private Path input(String role) {
        return plan.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(f -> f.get("role").getAsString().equals(role)).map(f -> root.resolve(f.get("path").getAsString())).findFirst().orElseThrow();
    }
    public Path bridge() { return bridge; }
    public boolean registryContract() { return Boolean.getBoolean("neoforbric.neoforge.registryContract"); }
    public void phase(GameClassLoader loader, String phase) {
        try { Class.forName("org.neoforbric.neoforge.runtime.NativeNeoForgeRuntime", true, loader).getMethod("phase", String.class).invoke(null, phase); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new Failure("NEOFORGE_PHASE", phase, error.getCause());
        } catch (ReflectiveOperationException error) { throw new Failure("NEOFORGE_PHASE", phase, error); }
    }
    public void transformers(TransformPipeline pipeline, List<Discovery.Candidate> mods) throws Exception {
        List<Path> dependencies = new ArrayList<>();
        for (JsonElement value : plan.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject();
            if (file.get("role").getAsString().equals("library")) dependencies.add(root.resolve(file.get("path").getAsString()));
        }
        List<Path> rules = new ArrayList<>();
        List<Archive> archives = new ArrayList<>(); if (!registryContract()) archives.add(Archive.read(universal));
        mods.stream().filter(ForgeNeoForgeCompatibility::nativeMod).map(Discovery.Candidate::archive).forEach(archives::add);
        for (Archive archive : archives) {
            List<String> names = new ArrayList<>();
            byte[] descriptor = archive.read("META-INF/neoforge.mods.toml");
            if (descriptor != null) {
                names.addAll(NeoForgeMetadata.read(archive).accessTransformers());
            }
            for (int i = 0; i < names.size(); i++) {
                byte[] config = archive.read(names.get(i));
                if (config == null) throw new Failure("NEOFORGE_AT", "Missing access transformer " + names.get(i) + " in " + archive.path());
                Path path = root.resolve("at-" + archive.hash() + "-" + i + ".cfg"); Files.write(path, config); rules.add(path);
            }
        }
        access = new NeoForgeAccessTransformers(dependencies, rules); pipeline.add(access);
    }
    @Override public void close() throws Exception {
        try {
            if (closeBridge != null) closeBridge.invoke(null);
        } finally {
            try {
                for (Path snapshot : snapshots) Files.deleteIfExists(snapshot);
            } finally {
                if (access != null) access.close();
            }
        }
    }
    public Archive library(Path path) throws Exception {
        Archive archive = Archive.read(path);
        if (path.toRealPath().equals(universal.toRealPath())) {
            JsonObject metadata = JsonParser.parseString(new String(archive.read("META-INF/jarjar/metadata.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> nested = new HashSet<>();
            for (JsonElement value : metadata.getAsJsonArray("jars")) nested.add(value.getAsJsonObject().get("path").getAsString());
            return archive.permitDeclaredNested(nested);
        }
        return archive;
    }
    public RuntimeInputs inputs(RuntimeInputs vanilla) throws Exception {
        Map<String, Path> libraries = new LinkedHashMap<>();
        // Keep vanilla library ordering, replacing only exact group/module identities.
        JsonObject vanillaPlan = JsonParser.parseString(Files.readString(vanilla.game().getParent().resolve("runtime.json"))).getAsJsonObject();
        for (JsonElement value : vanillaPlan.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject();
            if (file.get("role").getAsString().equals("library")) libraries.put(key(file.get("coordinate").getAsString()), vanilla.game().getParent().resolve(file.get("path").getAsString()));
        }
        for (JsonElement value : plan.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject();
            String coordinate = file.get("coordinate").getAsString();
            if (coordinate.startsWith("org.ow2.asm:")) continue; // The kernel already owns the pinned ASM 9.10.1 SPI.
            if (Set.of("library", "neoforge").contains(file.get("role").getAsString())) libraries.put(key(coordinate), root.resolve(file.get("path").getAsString()));
        }
        selectedGame = registryContract() ? vanilla.game() : game;
        var archive = Archive.readRuntimeGame(selectedGame);
        return new RuntimeInputs(selectedGame, vanilla.intermediaryGame(), vanilla.mappings(), vanilla.intermediaryMappings(),
                new ArrayList<>(libraries.values()), Archive.sha256(archive.read("net/minecraft/core/registries/BuiltInRegistries.class")),
                "client", vanilla.assets(), vanilla.natives());
    }
    private static String key(String coordinate) {
        String[] parts = coordinate.split(":"); return parts[0] + ":" + parts[1];
    }
    public void prepare(GameClassLoader loader, Path directory, List<Discovery.Candidate> mods, Map<String, Set<String>> predecessors, List<Archive> libraries) throws Exception {
        List<Path> paths = new ArrayList<>(); Set<String> seen = new HashSet<>();
        Map<Path, java.util.function.Consumer<java.util.function.Consumer<byte[]>>> scans = new HashMap<>();
        Path snapshotDirectory = root.resolve("mod-snapshots"); Files.createDirectories(snapshotDirectory);
        for (var mod : mods) if (ForgeNeoForgeCompatibility.nativeMod(mod) && seen.add(mod.archive().hash())) {
            Path snapshot = snapshotDirectory.resolve(mod.archive().hash() + ".jar");
            if (!Files.exists(snapshot) || !Archive.sha256(Files.readAllBytes(snapshot)).equals(mod.archive().hash())) {
                Path temporary = Files.createTempFile(snapshotDirectory, "snapshot-", ".part");
                try { mod.archive().writeSnapshot(temporary); Files.move(temporary, snapshot, StandardCopyOption.REPLACE_EXISTING); }
                finally { Files.deleteIfExists(temporary); }
            }
            paths.add(snapshot);
            scans.put(snapshot, consumer -> scanClasses(mod.archive(), consumer));
        }
        String version = plan.get("neoforge").getAsString();
        Path originalUniversal = root.resolve("maven/net/neoforged/neoforge/" + version + "/neoforge-" + version + "-universal.jar");
        var universalEntry = plan.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(f -> f.get("role").getAsString().equals("neoforge")).findFirst().orElseThrow();
        if (!Archive.sha256(Files.readAllBytes(originalUniversal)).equals(universalEntry.get("originalSha256").getAsString()))
            throw new Failure("INPUT_CHECKSUM", "Original NeoForge metadata archive differs");
        scans.put(selectedGame, consumer -> scanClasses(boundArchives.stream().filter(archive -> archive.path().equals(selectedGame)).findFirst().orElseThrow(), consumer));
        scans.put(originalUniversal, consumer -> scanClasses(boundArchives.stream().filter(archive -> archive.path().equals(universal)).findFirst().orElseThrow(), consumer));
        var type = Class.forName("org.neoforbric.neoforge.runtime.NeoForgeBridge", true, loader);
        closeBridge = type.getMethod("close");
        var ids = mods.stream().filter(ForgeNeoForgeCompatibility::nativeMod).map(mod -> mod.metadata().id()).toList();
        Map<String, List<String>> entrypoints = new TreeMap<>(); Set<String> scanned = new HashSet<>();
        for (var mod : mods) if (ForgeNeoForgeCompatibility.nativeMod(mod) && scanned.add(mod.archive().hash()) && NeoForgeMetadata.read(mod.archive()).modLoader().equals("javafml")) entrypoints.putAll(NeoForgeEntrypoints.scan(mod.archive(), "client"));
        @SuppressWarnings("unchecked")
        List<String> configs = (List<String>)type.getMethod("prepare", Path.class, Path.class, Path.class, List.class, ClassLoader.class, List.class, Map.class, Map.class, boolean.class, List.class, Map.class)
                .invoke(null, directory, selectedGame, originalUniversal, paths, loader, ids, predecessors, entrypoints, registryContract(), libraries.stream().map(Archive::path).toList(), scans);
        var enumType = Class.forName("org.neoforbric.neoforge.runtime.NativeNeoForgeTransforms", true, loader);
        transformEnums = enumType.getMethod("transform", String.class, byte[].class);
        Set<String> allConfigs = new LinkedHashSet<>(configs);
        for (Archive archive : boundArchives) {
            if (archive.path().equals(selectedGame) || archive.path().equals(universal)) continue;
            byte[] manifest = archive.read("META-INF/MANIFEST.MF"); if (manifest == null) continue;
            String declared = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(manifest)).getMainAttributes().getValue("MixinConfigs");
            if (declared == null) continue;
            for (String config : declared.split(",")) if (!config.isBlank()) {
                config = config.trim();
                if (archive.read(config) == null) throw new Failure("NEOFORGE_MIXIN", "Missing manifest Mixin config " + config + " in " + archive.path());
                allConfigs.add(config);
            }
        }
        mixins.start(List.copyOf(allConfigs));
        type.getMethod("finish", Map.class, boolean.class).invoke(null, entrypoints, registryContract());
    }
    private static void scanClasses(Archive archive, java.util.function.Consumer<byte[]> consumer) {
        for (String name : new TreeSet<>(archive.names())) if (name.endsWith(".class")) consumer.accept(archive.read(name));
    }
}
