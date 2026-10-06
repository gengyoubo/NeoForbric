package org.neoforbric.loader;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Standard jar: paths backed by the same immutable bytes used for class definition. */
public final class GameResources implements AutoCloseable {
    private final Path root;
    private final Map<Archive, Path> snapshots = new HashMap<>();
    private final List<FileSystem> filesystems = new ArrayList<>();
    private final AuditLog audit;
    public GameResources(AuditLog audit) throws IOException {
        this.audit = audit; root = Files.createTempDirectory("neoforbric-resources-");
    }
    public synchronized URL resource(Archive archive, String name) {
        if (!archive.names().contains(name)) return null;
        try {
            Path path = snapshots.get(archive);
            if (path == null) {
                path = root.resolve(snapshots.size() + "-" + archive.hash() + ".jar");
                Files.write(path, archive.snapshot(), StandardOpenOption.CREATE_NEW);
                filesystems.add(FileSystems.newFileSystem(URI.create("jar:" + path.toUri()), Map.of()));
                snapshots.put(archive, path);
                audit.record("GAME", "resource-snapshot", archive.path().toString(), Map.of("sha256", archive.hash(), "scheme", "jar", "path", path.toString()));
            }
            String entry = new URI(null, null, "/" + name, null).getRawPath().substring(1);
            URI uri = URI.create("jar:" + path.toUri() + "!/" + entry);
            return URL.of(uri, new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws IOException {
                    URLConnection connection = new URL(url.toExternalForm()).openConnection();
                    connection.setUseCaches(false); return connection;
                }
            });
        } catch (IOException | URISyntaxException error) { throw new Failure("RESOURCE_IO", "Cannot expose snapshot resource " + name, error); }
    }
    @Override public synchronized void close() throws IOException {
        IOException failure = null;
        for (FileSystem filesystem : filesystems) {
            try { filesystem.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        for (Path path : snapshots.values()) {
            try { Files.deleteIfExists(path); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        try { Files.deleteIfExists(root); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }
}
