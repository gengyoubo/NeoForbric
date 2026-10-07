package org.neoforbric.loader;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.neoforbric.fabric.NativeFabricRuntime;

/** The sole defining loader for all admitted game and mod types. Parent access is an allowlist. */
public final class GameClassLoader extends URLClassLoader implements Closeable {
    static { registerAsParallelCapable(); }
    // Kept for diagnostics/probes; URLClassLoader has no configurable name.
    @Override public String getName() { return "NeoForbric-Game"; }
    private final ClassIndex index;
    private final TransformPipeline transforms;
    private final AuditLog audit;
    private final GameResources filesystemResources;
    private final AtomicReference<Failure> poison = new AtomicReference<>();
    // Byte-provider URLs added at runtime, e.g. Fabric-ASM (mm / Shedaniel) generated Mixin
    // blobs. The loader owns bytes through the index, but Fabric-ASM hands Mixin a custom URL
    // that the game loader must expose so generated Mixin classes can be read and defined.
    private final List<URL> addedInputs = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Deque<String>> transforming = ThreadLocal.withInitial(ArrayDeque::new);
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile Set<String> moduleNames = Set.of();
    private Map<java.nio.file.Path, java.util.jar.Manifest> packageManifests = Map.of();
    /** Verified pre-normalization package metadata; never changes class/resource ownership. */
    public synchronized void packageManifests(Map<java.nio.file.Path, byte[]> manifests) throws IOException {
        if (ready) throw new Failure("LOADER_STATE", "Package metadata must be bound before opening G");
        Map<java.nio.file.Path, java.util.jar.Manifest> parsed = new HashMap<>();
        for (var entry : manifests.entrySet()) parsed.put(entry.getKey().toRealPath(), new java.util.jar.Manifest(new java.io.ByteArrayInputStream(entry.getValue())));
        packageManifests = Map.copyOf(parsed);
    }
    public synchronized void moduleNames(Set<String> names) { moduleNames = Set.copyOf(names); }
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
        super(new URL[0], parent);
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

    @Override protected Class<?> findClass(String moduleName, String name) {
        try { return loadClass(name); }
        catch (ClassNotFoundException absent) { return null; }
    }

    /** Fabric-ASM reflects for exactly this signature to register its generated Mixin byte source. */
    public void addURL(URL url) { if (url != null) addedInputs.add(url); }
    /** Exposes an input JAR to classpath scanners (Reflections, ClassGraph) without changing ownership. */
    public void addClasspath(URL url) { if (url != null) super.addURL(url); }
    /** Resolves a slash-form resource name against the runtime byte-provider URLs, or null. */
    public URL addedResource(String name) {
        for (URL base : addedInputs) {
            try {
                URL candidate = new URL(base, name);
                if (candidate.openConnection() != null) return candidate;
            } catch (IOException | RuntimeException absent) { /* provider does not hold this entry */ }
        }
        return null;
    }
    /** Generated class bytes for {code name} (dotted or slashed) served by an added URL, or null. */
    public byte[] addedClassBytes(String name) {
        URL url = addedResource(name.replace('.', '/') + ".class");
        if (url == null) return null;
        try (InputStream in = url.openStream()) { return in.readAllBytes(); }
        catch (IOException unreadable) { return null; }
    }

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
            boolean metadataTool = ((NativeFabricRuntime.active() && "fabric-runtime-mixin".equals(transforms.activeRule()))
                    || (org.neoforbric.neoforge.NeoForgeMixins.active() && Set.of("neoforge-mixin", "neoforge-enum-extension").contains(transforms.activeRule()))
                    || (org.neoforbric.forge.ForgeMixins.active() && "forge-mixin".equals(transforms.activeRule()))
                    || Set.of("forge-native-plugins", "forge-coremods").contains(transforms.activeRule())) && !targets.contains(name);
            if (!metadataTool) throw new Failure("REENTRANT_DEFINITION", "Transformer for " + targets.getLast() + " requested class " + name + "; use bytecode access");
            audit.record("PREPARE", "mixin-plugin-class", name, Map.of("requestingTarget", targets.getLast(), "loader", "G"));
        }
        // Native plugins may load a superclass while the serial transformation
        // pipeline is held. Take that monitor before any per-class monitor, so
        // another loading worker cannot hold the superclass lock while waiting
        // for the pipeline. The monitor is reentrant for plugin dependencies.
        synchronized (transforms) {
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
    }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        ClassIndex.Entry entry = index.entry(name);
        Generated generated = entry == null && generator != null ? generator.apply(name) : null;
        if (entry == null && generated == null) return defineAdded(name);
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
            audit.record("GAME", "class-defined", name, Map.of("loader", "G", "module", type.getModule().isNamed() ? type.getModule().getName() : "unnamed", "source", owner.path().toString(),
                    "archiveSha256", owner.hash(), "finalSha256", Archive.sha256(bytes)));
            return type;
        } catch (Exception | Error failed) {
            Failure.rethrowFatal(failed);
            throw definitionFailure(name, failed);
        }
    }

    /** Defines a runtime-generated class (e.g. an mm Mixin blob) from an added URL byte source. */
    private Class<?> defineAdded(String name) throws ClassNotFoundException {
        URL url = addedResource(name.replace('.', '/') + ".class");
        if (url == null) throw new ClassNotFoundException(name + " has no admitted game-domain owner");
        byte[] bytes;
        try (InputStream in = url.openStream()) { bytes = in.readAllBytes(); }
        catch (IOException unreadable) { throw new ClassNotFoundException(name, unreadable); }
        ClassIndex.validateName(name, bytes);
        int separator = name.lastIndexOf('.');
        if (separator > 0) {
            String packageName = name.substring(0, separator);
            synchronized (this) {
                if (getDefinedPackage(packageName) == null) definePackage(packageName, null, null, null, null, null, null, null);
            }
        }
        audit.record("GAME", "class-defined", name, Map.of("loader", "G", "module", "unnamed", "source", url.toString(), "finalSha256", Archive.sha256(bytes)));
        return defineClass(name, bytes, 0, bytes.length, new CodeSource(url, (java.security.cert.Certificate[]) null));
    }

    /** JVM package sealing: a sealed package only accepts classes from the archive that declared the seal. */
    private void definePackage(String packageName, Archive owner) {
        synchronized (this) {
            URL sealBase = owner.seals(packageName) ? owner.codeSource() : null;
            Package defined = getDefinedPackage(packageName);
            if (defined == null) {
                definePackage(packageName, packageAttribute(owner, packageName, "Specification-Title"), packageAttribute(owner, packageName, "Specification-Version"),
                        packageAttribute(owner, packageName, "Specification-Vendor"), packageAttribute(owner, packageName, "Implementation-Title"),
                        packageAttribute(owner, packageName, "Implementation-Version"), packageAttribute(owner, packageName, "Implementation-Vendor"), sealBase);
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
    private String packageAttribute(Archive owner, String name, String key) {
        var manifest = packageManifests.get(owner.path()); if (manifest == null) return owner.packageAttribute(name, key);
        var entry = manifest.getAttributes(name.replace('.', '/') + "/"); String value = entry == null ? null : entry.getValue(key);
        return value == null ? manifest.getMainAttributes().getValue(key) : value;
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
        return resources.isEmpty() ? addedResource(name) : resources.getFirst();
    }
    // Module.getResourceAsStream uses this overload, bypassing getResource.
    // NeoForge's named modules use the same admitted resource ownership index.
    @Override protected URL findResource(String moduleName, String name) {
        return moduleName == null || moduleNames.contains(moduleName) ? getResource(name) : null;
    }
    @Override public Enumeration<URL> getResources(String name) {
        if (closed) return Collections.emptyEnumeration();
        if (sharedResource(name)) {
            try { return getParent().getResources(name); }
            catch (java.io.IOException failed) { throw new Failure("RESOURCE_IO", "Cannot enumerate " + name, failed); }
        }
        List<URL> resources = new ArrayList<>(ownedResources(name));
        URL added = addedResource(name);
        if (added != null) resources.add(added);
        return Collections.enumeration(resources);
    }
    private List<URL> ownedResources(String name) { return filesystemResources == null ? index.resources(name) : index.resources(name, filesystemResources); }
    private static boolean sharedResource(String name) {
        return name.startsWith("org/neoforbric/api/") || name.startsWith("org/objectweb/asm/") || name.startsWith("net/fabricmc/api/");
    }
    @Override public void close() { closed = true; }
}
