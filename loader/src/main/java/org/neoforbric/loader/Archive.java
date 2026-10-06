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
    private static final int MAX_ARCHIVE = 64 * 1024 * 1024;
    private static final int MAX_ENTRY = 16 * 1024 * 1024;
    private static final int MAX_EXPANDED = 128 * 1024 * 1024;
    private final Path path;
    private final String hash;
    private final Map<String, byte[]> entries;
    private final Manifest manifest;
    private final byte[] source;
    private Set<String> permittedNested = Set.of();
    private boolean verifiedSignatures;

    private Archive(Path path, String hash, Map<String, byte[]> entries, Manifest manifest, byte[] source) {
        this.path = path;
        this.hash = hash;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.manifest = manifest;
        this.source = source;
    }

    public static Archive read(Path path) throws IOException {
        return read(path, 20000);
    }
    public static Archive readRuntimeGame(Path path) throws IOException {
        return read(path, 40000);
    }
    private static Archive read(Path path, int maxEntries) throws IOException {
        Path actual = path.toRealPath();
        byte[] source;
        try (InputStream in = Files.newInputStream(actual)) {
            source = in.readNBytes(MAX_ARCHIVE + 1);
        }
        if (source.length > MAX_ARCHIVE) throw new Failure("ARCHIVE_LIMIT", actual + " exceeds 64 MiB");
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
                byte[] bytes = zip.readNBytes(MAX_ENTRY + 1);
                expanded += bytes.length;
                if (bytes.length > MAX_ENTRY || expanded > MAX_EXPANDED || entries.size() >= maxEntries)
                    throw new Failure("ARCHIVE_LIMIT", actual + " exceeds expanded entry limits");
                entries.put(name, bytes);
            }
        }
        if (entries.isEmpty()) throw new Failure("INVALID_ARCHIVE", actual + " has no ZIP entries");
        byte[] manifestBytes = entries.get("META-INF/MANIFEST.MF");
        Manifest manifest = manifestBytes == null ? new Manifest() : new Manifest(new ByteArrayInputStream(manifestBytes));
        return new Archive(actual, sha256(source), entries, manifest, source);
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
    public Set<String> names() { return entries.keySet(); }
    public byte[] read(String name) {
        byte[] bytes = entries.get(name);
        return bytes == null ? null : bytes.clone();
    }
    public URL codeSource() {
        try { return path.toUri().toURL(); }
        catch (MalformedURLException impossible) { throw new AssertionError(impossible); }
    }

    /** The runtime plan must discover and verify every declared nested input before granting this view. */
    public Archive permitDeclaredNested(Set<String> names) {
        for (String name : names) if (!entries.containsKey(name) || !name.endsWith(".jar")) throw new Failure("NESTED_INPUT", path + " missing declared nested JAR " + name);
        Archive view = new Archive(path, hash, entries, manifest, source);
        view.permittedNested = Set.copyOf(names); view.verifiedSignatures = verifiedSignatures; return view;
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
        Archive view = new Archive(path, hash, entries, manifest, source); view.permittedNested = permittedNested; view.verifiedSignatures = true;
        audit.record("DISCOVER", "jar-signature-verified", path.toString(), Map.of("sourceSha256", hash, "signedEntries", Integer.toString(verified), "derivedPolicy", "unsigned-remapped-artifact"));
        return view;
    }

    public void requireSupportedLayout() {
        var attributes = manifest.getMainAttributes();
        if (attributes.getValue("Class-Path") != null
                || "true".equalsIgnoreCase(attributes.getValue("Multi-Release")))
            throw new Failure("UNSUPPORTED_LAYOUT", path + " requires manifest classpath, modules or multi-release support");
        for (String name : names()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (name.equals("module-info.class") || name.startsWith("META-INF/versions/")
                || (upper.endsWith(".JAR") && !permittedNested.contains(name)) || (upper.startsWith("META-INF/")
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
        if (bytes == null) return null;
        try {
            return URL.of(new URI("neoforbric", hash + "/" + name, null), new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() { connected = true; }
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                    };
                }
            });
        } catch (MalformedURLException | URISyntaxException impossible) { throw new AssertionError(impossible); }
    }
}
