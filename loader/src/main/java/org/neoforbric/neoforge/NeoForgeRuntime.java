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
        mods.stream().filter(m -> m.metadata().ecosystem() == Metadata.Ecosystem.NEOFORGE).map(Discovery.Candidate::archive).forEach(archives::add);
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
    public void prepare(GameClassLoader loader, Path directory, List<Discovery.Candidate> mods, Map<String, Set<String>> predecessors) throws Exception {
        List<Path> paths = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (var mod : mods) if (mod.metadata().ecosystem() == Metadata.Ecosystem.NEOFORGE && seen.add(mod.archive().hash())) {
            Path snapshot = Files.createTempFile(root, "mod-snapshot-", ".jar"); snapshots.add(snapshot);
            mod.archive().writeSnapshot(snapshot); paths.add(snapshot);
        }
        Path originalUniversal = root.resolve("maven/net/neoforged/neoforge/21.1.244/neoforge-21.1.244-universal.jar");
        var universalEntry = plan.getAsJsonArray("files").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(f -> f.get("role").getAsString().equals("neoforge")).findFirst().orElseThrow();
        if (!Archive.sha256(Files.readAllBytes(originalUniversal)).equals(universalEntry.get("originalSha256").getAsString()))
            throw new Failure("INPUT_CHECKSUM", "Original NeoForge metadata archive differs");
        var type = Class.forName("org.neoforbric.neoforge.runtime.NeoForgeBridge", true, loader);
        closeBridge = type.getMethod("close");
        var ids = mods.stream().filter(mod -> mod.metadata().ecosystem() == Metadata.Ecosystem.NEOFORGE).map(mod -> mod.metadata().id()).toList();
        Map<String, List<String>> entrypoints = new TreeMap<>(); Set<String> scanned = new HashSet<>();
        for (var mod : mods) if (mod.metadata().ecosystem() == Metadata.Ecosystem.NEOFORGE && scanned.add(mod.archive().hash())) entrypoints.putAll(NeoForgeEntrypoints.scan(mod.archive(), "client"));
        type.getMethod("prepare", Path.class, Path.class, Path.class, List.class, ClassLoader.class, List.class, Map.class, Map.class, boolean.class)
                .invoke(null, directory, selectedGame, originalUniversal, paths, loader, ids, predecessors, entrypoints, registryContract());
    }
}
