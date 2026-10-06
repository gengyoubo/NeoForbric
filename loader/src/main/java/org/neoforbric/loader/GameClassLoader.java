package org.neoforbric.loader;

import java.io.Closeable;
import java.net.URL;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.neoforbric.fabric.NativeFabricRuntime;

/** The sole defining loader for all admitted game and mod types. Parent access is an allowlist. */
public final class GameClassLoader extends SecureClassLoader implements Closeable {
    static { registerAsParallelCapable(); }
    private final ClassIndex index;
    private final TransformPipeline transforms;
    private final AuditLog audit;
    private final GameResources filesystemResources;
    private final AtomicReference<Failure> poison = new AtomicReference<>();
    private final ThreadLocal<Deque<String>> transforming = ThreadLocal.withInitial(ArrayDeque::new);
    private volatile boolean ready;
    private volatile boolean closed;
    public record Generated(byte[] bytes, Archive owner, String generator) {}
    private java.util.function.Function<String, Generated> generator;
    public synchronized void generatedClasses(java.util.function.Function<String, Generated> source) {
        if (ready || generator != null) throw new Failure("LOADER_STATE", "Generated-class provider must be installed once before opening G");
        generator = Objects.requireNonNull(source);
    }

    public GameClassLoader(ClassIndex index, TransformPipeline transforms, ClassLoader parent, AuditLog audit) {
        this(index, transforms, parent, audit, null);
    }
    public GameClassLoader(ClassIndex index, TransformPipeline transforms, ClassLoader parent, AuditLog audit, GameResources filesystemResources) {
        super("NeoForbric-Game", parent);
        this.index = index;
        this.transforms = transforms;
        this.audit = audit;
        this.filesystemResources = filesystemResources;
        audit.record("PREPARE", "loader-created", "G", Map.of("definitionGate", "closed", "parent", parent.getName() == null ? "unnamed" : parent.getName()));
    }

    public synchronized void open() {
        if (ready || closed) throw new Failure("LOADER_STATE", "Cannot reopen game loader");
        if (!transforms.sealed()) throw new Failure("TRANSFORM_NOT_READY", "Seal transformation plan before opening G");
        ready = true;
        audit.record("SEALED", "definition-gate", "G", Map.of("state", "open"));
    }
    public boolean hasDefined(String name) { return findLoadedClass(name) != null; }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (closed) throw new Failure("LOADER_CLOSED", "Game domain is closed");
        if (poison.get() != null) throw new Failure("INSTANCE_TAINTED", "An earlier definition failed; restart required", poison.get());
        if (ClassIndex.shared(name)) return getParent().loadClass(name);
        try { return ClassLoader.getPlatformClassLoader().loadClass(name); }
        catch (ClassNotFoundException expected) { /* Only the explicit game index may define other types. */ }
        if (!ready) throw new Failure("EARLY_DEFINITION", "Game class requested before definition gate: " + name);
        Deque<String> targets = transforming.get();
        if (!targets.isEmpty() && findLoadedClass(name) == null) {
            // Native Mixin plugins execute in G and can link Minecraft APIs as well as
            // their helpers. Each dependency still runs the complete transformation plan;
            // Mixin enforces its own target preparation rules. Cycles never define twice.
            boolean metadataTool = NativeFabricRuntime.active() && "fabric-runtime-mixin".equals(transforms.activeRule()) && !targets.contains(name);
            if (!metadataTool) throw new Failure("REENTRANT_DEFINITION", "Transformer for " + targets.getLast() + " requested class " + name + "; use bytecode access");
            audit.record("PREPARE", "mixin-plugin-class", name, Map.of("requestingTarget", targets.getLast(), "loader", "G"));
        }
        synchronized (getClassLoadingLock(name)) {
            if (poison.get() != null) throw new Failure("INSTANCE_TAINTED", "An earlier definition failed; restart required", poison.get());
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) loaded = findClass(name);
            if (resolve) {
                try { resolveClass(loaded); }
                catch (LinkageError failed) { throw definitionFailure(name, failed); }
            }
            return loaded;
        }
    }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        ClassIndex.Entry entry = index.entry(name);
        Generated generated = entry == null && generator != null ? generator.apply(name) : null;
        if (entry == null && generated == null) throw new ClassNotFoundException(name + " has no admitted game-domain owner");
        try {
            byte[] bytes;
            Archive owner = entry == null ? generated.owner() : entry.archive();
            Deque<String> targets = transforming.get();
            targets.addLast(name);
            try {
                if (generated == null) bytes = transforms.apply(name, entry.bytes(), index::original, audit);
                else {
                    if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.") || name.startsWith("org.neoforbric.") || ClassIndex.shared(name)) throw new Failure("PROTECTED_PACKAGE", "Synthetic class cannot define " + name);
                    bytes = generated.bytes(); ClassIndex.validateName(name, bytes);
                    audit.record("GAME", "generated-class-owner", name, Map.of("generator", generated.generator(), "source", owner.path().toString(), "finalSha256", Archive.sha256(bytes)));
                }
            }
            finally { targets.removeLast(); if (targets.isEmpty()) transforming.remove(); }
            int separator = name.lastIndexOf('.');
            if (separator > 0) definePackage(name.substring(0, separator), owner);
            CodeSource source = new CodeSource(owner.codeSource(), (java.security.cert.Certificate[]) null);
            Class<?> type = defineClass(name, bytes, 0, bytes.length, source);
            audit.record("GAME", "class-defined", name, Map.of("loader", "G", "module", "unnamed", "source", owner.path().toString(),
                    "archiveSha256", owner.hash(), "finalSha256", Archive.sha256(bytes)));
            return type;
        } catch (Exception | Error failed) {
            Failure.rethrowFatal(failed);
            throw definitionFailure(name, failed);
        }
    }

    /** JVM package sealing: a sealed package only accepts classes from the archive that declared the seal. */
    private void definePackage(String packageName, Archive owner) {
        synchronized (this) {
            URL sealBase = owner.seals(packageName) ? owner.codeSource() : null;
            Package defined = getDefinedPackage(packageName);
            if (defined == null) {
                definePackage(packageName, null, null, null, null, null, null, sealBase);
                return;
            }
            if (defined.isSealed()) {
                if (sealBase == null || !defined.isSealed(sealBase))
                    throw new Failure("PACKAGE_SEALED", "Sealed package " + packageName + " rejects " + owner.path());
            } else if (sealBase != null) {
                throw new Failure("PACKAGE_SEALED", "Package " + packageName + " is already loaded unsealed and cannot be sealed by " + owner.path());
            }
        }
    }

    private Failure definitionFailure(String name, Throwable failed) {
        Failure failure = failed instanceof Failure f ? f : new Failure("CLASS_DEFINITION", "Cannot define / resolve " + name, failed);
        poison.compareAndSet(null, failure);
        audit.record("FAILED", "definition-failed", name, Map.of("code", failure.code(), "message", failure.getMessage(),
                "severity", "INSTANCE_FATAL", "stateTainted", "true"));
        return failure;
    }

    @Override public URL getResource(String name) {
        if (closed) return null;
        if (sharedResource(name)) return getParent().getResource(name);
        List<URL> resources = ownedResources(name);
        return resources.isEmpty() ? null : resources.getFirst();
    }
    // Module.getResourceAsStream uses this overload, bypassing getResource.
    // All admitted types belong to this loader's unnamed module.
    @Override protected URL findResource(String moduleName, String name) {
        return moduleName == null ? getResource(name) : null;
    }
    @Override public Enumeration<URL> getResources(String name) {
        if (closed) return Collections.emptyEnumeration();
        if (sharedResource(name)) {
            try { return getParent().getResources(name); }
            catch (java.io.IOException failed) { throw new Failure("RESOURCE_IO", "Cannot enumerate " + name, failed); }
        }
        return Collections.enumeration(ownedResources(name));
    }
    private List<URL> ownedResources(String name) { return filesystemResources == null ? index.resources(name) : index.resources(name, filesystemResources); }
    private static boolean sharedResource(String name) {
        return name.startsWith("org/neoforbric/api/") || name.startsWith("org/objectweb/asm/") || name.startsWith("net/fabricmc/api/");
    }
    @Override public void close() { closed = true; }
}
