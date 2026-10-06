package org.neoforbric.fabric;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.discovery.*;
import net.fabricmc.loader.impl.metadata.*;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModDependency;
import org.neoforbric.loader.*;

/** Fabric's passive resolver consumes NeoForbric's immutable inputs; it never discovers a mods directory. */
public final class FabricRuntimePlan {
    private static final int MAX_CANDIDATES = 1024;
    public record Node(Discovery.Candidate source, LoaderModMetadata metadata, ModCandidateImpl nativeCandidate, Set<String> nestedPaths) {}
    private final Path cache;
    private final AuditLog audit;
    private final EnvType side;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Node> nestedByHash = new HashMap<>();
    private final Set<ModCandidateImpl> builtins = new HashSet<>();
    private List<ModCandidateImpl> selected;
    private Map<Path, String> exclusions = Map.of();
    public FabricRuntimePlan(Path cache, EnvType side, AuditLog audit) { this.cache = cache; this.side = side; this.audit = audit; }
    public List<Discovery.Candidate> discover(List<Discovery.Candidate> roots) throws IOException {
        Files.createDirectories(cache);
        for (var candidate : roots) {
            if (candidate.metadata().ecosystem() != Metadata.Ecosystem.FABRIC) throw new Failure("FABRIC_RUNTIME_SCOPE", "Experimental Fabric runtime accepts Fabric inputs only: " + candidate.metadata().id());
            read(candidate.archive(), null, List.of(), 0);
        }
        return nodes.stream().map(Node::source).toList();
    }
    private Node read(Archive archive, String nestedPath, List<String> parents, int depth) throws IOException {
        if (depth > 8) throw new Failure("NESTED_LIMIT", archive.path() + " exceeds nested depth 8");
        if (nestedPath != null && nestedByHash.containsKey(archive.hash())) return nestedByHash.get(archive.hash());
        if (nodes.size() >= MAX_CANDIDATES) throw new Failure("NESTED_LIMIT", archive.path() + " exceeds " + MAX_CANDIDATES + " Fabric candidates");
        LoaderModMetadata metadata;
        try { metadata = ModMetadataParser.parseMetadata(new ByteArrayInputStream(archive.read("fabric.mod.json")), archive.path().toString(), parents, new VersionOverrides(), new DependencyOverrides(cache), false); }
        catch (Exception error) { throw new Failure("FABRIC_SCHEMA", archive.path().toString(), error); }
        for (var adapter : metadata.getLanguageAdapterDefinitions().entrySet()) {
            if (!adapter.getValue().matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+"))
                throw new Failure("FABRIC_SCHEMA", metadata.getId() + " has an invalid language adapter class " + adapter.getValue());
            audit.record("DISCOVER", "fabric-language-adapter", metadata.getId(), Map.of("key", adapter.getKey(), "class", adapter.getValue(), "loader", "G"));
        }
        Set<String> declared = new LinkedHashSet<>(); List<ModCandidateImpl> children = new ArrayList<>();
        for (var jar : metadata.getJars()) {
            String entry = jar.getFile();
            if (entry.contains("\\") || entry.startsWith("/") || Arrays.stream(entry.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) throw new Failure("NESTED_PATH", entry);
            byte[] bytes = archive.read(entry);
            if (bytes == null || !declared.add(entry)) throw new Failure("NESTED_INPUT", archive.path() + " missing / duplicate " + entry);
            String hash = Archive.sha256(bytes); Path extracted = cache.resolve(hash + ".jar");
            if (Files.exists(extracted)) { if (!Archive.sha256(Files.readAllBytes(extracted)).equals(hash)) throw new Failure("INPUT_CHECKSUM", extracted.toString()); }
            else Files.write(extracted, bytes);
            List<String> chain = new ArrayList<>(parents); chain.add(archive.path().toString());
            Node child = read(Archive.read(extracted), entry, chain, depth + 1); children.add(child.nativeCandidate());
            audit.record("DISCOVER", "nested-mod", child.metadata().getId(), Map.of("parent", metadata.getId(), "entry", entry, "sha256", hash));
        }
        Archive permitted = archive.verifyJarSignatures(audit).permitDeclaredNested(declared); permitted.requireFabricLayout();
        ModCandidateImpl nativeCandidate;
        if (nestedPath == null) nativeCandidate = plain(List.of(archive.path()), metadata, children);
        else {
            nativeCandidate = (ModCandidateImpl) NativeAccess.call(null, ModCandidateImpl.class, "createNested", new Class<?>[]{String.class, long.class, LoaderModMetadata.class, boolean.class, Collection.class}, nestedPath, 0L, metadata, false, children);
            nativeCandidate.setPaths(List.of(archive.path()));
        }
        for (var child : children) NativeAccess.call(child, ModCandidateImpl.class, "addParent", new Class<?>[]{ModCandidateImpl.class}, nativeCandidate);
        Discovery.Candidate candidate = new Discovery.Candidate(permitted, Metadata.read(permitted).getFirst());
        Node node = new Node(candidate, metadata, nativeCandidate, Set.copyOf(declared)); nodes.add(node);
        if (nestedPath != null) nestedByHash.put(archive.hash(), node);
        audit.record("DISCOVER", "fabric-runtime-candidate", metadata.getId(), Map.of("version", metadata.getVersion().getFriendlyString(), "source", archive.path().toString(), "sha256", archive.hash()));
        return node;
    }
    static ModCandidateImpl plain(List<Path> paths, LoaderModMetadata metadata, Collection<ModCandidateImpl> children) {
        return (ModCandidateImpl) NativeAccess.call(null, ModCandidateImpl.class, "createPlain", new Class<?>[]{List.class, LoaderModMetadata.class, boolean.class, Collection.class}, paths, metadata, false, children);
    }
    public void builtin(String id, String version, List<Path> paths) {
        ModMetadata metadata = new BuiltinModMetadata.Builder(id, version).setName(id).build();
        var wrapper = (LoaderModMetadata) NativeAccess.construct("net.fabricmc.loader.impl.discovery.BuiltinMetadataWrapper", new Class<?>[]{ModMetadata.class}, metadata);
        builtins.add(plain(paths, wrapper, List.of()));
    }
    public List<Discovery.Candidate> resolve() {
        Map<String, ModCandidateImpl> provided = new HashMap<>();
        builtins.forEach(builtin -> provided.put(builtin.getId(), builtin));
        List<ModCandidateImpl> inputs = new ArrayList<>(builtins);
        inputs.addAll(nodes.stream().map(Node::nativeCandidate)
                .filter(candidate -> candidate.isRoot() || !provided.containsKey(candidate.getId())).toList());
        try { selected = ModResolver.resolve(inputs, side, new HashMap<>()); }
        catch (ModResolutionException error) { throw new Failure("FABRIC_DEPENDENCY", "Fabric dependency resolution failed: " + error.getMessage(), error); }
        Map<Path, String> reasons = new HashMap<>();
        for (var node : nodes) {
            if (selected.contains(node.nativeCandidate())) continue;
            List<String> missing = node.metadata().getDependencies().stream()
                    .filter(dependency -> dependency.getKind() == ModDependency.Kind.DEPENDS)
                    .filter(dependency -> selected.stream().noneMatch(mod ->
                            (mod.getId().equals(dependency.getModId()) || mod.getProvides().contains(dependency.getModId()))
                                    && dependency.matches(mod.getVersion())))
                    .map(dependency -> dependency.getModId() + " " + dependency.getVersionRequirements()).sorted().toList();
            String reason = !node.nativeCandidate().isRoot() && provided.containsKey(node.metadata().getId())
                    ? "Provided by builtin " + node.metadata().getId() + " " + provided.get(node.metadata().getId()).getVersion().getFriendlyString()
                    : !node.source().metadata().available(side.name().toLowerCase(Locale.ROOT))
                    ? "Excluded on " + side.name().toLowerCase(Locale.ROOT) + ": environment=" + node.source().metadata().environment()
                    : missing.isEmpty() ? "Not selected by Fabric dependency resolution"
                    : "Not selected by Fabric dependency resolution; required dependencies not satisfied: " + String.join(", ", missing);
            reasons.put(node.source().archive().path(), reason);
            audit.record("RESOLVE", "fabric-runtime-excluded", node.metadata().getId(), Map.of("reason", reason, "source", node.source().archive().path().toString()));
        }
        exclusions = Map.copyOf(reasons);
        audit.record("RESOLVE", "fabric-runtime-order", "plan", Map.of("ids", selected.stream().map(ModCandidateImpl::getId).toList().toString(), "owner", "NeoForbric", "nativeDiscovery", "false"));
        return selected.stream().filter(c -> !builtins.contains(c)).map(c -> nodes.stream().filter(n -> n.nativeCandidate() == c).findFirst().orElseThrow().source()).toList();
    }
    public List<Node> nodes() { return List.copyOf(nodes); }
    public Map<Path, String> exclusions() { return exclusions; }
    public List<ModCandidateImpl> selectedNative() { return List.copyOf(selected); }
    public Node node(Discovery.Candidate candidate) { return nodes.stream().filter(n -> n.source().archive().path().equals(candidate.archive().path())).findFirst().orElseThrow(); }
    /** Loom wraps ordinary nested dependency libraries in generated mod metadata. */
    public boolean bundledLibrary(Discovery.Candidate candidate) {
        Node node = node(candidate);
        var metadata = node.metadata();
        var generated = metadata.getCustomValue("fabric-loom:generated");
        return !node.nativeCandidate().isRoot() && generated != null
                && generated.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.BOOLEAN && generated.getAsBoolean()
                && metadata.getEntrypointKeys().isEmpty() && metadata.getOldInitializers().isEmpty()
                && metadata.getLanguageAdapterDefinitions().isEmpty() && metadata.getClassTweaker() == null
                && metadata.getMixinConfigs(EnvType.CLIENT).isEmpty() && metadata.getMixinConfigs(EnvType.SERVER).isEmpty();
    }
}
