package org.neoforbric.neoforge;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.neoforbric.loader.*;

/** Kernel discovery of mods and declared JarJar libraries. No native locator or launcher is invoked. */
public final class NeoForgeDiscovery {
    public record Result(List<Discovery.Candidate> mods, List<Archive> libraries) {}
    private record Nested(String coordinate, String version, String range, Archive archive) {}
    private final Path cache;
    private final AuditLog audit;
    private final Map<String, List<Nested>> requests = new TreeMap<>();
    private final Set<String> traversed = new HashSet<>();
    private final List<Archive> packagedLibraries = new ArrayList<>();
    private NeoForgeDiscovery(Path cache, AuditLog audit) { this.cache = cache; this.audit = audit; }
    public static Result discover(Path directory, AuditLog audit) throws IOException {
        if (!Files.isDirectory(directory)) throw new Failure("MOD_DIRECTORY", "Missing mod directory: " + directory);
        var discovery = new NeoForgeDiscovery(directory.toAbsolutePath().getParent().resolve(".neoforbric/neoforge-nested"), audit);
        Files.createDirectories(discovery.cache);
        List<Archive> roots = new ArrayList<>();
        List<Archive> locatorLibraries = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                Archive archive = Archive.read(path).java21View(audit);
                Archive runtime = discovery.locatorRuntime(archive);
                if (runtime != null) { locatorLibraries.add(archive); roots.add(runtime); discovery.nested(archive); discovery.nested(runtime); }
                else { roots.add(archive); discovery.nested(archive); }
            }
        }
        // Native ports preserve NeoForge's events and patched interfaces; Fabric's
        // passive resolver substitutes only the aliases explicitly declared by them.
        if (!Boolean.getBoolean("neoforbric.fabric.plain") && roots.stream().noneMatch(archive ->
                archive.names().contains("META-INF/neoforge.mods.toml") && NeoForgeMetadata.read(archive).mods().stream().anyMatch(mod -> mod.id().equals("fabric_api")))
                && roots.stream().anyMatch(NeoForgeDiscovery::needsFabricApi)) {
            Archive api;
            try { api = org.neoforbric.minecraft.NeoForgeFabricApi.prepare(discovery.cache.resolve("fabric-api"), audit); }
            catch (Exception error) { throw new Failure("FABRIC_NATIVE_API", "Cannot prepare the pinned NeoForge Fabric API implementation", error); }
            roots.add(api); discovery.nested(api);
        }
        List<Archive> selected = new ArrayList<>(roots);
        for (var entry : discovery.requests.entrySet()) {
            List<Nested> choices = entry.getValue();
            Nested choice = choices.stream().filter(candidate -> choices.stream().allMatch(request -> MavenVersions.matches(request.range(), candidate.version())))
                    .max(Comparator.comparing(candidate -> new DefaultArtifactVersion(candidate.version())))
                    .orElseThrow(() -> new Failure("JARJAR_VERSION", "No common version for " + entry.getKey() + ": " + choices.stream().map(n -> n.range() + " -> " + n.version()).toList()));
            selected.add(choice.archive());
            audit.record("DISCOVER", "jarjar-selected", entry.getKey(), Map.of("version", choice.version(), "sha256", choice.archive().hash()));
        }
        List<Discovery.Candidate> mods = new ArrayList<>(); List<Archive> libraries = new ArrayList<>(locatorLibraries); Set<String> seen = new HashSet<>();
        for (Archive library : discovery.packagedLibraries) if (seen.add(library.hash())) libraries.add(library);
        Set<String> rootIds = new HashSet<>();
        for (Archive root : roots) {
            if (hasDescriptor(root)) selectedMetadata(root).forEach(mod -> rootIds.add(mod.id()));
        }
        for (Archive archive : selected) {
            if (!seen.add(archive.hash())) continue;
            if (archive.names().contains("META-INF/neoforge.mods.toml")) {
                for (Metadata metadata : NeoForgeMetadata.read(archive).mods()) {
                    if (!roots.contains(archive) && rootIds.contains(metadata.id())) {
                        audit.record("DISCOVER", "jarjar-root-provided", metadata.id(), Map.of("nestedVersion", metadata.version())); continue;
                    }
                    mods.add(new Discovery.Candidate(archive, metadata));
                    audit.record("DISCOVER", "mod-discovered", metadata.id(), Map.of("source", archive.path().toString(), "sha256", archive.hash(), "ecosystem", "NEOFORGE", "descriptor", "META-INF/neoforge.mods.toml", "version", metadata.version()));
                }
            } else {
                var manifest = new Manifest(new ByteArrayInputStream(Objects.requireNonNullElse(archive.read("META-INF/MANIFEST.MF"), new byte[0])));
                String type = manifest.getMainAttributes().getValue("FMLModType");
                if (Set.of("LIBRARY", "GAMELIBRARY", "LANGPROVIDER").contains(Objects.requireNonNullElse(type, ""))
                        || (!roots.contains(archive) && !hasDescriptor(archive))) {
                    libraries.add(archive); audit.record("DISCOVER", "neoforge-library", archive.path().toString(), Map.of("sha256", archive.hash()));
                } else for (Metadata metadata : Metadata.read(archive)) {
                    if (!roots.contains(archive) && rootIds.contains(metadata.id())) {
                        audit.record("DISCOVER", "jarjar-root-provided", metadata.id(), Map.of("nestedVersion", metadata.version())); continue;
                    }
                    mods.add(new Discovery.Candidate(archive, metadata));
                    audit.record("DISCOVER", "mod-discovered", metadata.id(), Map.of("source", archive.path().toString(),
                            "sha256", archive.hash(), "ecosystem", metadata.ecosystem().name(),
                            "descriptor", Metadata.descriptor(archive), "version", metadata.version()));
                }
            }
        }
        Map<String, List<Discovery.Candidate>> byId = new TreeMap<>();
        mods.forEach(mod -> byId.computeIfAbsent(mod.metadata().id(), ignored -> new ArrayList<>()).add(mod));
        Set<String> excludedNested = new HashSet<>();
        for (var entry : byId.entrySet()) if (entry.getValue().size() > 1 && !rootIds.contains(entry.getKey())) {
            List<Nested> aliases = discovery.requests.values().stream().flatMap(Collection::stream)
                    .filter(nested -> hasDescriptor(nested.archive())
                            && selectedMetadata(nested.archive()).stream().anyMatch(mod -> mod.id().equals(entry.getKey()))).toList();
            var winner = entry.getValue().stream().filter(candidate -> aliases.stream().allMatch(alias -> MavenVersions.matches(alias.range(), candidate.metadata().version())))
                    .max(Comparator.comparing(candidate -> new DefaultArtifactVersion(candidate.metadata().version())))
                    .orElseThrow(() -> new Failure("JARJAR_VERSION", "Conflicting nested aliases for mod " + entry.getKey()));
            for (var candidate : entry.getValue()) if (candidate != winner) excludedNested.add(candidate.archive().hash());
            audit.record("DISCOVER", "jarjar-mod-alias-selected", entry.getKey(), Map.of("version", winner.metadata().version(), "sha256", winner.archive().hash()));
        }
        return new Result(mods.stream().filter(mod -> !excludedNested.contains(mod.archive().hash())).toList(), List.copyOf(libraries));
    }
    private static boolean needsFabricApi(Archive archive) {
        if (archive.read("fabric.mod.json") == null || archive.read("META-INF/neoforge.mods.toml") != null) return false;
        JsonObject metadata = FabricJson.metadata(archive);
        if (metadata.get("id").getAsString().equals("fabric-api")) return true;
        return metadata.has("depends") && metadata.getAsJsonObject("depends").keySet().stream().anyMatch(id -> id.startsWith("fabric-"));
    }
    private static boolean hasDescriptor(Archive archive) {
        return java.util.stream.Stream.of("neoforbric.mod.json", "fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")
                .anyMatch(archive.names()::contains);
    }
    private static List<Metadata> selectedMetadata(Archive archive) {
        return archive.names().contains("META-INF/neoforge.mods.toml") ? NeoForgeMetadata.read(archive).mods() : Metadata.read(archive);
    }
    /** Passive equivalent of Crash Assistant's locator. Its separate helper application remains a resource. */
    private Archive locatorRuntime(Archive owner) throws IOException {
        byte[] candidateService = owner.read("META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator");
        if (hasService(candidateService, "net.caffeinemc.mods.sodium.service.SodiumServiceModLocator")) {
            var expected = NeoForgeMetadata.read(owner).mods().stream().collect(java.util.stream.Collectors.toMap(Metadata::id, Metadata::version));
            byte[] metadata = Objects.requireNonNull(owner.read("META-INF/jarjar/metadata.json"), "Sodium wrapper must declare its runtime");
            for (var entry : JsonParser.parseString(new String(metadata, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("jars")) {
                String path = entry.getAsJsonObject().get("path").getAsString();
                Archive child = materialize(owner, path);
                if (!child.names().contains("META-INF/neoforge.mods.toml")) continue;
                var actual = NeoForgeMetadata.read(child).mods().stream().collect(java.util.stream.Collectors.toMap(Metadata::id, Metadata::version));
                if (!expected.equals(actual)) continue;
                audit.record("DISCOVER", "neoforge-locator-runtime", owner.path().toString(), Map.of("ownerSha256", owner.hash(), "nestedPath", path, "runtimeSha256", child.hash()));
                return child;
            }
            throw new Failure("JARJAR_PATH", "Sodium wrapper has no matching runtime: " + owner.path());
        }
        byte[] service = owner.read("META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator");
        if (!hasService(service, "dev.kostromdan.mods.crash_assistant.core_mod.services.CrashAssistantDependencyLocator")) return null;
        String path = "META-INF/jarjar/crash_assistant-neoforge.jar";
        Archive child = materialize(owner, path);
        audit.record("DISCOVER", "neoforge-locator-runtime", owner.path().toString(), Map.of("ownerSha256", owner.hash(), "nestedPath", path, "runtimeSha256", child.hash()));
        return child;
    }
    private static boolean hasService(byte[] bytes, String name) {
        return bytes != null && new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines()
                .map(line -> line.split("#", 2)[0].trim()).anyMatch(name::equals);
    }
    private Archive materialize(Archive owner, String path) throws IOException {
        if (path.startsWith("/") || path.contains("\\") || Arrays.stream(path.split("/", -1)).anyMatch(part -> Set.of("", ".", "..").contains(part)))
            throw new Failure("JARJAR_PATH", "Invalid nested path " + path + " in " + owner.path());
        byte[] bytes = owner.read(path); if (bytes == null) throw new Failure("JARJAR_PATH", "Missing declared nested jar " + path + " in " + owner.path());
        String hash = Archive.sha256(bytes); Path materialized = cache.resolve(hash + ".jar");
        if (!Files.exists(materialized) || !Archive.sha256(Files.readAllBytes(materialized)).equals(hash)) Files.write(materialized, bytes);
        return Archive.read(materialized).java21View(audit);
    }
    private void nested(Archive owner) throws IOException {
        if (!traversed.add(owner.hash())) return;
        byte[] packaged = owner.read("META-INF/packageddependencies.json");
        if (packaged != null) for (var entry : JsonParser.parseString(new String(packaged, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("paths")) {
            String path = entry.getAsString(); Archive library = materialize(owner, path);
            packagedLibraries.add(library);
            audit.record("DISCOVER", "neoforge-packaged-library", owner.path().toString(), Map.of("ownerSha256", owner.hash(), "nestedPath", path, "librarySha256", library.hash()));
            nested(library);
        }
        byte[] metadata = owner.read("META-INF/jarjar/metadata.json"); if (metadata == null) return;
        for (JsonElement entry : JsonParser.parseString(new String(metadata, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("jars")) {
            var jar = entry.getAsJsonObject(); String path = jar.get("path").getAsString();
            Archive child = materialize(owner, path);
            var identifier = jar.getAsJsonObject("identifier"); var version = jar.getAsJsonObject("version");
            String coordinate = identifier.get("group").getAsString() + ":" + identifier.get("artifact").getAsString();
            requests.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(new Nested(coordinate, version.get("artifactVersion").getAsString(), version.get("range").getAsString(), child));
            nested(child);
        }
    }
}
