package org.neoforbric.fabric;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.discovery.*;
import net.fabricmc.loader.impl.metadata.*;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.neoforbric.loader.*;

/** Fabric's passive resolver consumes NeoForbric's immutable inputs; it never discovers a mods directory. */
public final class FabricRuntimePlan {
    public record Node(Discovery.Candidate source, LoaderModMetadata metadata, ModCandidateImpl nativeCandidate, Set<String> nestedPaths) {}
    private final Path cache;
    private final AuditLog audit;
    private final EnvType side;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Node> nestedByHash = new HashMap<>();
    private final Set<ModCandidateImpl> builtins = new HashSet<>();
    private List<ModCandidateImpl> selected;
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
        if (depth > 8 || nodes.size() >= 256) throw new Failure("NESTED_LIMIT", "Fabric graph exceeds depth / candidate limit");
        if (nestedPath != null && nestedByHash.containsKey(archive.hash())) return nestedByHash.get(archive.hash());
        LoaderModMetadata metadata;
        try { metadata = ModMetadataParser.parseMetadata(new ByteArrayInputStream(archive.read("fabric.mod.json")), archive.path().toString(), parents, new VersionOverrides(), new DependencyOverrides(), false); }
        catch (Exception error) { throw new Failure("FABRIC_SCHEMA", archive.path().toString(), error); }
        if (!metadata.getLanguageAdapterDefinitions().isEmpty()) throw new Failure("FABRIC_FEATURE_UNSUPPORTED", metadata.getId() + " requires an unverified custom language adapter");
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
        Archive permitted = archive.permitDeclaredNested(declared); permitted.requireSupportedLayout();
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
        builtins.add(plain(paths, new BuiltinMetadataWrapper(metadata), List.of()));
    }
    public List<Discovery.Candidate> resolve() {
        List<ModCandidateImpl> inputs = new ArrayList<>(builtins); inputs.addAll(nodes.stream().map(Node::nativeCandidate).toList());
        try { selected = ModResolver.resolve(inputs, side, new HashMap<>()); }
        catch (ModResolutionException error) { throw new Failure("FABRIC_DEPENDENCY", "Fabric dependency resolution failed", error); }
        Set<ModCandidateImpl> selectedSet = new HashSet<>(selected);
        audit.record("RESOLVE", "fabric-runtime-order", "plan", Map.of("ids", selected.stream().map(ModCandidateImpl::getId).toList().toString(), "owner", "NeoForbric", "nativeDiscovery", "false"));
        return selected.stream().filter(c -> !builtins.contains(c)).map(c -> nodes.stream().filter(n -> n.nativeCandidate() == c).findFirst().orElseThrow().source()).toList();
    }
    public List<Node> nodes() { return List.copyOf(nodes); }
    public List<ModCandidateImpl> selectedNative() { return List.copyOf(selected); }
    public Node node(Discovery.Candidate candidate) { return nodes.stream().filter(n -> n.source().archive().path().equals(candidate.archive().path())).findFirst().orElseThrow(); }
}
