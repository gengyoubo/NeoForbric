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
    private final Set<InputStream> streams = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<java.util.jar.JarFile> jars = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;
    private final AuditLog audit;
    public GameResources(AuditLog audit) throws IOException {
        this.audit = audit; root = Files.createTempDirectory("neoforbric-resources-");
    }
    public synchronized URL resource(Archive archive, String name) {
        if (closed) return null;
        if (!archive.hasResource(name)) return null;
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
                    JarURLConnection connection = (JarURLConnection) new URL(url.toExternalForm()).openConnection();
                    connection.setUseCaches(false);
                    return new JarURLConnection(url) {
                        @Override public void connect() throws IOException { getJarFile(); connected = true; }
                        @Override public java.util.jar.JarFile getJarFile() throws IOException {
                            synchronized (GameResources.this) {
                                if (closed) throw new IOException("Game resources are closed");
                                var jar = connection.getJarFile(); jars.add(jar); return jar;
                            }
                        }
                        @Override public InputStream getInputStream() throws IOException {
                            synchronized (GameResources.this) {
                                if (closed) throw new IOException("Game resources are closed");
                                getJarFile();
                                InputStream source = connection.getInputStream();
                                InputStream tracked = new FilterInputStream(source) {
                                    @Override public void close() throws IOException {
                                        try { super.close(); }
                                        finally { synchronized (GameResources.this) { streams.remove(this); } }
                                    }
                                };
                                streams.add(tracked); return tracked;
                            }
                        }
                        @Override public String getContentType() {
                            try { connect(); return connection.getContentType(); } catch (IOException error) { return null; }
                        }
                        @Override public long getContentLengthLong() {
                            try { connect(); return connection.getContentLengthLong(); } catch (IOException error) { return -1; }
                        }
                        @Override public int getContentLength() {
                            long length = getContentLengthLong(); return length > Integer.MAX_VALUE ? -1 : (int) length;
                        }
                        @Override public long getLastModified() { return connection.getLastModified(); }
                        @Override public java.security.Permission getPermission() throws IOException { return connection.getPermission(); }
                    };
                }
            });
        } catch (IOException | URISyntaxException error) { throw new Failure("RESOURCE_IO", "Cannot expose snapshot resource " + name, error); }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (InputStream stream : List.copyOf(streams)) {
            try { stream.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        for (java.util.jar.JarFile jar : jars) {
            try { jar.close(); } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        // Package scanners reconstruct standard jar URLs and can populate the
        // JDK JarURLConnection cache despite our resource handler disabling it.
        // Release those handles for our own snapshots before deleting on Windows.
        for (Path path : snapshots.values()) {
            try {
                var connection = (JarURLConnection) URI.create("jar:" + path.toUri() + "!/").toURL().openConnection();
                connection.setUseCaches(true);
                connection.getJarFile().close();
            } catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
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
