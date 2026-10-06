package org.neoforbric.fabric;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import org.neoforbric.loader.*;

/** Information bridge for passive Fabric implementations. It cannot create a classloader or add an input. */
public final class NeoFabricLauncher extends FabricLauncherBase {
    private final EnvType side;
    private final String entrypoint;
    private final AuditLog audit;
    private ClassIndex index;
    private GameClassLoader game;
    private TransformPipeline pipeline;
    private List<Path> classpath = List.of();
    NeoFabricLauncher(EnvType side, String entrypoint, AuditLog audit) {
        this.side = side; this.entrypoint = entrypoint; this.audit = audit; setProperties(new HashMap<>());
    }
    void bind(ClassIndex index, GameClassLoader game, TransformPipeline pipeline, List<Archive> archives) {
        this.index = index; this.game = game; this.pipeline = pipeline; this.classpath = archives.stream().map(Archive::path).toList();
    }
    void finishMixin() { finishMixinBootstrapping(); }
    @Override public void addToClassPath(Path path, String... prefixes) { throw forbidden("addToClassPath", path); }
    @Override public void setAllowedPrefixes(Path path, String... prefixes) { throw forbidden("setAllowedPrefixes", path); }
    @Override public void setValidParentClassPath(Collection<Path> paths) { throw new Failure("NATIVE_BOOTSTRAP_FORBIDDEN", "NeoForbric owns parent classpath boundaries"); }
    private Failure forbidden(String action, Path path) { return new Failure("NATIVE_BOOTSTRAP_FORBIDDEN", "NeoForbric owns " + action + ": " + path); }
    @Override public EnvType getEnvironmentType() { return side; }
    @Override public boolean isClassLoaded(String name) { return game != null && game.hasDefined(name.replace('/', '.')); }
    @Override public Class<?> loadIntoTarget(String name) throws ClassNotFoundException { return getTargetClassLoader().loadClass(name); }
    @Override public InputStream getResourceAsStream(String name) {
        // Mixin consumes small metadata streams. Memory snapshots also avoid leaking Windows
        // JAR handles when third-party metadata readers leave their stream open.
        byte[] bytes = index == null ? null : index.resourceBytes(name);
        return bytes == null ? NeoFabricLauncher.class.getClassLoader().getResourceAsStream(name) : new ByteArrayInputStream(bytes);
    }
    @Override public ClassLoader getTargetClassLoader() {
        if (game == null) throw new Failure("FABRIC_RUNTIME_NOT_BOUND", "Game classloader has not been bound"); return game;
    }
    @Override public byte[] getClassByteArray(String name, boolean transformed) throws IOException {
        String dotted = name.replace('/', '.');
        if (index != null && index.entry(dotted) != null) {
            byte[] original = index.original(dotted);
            if (!transformed) return original;
            try { return pipeline.applyBefore(dotted, original, index::original, audit, "fabric-runtime-mixin"); }
            catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("Pre-Mixin bytes unavailable: " + name, error); }
        }
        try (InputStream in = NeoFabricLauncher.class.getClassLoader().getResourceAsStream(dotted.replace('.', '/') + ".class")) { return in == null ? null : in.readAllBytes(); }
    }
    @Override public Manifest getManifest(Path path) {
        try (var jar = new java.util.jar.JarFile(path.toFile())) { return jar.getManifest(); }
        catch (IOException error) { throw new Failure("MANIFEST_READ", path.toString(), error); }
    }
    @Override public String getEntrypoint() { return entrypoint; }
    @Override public List<Path> getClassPath() { return classpath; }
}
