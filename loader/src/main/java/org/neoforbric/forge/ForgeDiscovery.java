package org.neoforbric.forge;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.neoforbric.loader.*;

/** Passive Forge descriptor/JarJar discovery; no native locator service executes. */
public final class ForgeDiscovery {
    public record Result(List<Discovery.Candidate> mods, List<Archive> libraries) {}
    private record Nested(String version, String range, Archive archive) {}
    private final Map<String, List<Nested>> requests = new TreeMap<>();
    private final Set<String> visited = new HashSet<>();
    private final Path cache;
    private ForgeDiscovery(Path mods) throws Exception { cache = mods.toAbsolutePath().getParent().resolve(".neoforbric-forge-jarjar"); Files.createDirectories(cache); }
    public static Result discover(Path directory, AuditLog audit) throws Exception {
        if (!Files.isDirectory(directory)) throw new Failure("MODS_DIRECTORY", "Not a directory: " + directory);
        var discovery = new ForgeDiscovery(directory); List<Archive> roots = new ArrayList<>();
        try (var paths = Files.list(directory)) { for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) { var archive = Archive.read(path).java21View(audit); roots.add(archive); discovery.nested(archive); } }
        List<Archive> selected = new ArrayList<>(roots);
        for (var entry : discovery.requests.entrySet()) {
            var chosen = entry.getValue().stream().filter(c -> entry.getValue().stream().allMatch(r -> MavenVersions.matches(r.range(), c.version())))
                    .max(Comparator.comparing(c -> new DefaultArtifactVersion(c.version()))).orElseThrow(() -> new Failure("JARJAR_VERSION", "No common Forge JarJar version for " + entry.getKey()));
            selected.add(chosen.archive()); audit.record("DISCOVER", "jarjar-selected", entry.getKey(), Map.of("version", chosen.version(), "sha256", chosen.archive().hash()));
        }
        Set<String> hashes = new HashSet<>(); List<Discovery.Candidate> mods = new ArrayList<>(); List<Archive> libraries = new ArrayList<>();
        for (Archive original : selected) if (hashes.add(original.hash())) {
            Archive archive = original.permitDeclaredNested(discovery.permitted.getOrDefault(original.hash(), Set.of()));
            if (archive.read("META-INF/mods.toml") != null) for (var metadata : ForgeMetadata.read(archive).mods()) {
                mods.add(new Discovery.Candidate(archive, metadata)); audit.record("DISCOVER", "mod-discovered", metadata.id(), Map.of("ecosystem", "FORGE", "descriptor", "META-INF/mods.toml", "source", archive.path().toString(), "sha256", archive.hash(), "version", metadata.version()));
            } else if (!roots.contains(original)) { ForgeAdmission.library(archive); libraries.add(archive); audit.record("DISCOVER", "forge-library", archive.path().toString(), Map.of("sha256", archive.hash())); }
            else throw new Failure("FORGE_PROFILE", "Forge profile requires META-INF/mods.toml: " + archive.path());
        }
        return new Result(List.copyOf(mods), List.copyOf(libraries));
    }
    private void nested(Archive archive) throws Exception {
        if (!visited.add(archive.hash())) return;
        byte[] metadata = archive.read("META-INF/jarjar/metadata.json"); if (metadata == null) return;
        Set<String> declared = new HashSet<>();
        for (var value : JsonParser.parseString(new String(metadata, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("jars")) {
            var entry = value.getAsJsonObject(); String path = entry.get("path").getAsString();
            if (path.startsWith("/") || path.contains("\\") || path.contains(":") || Arrays.stream(path.split("/", -1)).anyMatch(p -> Set.of("", ".", "..").contains(p))) throw new Failure("JARJAR_PATH", path);
            declared.add(path); byte[] bytes = archive.read(path); if (bytes == null) throw new Failure("JARJAR_PATH", "Missing " + path);
            Path materialized = cache.resolve(Archive.sha256(bytes) + ".jar"); Files.write(materialized, bytes); Archive child = Archive.read(materialized).java21View(new AuditLog());
            var identifier = entry.getAsJsonObject("identifier"); var version = entry.getAsJsonObject("version"); String key = identifier.get("group").getAsString() + ":" + identifier.get("artifact").getAsString();
            requests.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Nested(version.get("artifactVersion").getAsString(), version.get("range").getAsString(), child)); nested(child);
        }
        // Layout validation of admitted roots permits precisely the declared children.
        permitted.put(archive.hash(), Set.copyOf(declared));
    }
    private final Map<String, Set<String>> permitted = new HashMap<>();
}
