package org.neoforbric.loader;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.metadata.*;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.neoforbric.forge.*;
import org.neoforbric.neoforge.*;

/** Passive local dependency closure. Never defines mod classes or calls Minecraft. */
public final class BenchmarkDependencies {
    private record Requirement(String owner, String id, String range, Predicate<String> matches, boolean optional, boolean incompatible) {
        String explanation() { return owner + " -> " + id + " " + range; }
    }
    private record Mod(String id, String version, Set<String> aliases, boolean root, List<Requirement> requirements) {
        boolean provides(String name) { return id.equals(name) || aliases.contains(name); }
    }
    private record Bundle(Path path, List<Mod> mods) {}
    @FunctionalInterface private interface Reader { byte[] read(String name) throws Exception; }
    private final Path scratch;
    private final Map<String, Bundle> cache = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private int attempts;
    private String problem;

    public BenchmarkDependencies(Path scratch) { this.scratch = scratch; }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("BenchmarkDependencies <request.json> <result.json>");
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        var planner = new BenchmarkDependencies(output.getParent().resolve("metadata"));
        JsonObject request = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        List<Path> pool = request.getAsJsonArray("pool").asList().stream().map(v -> Path.of(v.getAsString())).toList();
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonElement value : request.getAsJsonArray("cases")) {
            var entry = value.getAsJsonObject();
            List<Path> explicit = entry.getAsJsonArray("inputs").asList().stream()
                    .map(v -> Path.of(v.getAsJsonObject().get("path").getAsString())).toList();
            Map<String, Object> result = new LinkedHashMap<>(planner.resolve(explicit, pool, entry.get("profile").getAsString()));
            result.put("case_id", entry.get("case_id").getAsString());
            results.add(result);
        }
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("schema_version", 1, "cases", results, "pool_warnings", planner.errors)) + "\n");
    }

    public Map<String, Object> resolve(List<Path> explicit, List<Path> pool, String profile) throws Exception {
        attempts = 0;
        problem = "No consistent dependency closure";
        List<Bundle> fixed = new ArrayList<>(), available = new ArrayList<>();
        for (Path path : explicit) {
            Bundle bundle = bundle(path, profile);
            if (bundle == null) return rejected("INPUT_METADATA", errors.get(path + ":" + profile));
            fixed.add(bundle);
        }
        for (Path path : pool) {
            if (explicit.contains(path)) continue;
            Bundle bundle = bundle(path, profile);
            if (bundle != null) available.add(bundle);
        }
        var builtin = new HashMap<String, String>();
        builtin.put("java", "21.0.0"); builtin.put("minecraft", "1.21.1");
        builtin.put("fabricloader", net.fabricmc.loader.impl.FabricLoaderImpl.VERSION);
        builtin.put("neoforbric", LoaderVersion.VERSION);
        if (profile.equals("neoforge")) {
            var lock = org.neoforbric.minecraft.NeoForgePreparation.lock();
            builtin.put("neoforge", lock.get("neoforge").getAsString());
            builtin.put("javafml", lock.get("fml").getAsString()); builtin.put("forge", "52.1.0");
        } else if (profile.equals("forge")) {
            builtin.put("forge", "52.1.0"); builtin.put("javafml", "52.1.0"); builtin.put("lowcodefml", "52.1.0");
        }
        List<Bundle> resolved = search(fixed, available, builtin);
        if (resolved == null) return rejected("INPUT_DEPENDENCY", problem);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Bundle bundle : resolved) selected.add(Map.of("path", bundle.path().toString(),
                "mods", bundle.mods().stream().map(mod -> Map.of("id", mod.id(), "version", mod.version(), "embedded", !mod.root())).toList(),
                "required", bundle.mods().stream().flatMap(mod -> mod.requirements().stream()).filter(r -> !r.optional() && !r.incompatible()).map(Requirement::explanation).toList()));
        return Map.of("status", "READY", "inputs", selected, "reason", "Recursive required dependency closure complete", "failure_code", "");
    }

    private static Map<String, Object> rejected(String code, String reason) {
        return Map.of("status", "INPUT_ERROR", "failure_code", code, "reason", Objects.requireNonNullElse(reason, "Unreadable metadata"), "inputs", List.of());
    }

    private List<Bundle> search(List<Bundle> selected, List<Bundle> available, Map<String, String> builtin) {
        if (++attempts > 10000) { problem = "Dependency search exceeded 10000 branches; pin versions in dependencies column"; return null; }
        Map<String, Path> rootIds = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (Bundle bundle : selected) {
            if (!names.add(bundle.path().getFileName().toString().toLowerCase(Locale.ROOT))) {
                problem = "Input filename collision: " + bundle.path().getFileName(); return null;
            }
            for (Mod mod : bundle.mods()) if (mod.root()) {
                Path previous = rootIds.putIfAbsent(mod.id(), bundle.path());
                if (previous != null && !previous.equals(bundle.path())) { problem = "Duplicate explicit mod ID " + mod.id(); return null; }
            }
        }
        Map<String, List<Requirement>> required = new TreeMap<>();
        for (Bundle bundle : selected) for (Mod mod : bundle.mods()) for (Requirement requirement : mod.requirements()) {
            List<Mod> providers = providers(selected, requirement.id());
            String builtVersion = builtin.get(requirement.id());
            if (requirement.incompatible()) {
                if ((builtVersion != null && requirement.matches().test(builtVersion)) || providers.stream().anyMatch(p -> requirement.matches().test(p.version()))) {
                    problem = "Incompatible declared inputs: " + requirement.explanation(); return null;
                }
            } else if (!requirement.optional() || builtVersion != null || !providers.isEmpty())
                required.computeIfAbsent(requirement.id(), ignored -> new ArrayList<>()).add(requirement);
        }
        for (var entry : required.entrySet()) {
            String id = entry.getKey(); List<Requirement> constraints = entry.getValue();
            if (builtin.containsKey(id)) {
                if (constraints.stream().allMatch(r -> r.matches().test(builtin.get(id)))) continue;
                problem = "Builtin version mismatch: " + id + "=" + builtin.get(id) + "; " + explanations(constraints); return null;
            }
            List<Mod> providers = providers(selected, id);
            if (providers.stream().anyMatch(p -> constraints.stream().allMatch(r -> r.matches().test(p.version())))) continue;
            List<Bundle> choices = available.stream().filter(bundle -> !selected.contains(bundle)
                    && bundle.mods().stream().anyMatch(mod -> mod.provides(id) && constraints.stream().allMatch(r -> r.matches().test(mod.version()))))
                    .sorted(Comparator.<Bundle, DefaultArtifactVersion>comparing(bundle -> bundle.mods().stream().filter(m -> m.provides(id))
                            .map(m -> new DefaultArtifactVersion(m.version())).max(Comparator.naturalOrder()).orElseThrow()).reversed()
                            .thenComparing(bundle -> bundle.path().toString())).toList();
            if (choices.isEmpty()) { problem = "Missing required dependency or incompatible version: " + explanations(constraints); return null; }
            for (Bundle choice : choices) {
                List<Bundle> next = new ArrayList<>(selected); next.add(choice);
                List<Bundle> result = search(next, available, builtin);
                if (result != null) return result;
            }
            return null;
        }
        return List.copyOf(selected);
    }

    private static String explanations(List<Requirement> constraints) { return String.join("; ", constraints.stream().map(Requirement::explanation).toList()); }
    private static List<Mod> providers(List<Bundle> selected, String id) {
        List<Mod> matches = selected.stream().flatMap(bundle -> bundle.mods().stream()).filter(mod -> mod.provides(id)).toList();
        // An explicit root version overrides copies embedded in other JARs.
        List<Mod> roots = matches.stream().filter(Mod::root).toList();
        return roots.isEmpty() ? matches : roots;
    }

    private Bundle bundle(Path path, String profile) {
        String key = path + ":" + profile;
        if (cache.containsKey(key)) return cache.get(key);
        if (errors.containsKey(key)) return null;
        try (JarFile jar = new JarFile(path.toFile())) {
            List<Mod> mods = new ArrayList<>();
            read(path, name -> {
                var entry = jar.getJarEntry(name);
                if (entry == null) return null;
                try (InputStream input = jar.getInputStream(entry)) { return bounded(input); }
            }, profile, true, 0, mods);
            if (mods.stream().noneMatch(Mod::root)) throw new IllegalArgumentException("No client mod descriptor for profile " + profile);
            Bundle bundle = new Bundle(path, List.copyOf(mods)); cache.put(key, bundle); return bundle;
        } catch (Exception invalid) { errors.put(key, path + ": " + invalid.getMessage()); return null; }
    }

    private static byte[] bounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(Archive.MAX_ENTRY + 1);
        if (bytes.length > Archive.MAX_ENTRY) throw new IOException("Metadata/nested entry exceeds NF archive bounds");
        return bytes;
    }

    private void read(Path source, Reader reader, String profile, boolean root, int depth, List<Mod> mods) throws Exception {
        if (depth > 8 || mods.size() > 1024) throw new IllegalArgumentException("Nested dependency metadata limit exceeded");
        Map<String, byte[]> entries = new HashMap<>();
        for (String name : List.of("neoforbric.mod.json", "fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "META-INF/MANIFEST.MF")) {
            byte[] bytes = reader.read(name); if (bytes != null) entries.put(name, bytes);
        }
        List<String> nested = new ArrayList<>();
        Archive archive = Archive.metadataSnapshot(source, entries);
        boolean neo = profile.equals("neoforge") && entries.containsKey("META-INF/neoforge.mods.toml");
        boolean forge = profile.equals("forge") && entries.containsKey("META-INF/mods.toml");
        if (!neo && !forge && entries.containsKey("fabric.mod.json") && !entries.containsKey("neoforbric.mod.json")) {
            Files.createDirectories(scratch);
            var metadata = ModMetadataParser.parseMetadata(new ByteArrayInputStream(entries.get("fabric.mod.json")), source.toString(), List.of(), new VersionOverrides(), new DependencyOverrides(scratch), false);
            if (metadata.loadsInEnvironment(EnvType.CLIENT)) {
                List<Requirement> requirements = new ArrayList<>();
                for (ModDependency dependency : metadata.getDependencies()) {
                    if (!Set.of(ModDependency.Kind.DEPENDS, ModDependency.Kind.BREAKS).contains(dependency.getKind())) continue;
                    requirements.add(new Requirement(metadata.getId(), dependency.getModId(), dependency.getVersionRequirements().toString(), version -> {
                        try { return dependency.matches(net.fabricmc.loader.api.Version.parse(version)); }
                        catch (net.fabricmc.loader.api.VersionParsingException error) { return false; }
                    }, false, dependency.getKind() == ModDependency.Kind.BREAKS));
                }
                mods.add(new Mod(metadata.getId(), metadata.getVersion().getFriendlyString(), Set.copyOf(metadata.getProvides()), root, requirements));
                metadata.getJars().forEach(jar -> nested.add(jar.getFile()));
            }
        } else if (neo || forge || entries.containsKey("neoforbric.mod.json") || entries.containsKey("META-INF/neoforge.mods.toml") || entries.containsKey("META-INF/mods.toml")) {
            var nativeMetadata = neo ? NeoForgeMetadata.read(archive) : null;
            List<Metadata> metadata = neo ? nativeMetadata.mods() : forge ? ForgeMetadata.read(archive).mods() : Metadata.read(archive);
            for (Metadata mod : metadata) if (mod.available("client")) {
                List<Requirement> requirements = new ArrayList<>();
                mod.depends().forEach((id, range) -> requirements.add(new Requirement(mod.id(), id, range, v -> Version.matches(range, v), false, false)));
                mod.optionalDepends().forEach((id, range) -> requirements.add(new Requirement(mod.id(), id, range, v -> Version.matches(range, v), true, false)));
                for (Dependency dep : mod.constraints()) if (dep.side().applies("client") && dep.kind() != Dependency.Kind.DISCOURAGED) {
                    Predicate<String> matches = mod.ecosystem() == Metadata.Ecosystem.FORGE
                            ? v -> ForgeVersionSupport.modMatches("1.21.1", mod.id(), dep.id(), dep.range(), v, new AuditLog())
                            : v -> MavenVersions.matches(dep.range(), v)
                                || (profile.equals("neoforge") && dep.id().equals("minecraft") && MavenVersions.matches(dep.range(), "1.21"))
                                || (profile.equals("neoforge") && dep.id().equals("neoforge") && MavenVersions.matches(dep.range(), "21.0.166"));
                    requirements.add(new Requirement(mod.id(), dep.id(), dep.range(), matches, dep.kind() == Dependency.Kind.OPTIONAL, dep.kind() == Dependency.Kind.INCOMPATIBLE));
                }
                mods.add(new Mod(mod.id(), mod.version(), nativeMetadata == null ? Set.of() : Set.copyOf(nativeMetadata.provides(mod.id())), root, requirements));
            }
        }
        byte[] jarjar = reader.read("META-INF/jarjar/metadata.json");
        if (jarjar != null) for (var entry : JsonParser.parseString(new String(jarjar, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("jars"))
            nested.add(entry.getAsJsonObject().get("path").getAsString());
        for (String name : new LinkedHashSet<>(nested)) {
            if (name.startsWith("/") || name.contains("\\") || name.contains(":") || Arrays.stream(name.split("/", -1)).anyMatch(part -> Set.of("", ".", "..").contains(part)))
                throw new IllegalArgumentException("Invalid nested path: " + name);
            byte[] bytes = reader.read(name);
            if (bytes == null) throw new IllegalArgumentException("Missing declared nested JAR: " + name);
            // Only copy descriptor entries from nested archives, never expand resources/classes.
            Map<String, byte[]> child = new HashMap<>();
            try (var stream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while ((entry = stream.getNextEntry()) != null) if (entry.getName().endsWith(".json") || entry.getName().endsWith(".toml")
                        || entry.getName().equals("META-INF/MANIFEST.MF") || entry.getName().endsWith(".jar"))
                    child.put(entry.getName(), bounded(stream));
            }
            read(Path.of(source + "!" + name), child::get, profile, false, depth + 1, mods);
        }
    }
}
