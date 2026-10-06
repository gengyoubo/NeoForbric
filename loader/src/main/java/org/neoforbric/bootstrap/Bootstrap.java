package org.neoforbric.bootstrap;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.neoforbric.api.ModInitializer;
import org.neoforbric.api.GameHooks;
import org.neoforbric.loader.*;
import org.neoforbric.minecraft.*;
import java.nio.file.*;

public final class Bootstrap {
    private final AuditLog audit = new AuditLog();
    private final TransformPipeline pipeline;
    private final AtomicBoolean used = new AtomicBoolean();
    private String phase = "BOOTSTRAP";
    public Bootstrap() { this(new TransformPipeline()); }
    public Bootstrap(TransformPipeline pipeline) { this.pipeline = Objects.requireNonNull(pipeline); }
    public AuditLog audit() { return audit; }

    public void run(LaunchOptions options) {
        if (!used.compareAndSet(false, true)) throw new Failure("LAUNCH_ALREADY_USED", "Each Bootstrap owns one launch; create a new instance/process");
        Failure failure = null;
        String outcome = "FAILED";
        List<String> initialized = new ArrayList<>();
        try {
            audit.mode(options.inspect() ? "metadata-inspect" : options.minecraft() ? "minecraft-1.21.1-server-settings" : "java-fixture");
            audit.record(phase, "runtime", "JVM", Map.of("javaVersion", Runtime.version().toString(),
                    "javaVendor", System.getProperty("java.vendor"), "targetMinecraft", "1.21.1", "inspect", Boolean.toString(options.inspect())));
            if (Runtime.version().feature() != 21) throw new Failure("JAVA_VERSION", "Prototype requires Java 21, got " + Runtime.version());
            phase("DISCOVER");
            var candidates = Discovery.discover(options.mods(), audit);
            if (options.inspect()) {
                phase("INSPECTED");
                outcome = "INSPECTED";
                return;
            }
            phase("RESOLVE");
            if (options.minecraft()) candidates = candidates.stream().map(c -> c.metadata().available(options.side()) ? FabricAdmission.admit(c) : c).toList();
            var mods = Resolver.resolve(candidates, options.side(), audit, options.minecraft());
            phase("PREPARE");
            RuntimeInputs runtime = options.minecraft() ? RuntimeInputs.read(options.runtime(), audit) : null;
            Archive game = Archive.read(runtime == null ? options.game() : runtime.game());
            audit.record(phase, "game-input", game.path().toString(), Map.of("sha256", game.hash()));
            List<Archive> inputs = new ArrayList<>();
            inputs.add(game);
            Set<Path> libraries = new HashSet<>();
            if (runtime != null) {
                for (Path library : runtime.libraries()) { Archive archive = Archive.read(library); inputs.add(archive); libraries.add(archive.path()); }
                pipeline.add(new RegistryWindowHook(runtime.registryClassSha256()));
            }
            List<Discovery.Candidate> preparedMods = new ArrayList<>();
            for (var mod : mods) {
                if (runtime != null && mod.metadata().ecosystem() == Metadata.Ecosystem.FABRIC) {
                    // Remap the immutable discovered snapshot, never a later disk revision of the mod.
                    Path cache = options.runtime().toAbsolutePath().getParent().resolve("remapped-mods").resolve(mod.archive().hash()); Files.createDirectories(cache);
                    Path source = cache.resolve("input-intermediary.jar"), mapped = cache.resolve("mod-mojang.jar");
                    mod.archive().requireSupportedLayout(); Files.write(source, mod.archive().snapshot());
                    GamePreparation.remap(source, mapped, runtime, "intermediary", "mojang");
                    Archive archive = Archive.read(mapped);
                    audit.record(phase, "mod-remap", mod.metadata().id(), Map.of("sourceSha256", mod.archive().hash(), "outputSha256", archive.hash(), "from", "intermediary", "to", "mojang"));
                    preparedMods.add(new Discovery.Candidate(archive, mod.metadata()));
                } else preparedMods.add(mod);
            }
            preparedMods.forEach(m -> inputs.add(m.archive()));
            ClassLoader parent = Bootstrap.class.getClassLoader();
            ClassIndex index = ClassIndex.prepare(inputs, parent, audit, libraries);
            try (GameClassLoader loader = new GameClassLoader(index, pipeline, parent, audit)) {
                pipeline.seal(audit);
                phase("SEALED");
                loader.open();
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                java.io.PrintStream previousOut = System.out, previousErr = System.err;
                try {
                    thread.setContextClassLoader(loader);
                    // Check every entrypoint shape before executing any candidate static initializer / constructor.
                    List<Initializer> initializers = new ArrayList<>();
                    for (var mod : preparedMods) {
                        if (mod.metadata().ecosystem() == Metadata.Ecosystem.FABRIC) {
                            for (var entry : FabricAdmission.entries(mod)) initializers.add(preflight(mod.metadata().id(), entry.group(), entry.className(), Class.forName(entry.api(), false, parent), entry.method(), loader));
                        } else initializers.add(preflight(mod.metadata().id(), "prototype", mod.metadata().entrypoint(), ModInitializer.class, "onInitialize", loader));
                    }
                    Class<?> mainType = Class.forName(options.mainClass(), false, loader);
                    Method main = mainType.getMethod("main", String[].class);
                    if (!Modifier.isPublic(mainType.getModifiers()) || !Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class)
                        throw new Failure("MAIN_TYPE", options.mainClass() + " requires public static void main(String[])");
                    Method verifier = null;
                    if (options.verifier() != null) {
                        Class<?> verifyType = Class.forName(options.verifier(), false, loader); verifier = verifyType.getMethod("verify");
                        if (!Modifier.isPublic(verifyType.getModifiers()) || !Modifier.isStatic(verifier.getModifiers()) || verifier.getReturnType() != void.class)
                            throw new Failure("VERIFIER_TYPE", "Verifier requires public static void verify()");
                    }
                    if (runtime == null) {
                        initialize(initializers, initialized); invokeMain(main, options);
                    } else {
                        try (GameHooks.Session hooks = GameHooks.attach(() -> {
                            phase("REGISTRY_OPEN"); audit.record(phase, "registry-window", "builtin", Map.of("state", "open", "anchor", "createContents-before-freeze"));
                            initialize(initializers.stream().filter(i -> !i.group().equals("server")).toList(), initialized);
                        }, () -> {
                            phase("REGISTRY_FROZEN"); audit.record(phase, "registry-window", "builtin", Map.of("state", "frozen", "freeze", "vanilla"));
                            initialize(initializers.stream().filter(i -> i.group().equals("server")).toList(), initialized);
                        })) {
                            invokeMain(main, options); hooks.verifyComplete();
                            if (verifier != null) {
                                phase("VERIFY"); verifier.invoke(null);
                                audit.record(phase, "verification-complete", options.verifier(), Map.of("loader", "G"));
                            }
                        }
                    }
                } finally {
                    thread.setContextClassLoader(previous);
                    if (options.minecraft()) {
                        System.setOut(previousOut); System.setErr(previousErr);
                        audit.record(phase, "stdio-restored", "JVM", Map.of("restored", "true"));
                    }
                    audit.record(phase, "tccl-restored", "launch-thread", Map.of("restored", "true"));
                }
            }
            phase("TERMINATED");
            outcome = "SUCCESS";
        } catch (Exception | Error failed) {
            Throwable cause = failed instanceof InvocationTargetException reflection ? reflection.getTargetException() : failed;
            failure = cause instanceof Failure known ? known : new Failure("LAUNCH_FAILED", phase + ": " + cause, cause);
            boolean defined = audit.events().stream().anyMatch(e -> e.type().equals("class-defined"));
            audit.record(phase, "failure", "launch", Map.of("code", failure.code(), "message", failure.getMessage(), "severity", "INSTANCE_FATAL",
                    "stateTainted", Boolean.toString(defined || !initialized.isEmpty()), "initializedMods", initialized.toString(), "recovery", "restart"));
            phase("FAILED");
            Failure.rethrowFatal(cause);
            throw failure;
        } finally {
            try { audit.write(options.audit(), outcome); }
            catch (IOException io) {
                if (failure != null) failure.addSuppressed(io);
                else throw new Failure("AUDIT_IO", "Could not write audit " + options.audit(), io);
            }
        }
    }

    private record Initializer(String id, String group, Constructor<?> constructor, Method method) {}
    private Initializer preflight(String id, String group, String name, Class<?> api, String method, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName(name, false, loader);
        if (!api.isAssignableFrom(type) || !Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers()))
            throw new Failure("ENTRYPOINT_TYPE", id + ": " + name + " must implement shared " + api.getName() + " as a public concrete class");
        Method initialize = type.getMethod(method);
        if (Modifier.isStatic(initialize.getModifiers()) || initialize.getReturnType() != void.class)
            throw new Failure("ENTRYPOINT_TYPE", id + ": initializer must be an instance void method");
        return new Initializer(id, group, type.getConstructor(), initialize);
    }
    private void initialize(List<Initializer> entries, List<String> initialized) {
        phase("MOD_INIT");
        for (var entry : entries) {
            audit.record(phase, "entrypoint-start", entry.id(), Map.of("loader", "G", "group", entry.group(), "class", entry.constructor().getDeclaringClass().getName()));
            try { entry.method().invoke(entry.constructor().newInstance()); }
            catch (ReflectiveOperationException failed) {
                Throwable cause = failed instanceof InvocationTargetException reflection ? reflection.getTargetException() : failed;
                Failure.rethrowFatal(cause);
                // Fixture callers historically receive LAUNCH_FAILED and the original cause.
                throw new Failure("LAUNCH_FAILED", "Entrypoint " + entry.id() + ": " + cause, cause);
            }
            initialized.add(entry.id());
            audit.record(phase, "entrypoint-complete", entry.id(), Map.of("loader", "G", "group", entry.group()));
        }
    }
    private void invokeMain(Method main, LaunchOptions options) throws ReflectiveOperationException {
        phase("GAME_MAIN");
        audit.record(phase, "main-start", options.mainClass(), Map.of("loader", "G", "argumentCount", Integer.toString(options.gameArguments().size())));
        main.invoke(null, (Object) options.gameArguments().toArray(String[]::new));
        audit.record(phase, "main-complete", options.mainClass(), Map.of("loader", "G"));
    }

    private void phase(String next) {
        audit.record(next, "phase", next, Map.of("previous", phase));
        phase = next;
    }
}
