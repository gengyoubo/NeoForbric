package org.neoforbric.loader;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.*;

/** One immutable byte snapshot: discovery, resources and definition cannot observe different file revisions. */
public final class Archive {
    // Large content mods bundle resources, language runtimes and full soundtracks (e.g. Cobblemon, Z's Medieval Music).
    private static final int DEFAULT_MAX_ARCHIVE = 1024 * 1024 * 1024;
    public static final int MAX_ENTRY = 32 * 1024 * 1024;
    private static final int DEFAULT_MAX_EXPANDED = 1024 * 1024 * 1024;
    // Resource-heavy mods such as Chipped contain tens of thousands of small files.
    private static final int MAX_ENTRIES = 100_000;

    /** The embedder can tighten the archive bounds without recompiling the loader. */
    public static int maxArchiveBytes() { return Integer.getInteger("neoforbric.archive.maxBytes", DEFAULT_MAX_ARCHIVE); }
    public static int maxExpandedBytes() { return Integer.getInteger("neoforbric.archive.maxExpandedBytes", DEFAULT_MAX_EXPANDED); }

    private static String mib(int bytes) { return (bytes / (1024 * 1024)) + " MiB"; }
    private final Path path;
    private final String hash;
    private final Map<String, byte[]> entries;
    private final Set<String> directories;
    private final Manifest manifest;
    private final byte[] source;
    private Set<String> permittedNested = Set.of();
    private boolean inertNestedResources;
    private boolean verifiedSignatures;

    private Archive(Path path, String hash, Map<String, byte[]> entries, Manifest manifest, byte[] source) {
        this.path = path;
        this.hash = hash;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        Set<String> directories = new HashSet<>();
        if (!entries.isEmpty()) directories.add("");
        for (String name : entries.keySet()) for (int separator = name.lastIndexOf('/'); separator >= 0; separator = name.lastIndexOf('/', separator - 1))
            directories.add(name.substring(0, separator + 1));
        this.directories = Set.copyOf(directories);
        this.manifest = manifest;
        this.source = source;
    }

    /** Policy views share immutable storage instead of rebuilding large resource indexes. */
    private Archive(Archive original) {
        path = original.path; hash = original.hash; entries = original.entries; directories = original.directories;
        manifest = original.manifest; source = original.source;
        permittedNested = original.permittedNested; inertNestedResources = original.inertNestedResources;
        verifiedSignatures = original.verifiedSignatures;
    }

    public static Archive read(Path path) throws IOException {
        Path actual = path.toRealPath();
        int maxArchive = maxArchiveBytes();
        int maxExpanded = maxExpandedBytes();
        byte[] source;
        try (InputStream in = Files.newInputStream(actual)) {
            source = in.readNBytes(maxArchive + 1);
        }
        if (source.length > maxArchive) throw new Failure("ARCHIVE_LIMIT", actual + " exceeds " + mib(maxArchive));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry next;
            while ((next = zip.getNextEntry()) != null) {
                if (next.isDirectory()) continue;
                String name = next.getName();
                if (name.contains("\\") || Arrays.stream(name.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
                    throw new Failure("ARCHIVE_PATH", actual + " contains non-canonical path " + name);
                if (entries.containsKey(name)) throw new Failure("DUPLICATE_ENTRY", actual + " contains duplicate " + name);
                if (entries.size() >= MAX_ENTRIES)
                    throw new Failure("ARCHIVE_LIMIT", actual + " exceeds " + MAX_ENTRIES + " file entries at " + name);
                byte[] bytes = zip.readNBytes(MAX_ENTRY + 1);
                expanded += bytes.length;
                if (bytes.length > MAX_ENTRY)
                    throw new Failure("ARCHIVE_LIMIT", actual + " entry " + name + " exceeds 32 MiB");
                if (expanded > maxExpanded)
                    throw new Failure("ARCHIVE_LIMIT", actual + " exceeds " + mib(maxExpanded) + " expanded size at " + name);
                entries.put(name, bytes);
            }
        }
        if (entries.isEmpty()) throw new Failure("INVALID_ARCHIVE", actual + " has no ZIP entries");
        byte[] manifestBytes = entries.get("META-INF/MANIFEST.MF");
        Manifest manifest = manifestBytes == null ? new Manifest() : new Manifest(new ByteArrayInputStream(manifestBytes));
        return new Archive(actual, sha256(source), entries, manifest, source);
    }

    public static Archive readRuntimeGame(Path path) throws IOException {
        return read(path);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public Path path() { return path; }
    public String hash() { return hash; }
    public byte[] snapshot() { return source.clone(); }
    public void writeSnapshot(Path target) throws IOException { Files.write(target, source); }
    public Set<String> names() { return entries.keySet(); }
    /** ZIPs need not store directory entries for their packages to be discoverable. */
    public boolean hasResource(String name) { return entries.containsKey(name) || directories.contains(name.isEmpty() || name.endsWith("/") ? name : name + "/"); }
    public byte[] read(String name) {
        byte[] bytes = entries.get(name);
        return bytes == null ? null : bytes.clone();
    }
    public URL codeSource() {
        try { return path.toUri().toURL(); }
        catch (MalformedURLException impossible) { throw new AssertionError(impossible); }
    }

    /** The Fabric plan verifies declared nested inputs; other embedded JARs stay inert resources. */
    public Archive permitDeclaredNested(Set<String> names) {
        for (String name : names) if (!entries.containsKey(name) || !name.endsWith(".jar")) throw new Failure("NESTED_INPUT", path + " missing declared nested JAR " + name);
        Archive view = new Archive(this);
        view.permittedNested = Set.copyOf(names); view.inertNestedResources = true;
        view.verifiedSignatures = verifiedSignatures; return view;
    }
    public Archive verifyJarSignatures(AuditLog audit) throws IOException {
        boolean signed = names().stream().map(name -> name.toUpperCase(Locale.ROOT)).anyMatch(name -> name.startsWith("META-INF/") && name.matches(".*\\.(SF|RSA|DSA|EC)$"));
        if (!signed) return this;
        int verified = 0;
        try (var jar = new java.util.jar.JarInputStream(new ByteArrayInputStream(source), true)) {
            java.util.jar.JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) { jar.transferTo(OutputStream.nullOutputStream()); if (entry.getCodeSigners() != null) verified++; }
        } catch (SecurityException invalid) { throw new Failure("JAR_SIGNATURE", "Invalid signed input " + path, invalid); }
        if (verified == 0) throw new Failure("JAR_SIGNATURE", "Signature metadata could not be verified: " + path);
        Archive view = new Archive(this);
        view.inertNestedResources = inertNestedResources; view.verifiedSignatures = true;
        audit.record("DISCOVER", "jar-signature-verified", path.toString(), Map.of("sourceSha256", hash, "signedEntries", Integer.toString(verified), "derivedPolicy", "unsigned-remapped-artifact"));
        return view;
    }

    public void requireSupportedLayout() {
        requireSupportedLayout(false);
    }

    /** Fabric snapshots are normalized to Java 21; only metadata-declared JARs are discovered. */
    public void requireFabricLayout() {
        requireSupportedLayout(true);
    }

    private void requireSupportedLayout(boolean normalizeModules) {
        var attributes = manifest.getMainAttributes();
        if (!normalizeModules && (attributes.getValue("Class-Path") != null
                || "true".equalsIgnoreCase(attributes.getValue("Multi-Release"))))
            throw new Failure("UNSUPPORTED_LAYOUT", path + " requires manifest classpath, modules or multi-release support");
        for (String name : names()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if ((!normalizeModules && (name.equals("module-info.class") || name.startsWith("META-INF/versions/")))
                || (!normalizeModules && !inertNestedResources && upper.endsWith(".JAR") && !permittedNested.contains(name)) || (upper.startsWith("META-INF/")
                    && !verifiedSignatures && (upper.matches(".*\\.(SF|RSA|DSA|EC)$") || upper.startsWith("META-INF/SIG-"))))
                throw new Failure("UNSUPPORTED_LAYOUT", path + " contains unsupported module / nested / signed entry " + name);
        }
    }

    /** Whether the manifest seals the given dotted package; a per-package entry overrides the main attribute. */
    public boolean seals(String packageName) {
        Attributes entry = manifest.getEntries().get(packageName.replace('.', '/') + "/");
        String value = entry == null ? null : entry.getValue("Sealed");
        if (value == null) value = manifest.getMainAttributes().getValue("Sealed");
        return "true".equalsIgnoreCase(value);
    }

    /** Sealing directives only, so remapped artifacts keep the JVM package-sealing contract without other manifest state. */
    public Manifest sealingManifest() { return sealingDirectives(manifest); }
    public static Manifest sealingDirectives(Manifest source) {
        Manifest result = new Manifest();
        result.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (source == null) return result;
        String main = source.getMainAttributes().getValue("Sealed");
        if (main != null) result.getMainAttributes().putValue("Sealed", main);
        for (var entry : source.getEntries().entrySet()) {
            String value = entry.getValue().getValue("Sealed");
            if (value != null) {
                Attributes attributes = new Attributes();
                attributes.putValue("Sealed", value);
                result.getEntries().put(entry.getKey(), attributes);
            }
        }
        return result;
    }

    public URL resource(String name) {
        byte[] bytes = entries.get(name);
        if (bytes == null && !hasResource(name)) return null;
        byte[] resource = bytes == null ? new byte[0] : bytes;
        try {
            return URL.of(new URI("neoforbric", hash + "/" + name, null), new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() { connected = true; }
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(resource); }
                    };
                }
            });
        } catch (MalformedURLException | URISyntaxException impossible) { throw new AssertionError(impossible); }
    }
}
