package org.neoforbric.loader;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
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
        view.permittedNested = Set.copyOf(names); return view;
    }

    public void requireSupportedLayout() {
        var attributes = manifest.getMainAttributes();
        if (attributes.getValue("Class-Path") != null || attributes.getValue("Automatic-Module-Name") != null
                || "true".equalsIgnoreCase(attributes.getValue("Multi-Release"))
                || "true".equalsIgnoreCase(attributes.getValue("Sealed"))
                || manifest.getEntries().values().stream().anyMatch(a -> "true".equalsIgnoreCase(a.getValue("Sealed"))))
            throw new Failure("UNSUPPORTED_LAYOUT", path + " requires manifest classpath, modules, sealing or multi-release support");
        for (String name : names()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (name.equals("module-info.class") || name.startsWith("META-INF/versions/")
                || (upper.endsWith(".JAR") && !permittedNested.contains(name)) || (upper.startsWith("META-INF/")
                    && (upper.matches(".*\\.(SF|RSA|DSA|EC)$") || upper.startsWith("META-INF/SIG-"))))
                throw new Failure("UNSUPPORTED_LAYOUT", path + " contains unsupported module / nested / signed entry " + name);
        }
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
