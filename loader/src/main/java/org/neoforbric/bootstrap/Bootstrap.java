package org.neoforbric.bootstrap;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.neoforbric.api.ModInitializer;
import org.neoforbric.loader.*;

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
            var mods = Resolver.resolve(candidates, options.side(), audit);
            phase("PREPARE");
            Archive game = Archive.read(options.game());
            audit.record(phase, "game-input", game.path().toString(), Map.of("sha256", game.hash()));
            List<Archive> inputs = new ArrayList<>();
            inputs.add(game);
            mods.forEach(m -> inputs.add(m.archive()));
            ClassLoader parent = Bootstrap.class.getClassLoader();
            ClassIndex index = ClassIndex.prepare(inputs, parent, audit);
            try (GameClassLoader loader = new GameClassLoader(index, pipeline, parent, audit)) {
                pipeline.seal(audit);
                phase("SEALED");
                loader.open();
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                try {
                    thread.setContextClassLoader(loader);
                    // Check every entrypoint shape before executing any candidate static initializer / constructor.
                    Map<String, Constructor<?>> constructors = new LinkedHashMap<>();
                    for (var mod : mods) {
                        Class<?> type = Class.forName(mod.metadata().entrypoint(), false, loader);
                        if (!ModInitializer.class.isAssignableFrom(type) || !Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers()))
                            throw new Failure("ENTRYPOINT_TYPE", mod.metadata().id() + " must implement the shared ModInitializer API as a public concrete class");
                        constructors.put(mod.metadata().id(), type.getConstructor());
                    }
                    Class<?> mainType = Class.forName(options.mainClass(), false, loader);
                    Method main = mainType.getMethod("main", String[].class);
                    if (!Modifier.isPublic(mainType.getModifiers()) || !Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class)
                        throw new Failure("MAIN_TYPE", options.mainClass() + " requires public static void main(String[])");
                    phase("MOD_INIT");
                    for (var constructor : constructors.entrySet()) {
                        audit.record(phase, "entrypoint-start", constructor.getKey(), Map.of("loader", "G"));
                        ((ModInitializer) constructor.getValue().newInstance()).onInitialize();
                        initialized.add(constructor.getKey());
                        audit.record(phase, "entrypoint-complete", constructor.getKey(), Map.of("loader", "G"));
                    }
                    phase("GAME_MAIN");
                    audit.record(phase, "main-start", options.mainClass(), Map.of("loader", "G", "argumentCount", Integer.toString(options.gameArguments().size())));
                    main.invoke(null, (Object) options.gameArguments().toArray(String[]::new));
                    audit.record(phase, "main-complete", options.mainClass(), Map.of("loader", "G"));
                } finally {
                    thread.setContextClassLoader(previous);
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

    private void phase(String next) {
        audit.record(next, "phase", next, Map.of("previous", phase));
        phase = next;
    }
}
