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

    private Archive(Path path, String hash, Map<String, byte[]> entries, Manifest manifest) {
        this.path = path;
        this.hash = hash;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.manifest = manifest;
    }

    public static Archive read(Path path) throws IOException {
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
                if (bytes.length > MAX_ENTRY || expanded > MAX_EXPANDED || entries.size() >= 20000)
                    throw new Failure("ARCHIVE_LIMIT", actual + " exceeds expanded entry limits");
                entries.put(name, bytes);
            }
        }
        if (entries.isEmpty()) throw new Failure("INVALID_ARCHIVE", actual + " has no ZIP entries");
        byte[] manifestBytes = entries.get("META-INF/MANIFEST.MF");
        Manifest manifest = manifestBytes == null ? new Manifest() : new Manifest(new ByteArrayInputStream(manifestBytes));
        return new Archive(actual, sha256(source), entries, manifest);
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
    public Set<String> names() { return entries.keySet(); }
    public byte[] read(String name) {
        byte[] bytes = entries.get(name);
        return bytes == null ? null : bytes.clone();
    }
    public URL codeSource() {
        try { return path.toUri().toURL(); }
        catch (MalformedURLException impossible) { throw new AssertionError(impossible); }
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
                    || upper.endsWith(".JAR") || (upper.startsWith("META-INF/")
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
