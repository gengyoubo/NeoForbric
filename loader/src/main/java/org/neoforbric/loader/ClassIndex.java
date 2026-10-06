package org.neoforbric.loader;

import java.net.URL;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.neoforbric.fabric.NativeFabricRuntime;

public final class ClassIndex {
    public record Entry(Archive archive, String resource) {
        public byte[] bytes() { return archive.read(resource); }
    }
    private final Map<String, Entry> classes;
    private final List<Archive> archives;

    private ClassIndex(Map<String, Entry> classes, List<Archive> archives) {
        this.classes = Map.copyOf(classes);
        this.archives = List.copyOf(archives);
    }

    public static boolean shared(String name) {
        return name.startsWith("org.neoforbric.api.") || name.startsWith("org.objectweb.asm.") || name.startsWith("net.fabricmc.api.")
                || (NativeFabricRuntime.active() && (name.startsWith("net.fabricmc.loader.") || name.startsWith("org.spongepowered.asm.") || name.startsWith("com.llamalad7.mixinextras.")));
    }

    public static ClassIndex prepare(List<Archive> inputs, ClassLoader parent, AuditLog audit) {
        return prepare(inputs, parent, audit, Set.of());
    }
    public static ClassIndex prepare(List<Archive> inputs, ClassLoader parent, AuditLog audit, Set<java.nio.file.Path> libraries) {
        return prepare(inputs, parent, audit, libraries, Set.of());
    }
    public static ClassIndex prepare(List<Archive> inputs, ClassLoader parent, AuditLog audit, Set<java.nio.file.Path> libraries, Set<java.nio.file.Path> clientUi) {
        Map<String, Entry> classes = new TreeMap<>();
        Set<String> seenArchives = new HashSet<>();
        List<Archive> unique = new ArrayList<>();
        for (Archive archive : inputs) {
            if (!seenArchives.add(archive.path().toString())) continue;
            archive.requireSupportedLayout();
            unique.add(archive);
            for (String resource : new TreeSet<>(archive.names())) {
                if (!resource.endsWith(".class")) continue;
                String name = resource.substring(0, resource.length() - 6).replace('/', '.');
                boolean ownedUi = clientUi.contains(archive.path()) && name.startsWith("org.neoforbric.client.");
                if (clientUi.contains(archive.path()) && !ownedUi) throw new Failure("CLIENT_UI_PACKAGE", "First-party UI JAR contains an unexpected class " + name);
                if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                        || (name.startsWith("org.neoforbric.") && !ownedUi) || shared(name))
                    throw new Failure("PROTECTED_PACKAGE", archive.path() + " attempts to define kernel / shared class " + name);
                validateName(name, archive.read(resource));
                Entry previous = classes.putIfAbsent(name, new Entry(archive, resource));
                if (previous != null) {
                    // Split Fabric modules publish the same package annotations. Ordinary classes,
                    // and package annotations which disagree, still have exactly one required owner.
                    if (name.endsWith(".package-info") && Arrays.equals(previous.bytes(), archive.read(resource))) {
                        audit.record("PREPARE", "identical-package-metadata", name, Map.of("owner", previous.archive().path().toString(), "duplicate", archive.path().toString()));
                        continue;
                    }
                    throw new Failure("DUPLICATE_CLASS", name + " belongs to both " + previous.archive().path() + " and " + archive.path());
                }
                URL contamination = parent.getResource(resource);
                // Explicit bundled libraries (e.g. game's Gson vs tools' Gson) are isolated in G.
                // Game/mod types and shared APIs still cannot be duplicated on P.
                if (contamination != null && !libraries.contains(archive.path())) throw new Failure("PARENT_CONTAMINATION", name + " also exists on bootstrap classpath: " + contamination);
                audit.record("PREPARE", "class-owner", name, Map.of("loader", "G", "source", archive.path().toString(), "archiveSha256", archive.hash()));
            }
        }
        return new ClassIndex(classes, unique);
    }

    public static void validateName(String name, byte[] bytes) {
        try {
            if (!new ClassReader(bytes).getClassName().replace('/', '.').equals(name))
                throw new Failure("CLASS_NAME_MISMATCH", "Class entry / transformed name differs from " + name);
        } catch (Failure known) { throw known; }
        catch (RuntimeException invalid) { throw new Failure("CLASS_INVALID", "Invalid class bytes for " + name, invalid); }
    }

    public Entry entry(String name) { return classes.get(name); }
    public byte[] original(String name) {
        Entry entry = classes.get(name);
        if (entry == null) throw new Failure("BYTECODE_MISSING", "No game-domain bytecode for " + name);
        return entry.bytes();
    }
    public List<URL> resources(String name) {
        return archives.stream().map(a -> a.resource(name)).filter(Objects::nonNull).toList();
    }
    public byte[] resourceBytes(String name) {
        for (Archive archive : archives) if (archive.names().contains(name)) return archive.read(name);
        return null;
    }
    public List<URL> resources(String name, GameResources resources) {
        return archives.stream().map(a -> resources.resource(a, name)).filter(Objects::nonNull).toList();
    }
}
