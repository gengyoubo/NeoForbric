package org.neoforbric.bootstrap;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.neoforbric.api.ModInitializer;
import org.neoforbric.api.GameHooks;
import org.neoforbric.api.ServerHooks;
import org.neoforbric.api.ClientHooks;
import java.util.concurrent.CountDownLatch;
import org.neoforbric.loader.*;
import org.neoforbric.minecraft.*;
import org.neoforbric.fabric.NativeFabricRuntime;
import org.neoforbric.api.FabricRuntimeHooks;
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
        CountDownLatch completed = new CountDownLatch(1);
        Thread[] shutdownHook = {null};
        ModCatalog catalog = null;
        List<Path> remapArtifacts = new ArrayList<>();
        NativeFabricRuntime fabricRuntime = null;
        try {
            audit.mode(options.inspect() ? "metadata-inspect" : options.client() ? "minecraft-1.21.1-client" : options.runServer() ? "minecraft-1.21.1-server" : options.minecraft() ? "minecraft-1.21.1-server-settings" : "java-fixture");
            audit.record(phase, "runtime", "JVM", Map.of("javaVersion", Runtime.version().toString(),
                    "javaVendor", System.getProperty("java.vendor"), "targetMinecraft", "1.21.1", "inspect", Boolean.toString(options.inspect())));
            if (Runtime.version().feature() != 21) throw new Failure("JAVA_VERSION", "Prototype requires Java 21, got " + Runtime.version());
            if (options.runServer()) {
                Properties eula = new Properties(); Path path = Path.of("eula.txt").toAbsolutePath();
                if (Files.exists(path)) try (var stream = Files.newInputStream(path)) { eula.load(stream); }
                if (!Boolean.parseBoolean(eula.getProperty("eula", "false").trim()))
                    throw new Failure("EULA_REQUIRED", "Read https://aka.ms/MinecraftEULA and set eula=true in " + path + " before running a world");
                audit.record(phase, "eula", path.toString(), Map.of("accepted", "true"));
            }
            phase("DISCOVER");
            var candidates = Discovery.discover(options.mods(), audit);
            boolean hasFabricMods = candidates.stream().anyMatch(candidate -> candidate.metadata().ecosystem() == Metadata.Ecosystem.FABRIC);
            RuntimeInputs runtime = null;
            if (options.client() && hasFabricMods && !Boolean.getBoolean("neoforbric.fabric.plain")) {
                fabricRuntime = new NativeFabricRuntime(options, audit);
                runtime = RuntimeInputs.read(options.runtime(), audit);
                candidates = fabricRuntime.discover(candidates, runtime);
            }
            if (options.client()) catalog = new ModCatalog(candidates, audit);
            if (options.inspect()) {
                phase("INSPECTED");
                outcome = "INSPECTED";
                return;
            }
            phase("RESOLVE");
            if (options.client() && fabricRuntime == null) candidates = catalog.selectClient(candidates);
            else if (options.minecraft() && fabricRuntime == null) candidates = candidates.stream().map(c -> c.metadata().available(options.side()) ? FabricAdmission.admit(c) : c).toList();
            var mods = fabricRuntime == null ? Resolver.resolve(candidates, options.side(), audit, options.minecraft()) : fabricRuntime.resolve();
            if (fabricRuntime != null) catalog.selectFabricRuntime(candidates, mods, fabricRuntime.exclusions());
            phase("PREPARE");
            if (runtime == null) runtime = options.minecraft() ? RuntimeInputs.read(options.runtime(), audit) : null;
            if (runtime != null && !runtime.side().equals(options.side())) throw new Failure("GAME_SIDE", "Runtime inputs belong to " + runtime.side() + ", requested " + options.side());
            Archive game = runtime == null ? Archive.read(options.game()) : Archive.readRuntimeGame(runtime.game());
            audit.record(phase, "game-input", game.path().toString(), Map.of("sha256", game.hash()));
            List<Archive> inputs = new ArrayList<>();
            inputs.add(game);
            Set<Path> libraries = new HashSet<>();
            Set<Path> clientUi = new HashSet<>();
            if (runtime != null) {
                for (Path library : runtime.libraries()) { Archive archive = Archive.read(library); inputs.add(archive); libraries.add(archive.path()); }
                pipeline.add(new RegistryWindowHook(runtime.registryClassSha256(), fabricRuntime != null && fabricRuntime.defersRegistries()));
                if (options.client()) pipeline.add(new ClientLifecycleHook(Archive.sha256(game.read("net/minecraft/client/Minecraft.class")), Archive.sha256(game.read("net/minecraft/client/main/Main.class"))));
                if (options.clientUi() != null) {
                    Archive ui = Archive.read(options.clientUi()); inputs.add(ui); clientUi.add(ui.path());
                    audit.record(phase, "kernel-client-ui", ui.path().toString(), Map.of("sha256", ui.hash(), "loader", "G"));
                    pipeline.add(new TitleScreenHook(Archive.sha256(game.read("net/minecraft/client/gui/screens/TitleScreen.class"))));
                }
                if (options.runServer()) pipeline.add(new ServerLifecycleHook(Archive.sha256(game.read("net/minecraft/server/MinecraftServer.class")), Archive.sha256(game.read("net/minecraft/server/Main.class"))));
            }
            List<Discovery.Candidate> preparedMods = new ArrayList<>();
            if (fabricRuntime != null) {
                List<GamePreparation.FabricInput> remapInputs = new ArrayList<>();
                for (var mod : mods) {
                    Path cache = options.runtime().toAbsolutePath().getParent().resolve("remapped-mods").resolve(mod.archive().hash()); Files.createDirectories(cache);
                    String launch = UUID.randomUUID().toString(); Path source = cache.resolve("input-" + launch + ".jar"), mapped = cache.resolve("mod-" + launch + ".jar");
                    remapArtifacts.addAll(List.of(source, mapped, mapped.resolveSibling(mapped.getFileName() + ".remapping.jar"), mapped.resolveSibling(mapped.getFileName() + ".part")));
                    Files.write(source, mod.archive().snapshot()); remapInputs.add(new GamePreparation.FabricInput(source, mapped, fabricRuntime.accessRules(mod)));
                }
                GamePreparation.remapFabricMods(remapInputs, runtime, options.runtime().toAbsolutePath().getParent().resolve("fabric-remap-cache"));
                for (int i = 0; i < mods.size(); i++) {
                    var mod = mods.get(i); Archive mapped = Archive.read(remapInputs.get(i).output()).permitDeclaredNested(fabricRuntime.nestedPaths(mod));
                    preparedMods.add(new Discovery.Candidate(mapped, mod.metadata()));
                    if (fabricRuntime.bundledLibrary(mod)) {
                        libraries.add(mapped.path());
                        audit.record(phase, "bundled-library", mod.metadata().id(), Map.of("source", mapped.path().toString(), "loader", "G"));
                    }
                    audit.record(phase, "mod-remap", mod.metadata().id(), Map.of("sourceSha256", mod.archive().hash(), "outputSha256", mapped.hash(), "from", "intermediary", "to", "mojang", "mixinReferences", "manifest-static-or-refmap", "accessRules", "mojang"));
                }
                fabricRuntime.install(preparedMods, runtime, pipeline);
            } else for (var mod : mods) {
                if (runtime != null && mod.metadata().ecosystem() == Metadata.Ecosystem.FABRIC) {
                    // Remap the immutable discovered snapshot, never a later disk revision of the mod.
                    Path cache = options.runtime().toAbsolutePath().getParent().resolve("remapped-mods").resolve(mod.archive().hash()); Files.createDirectories(cache);
                    String launch = UUID.randomUUID().toString();
                    Path source = cache.resolve("input-" + launch + ".jar"), mapped = cache.resolve("mod-" + launch + ".jar");
                    // Parallel client/server launches must never share a remapper's temporary output.
                    remapArtifacts.addAll(List.of(source, mapped, mapped.resolveSibling(mapped.getFileName() + ".remapping.jar"), mapped.resolveSibling(mapped.getFileName() + ".part")));
                    mod.archive().requireSupportedLayout(); Files.write(source, mod.archive().snapshot());
                    GamePreparation.remap(source, mapped, runtime, "intermediary", "mojang");
                    Archive archive = Archive.read(mapped);
                    audit.record(phase, "mod-remap", mod.metadata().id(), Map.of("sourceSha256", mod.archive().hash(), "outputSha256", archive.hash(), "from", "intermediary", "to", "mojang"));
                    preparedMods.add(new Discovery.Candidate(archive, mod.metadata()));
                } else preparedMods.add(mod);
            }
            preparedMods.forEach(m -> inputs.add(m.archive()));
            ClassLoader parent = Bootstrap.class.getClassLoader();
            ClassIndex index = ClassIndex.prepare(inputs, parent, audit, libraries, clientUi);
            try (GameResources resources = options.minecraft() ? new GameResources(audit) : null;
                 GameClassLoader loader = new GameClassLoader(index, pipeline, parent, audit, resources)) {
                if (fabricRuntime != null) {
                    NativeFabricRuntime generatedFabric = fabricRuntime;
                    loader.generatedClasses(name -> generatedFabric.generated(name, index));
                }
                pipeline.seal(audit);
                phase("SEALED");
                loader.open();
                Thread thread = Thread.currentThread();
                ClassLoader previous = thread.getContextClassLoader();
                java.io.PrintStream previousOut = System.out, previousErr = System.err;
                String previousLwjgl = System.getProperty("org.lwjgl.librarypath");
                try {
                    thread.setContextClassLoader(loader);
                    if (options.client()) System.setProperty("org.lwjgl.librarypath", runtime.natives().toString());
                    if (fabricRuntime != null) fabricRuntime.bindAndPrepare(index, loader, pipeline, inputs);
                    // Check every entrypoint shape before executing any candidate static initializer / constructor.
                    List<Initializer> initializers = new ArrayList<>();
                    if (fabricRuntime == null) for (var mod : preparedMods) {
                        if (mod.metadata().ecosystem() == Metadata.Ecosystem.FABRIC) {
                            for (var entry : FabricAdmission.entries(mod, options.side())) initializers.add(preflight(mod.metadata().id(), entry.group(), entry.className(), Class.forName(entry.api(), false, parent), entry.method(), loader));
                        } else initializers.add(preflight(mod.metadata().id(), "prototype", mod.metadata().entrypoint(), ModInitializer.class, "onInitialize", loader));
                    }
                    Class<?> mainType = Class.forName(options.mainClass(), false, loader);
                    Method main = mainType.getMethod("main", String[].class);
                    if (!Modifier.isPublic(mainType.getModifiers()) || !Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class)
                        throw new Failure("MAIN_TYPE", options.mainClass() + " requires public static void main(String[])");
                    Method verifier = null;
                    Method titleUi = options.clientUi() == null ? null : Class.forName("org.neoforbric.client.NeoForbricClientUi", false, loader).getMethod("onTitleScreen", Object.class);
                    if (options.verifier() != null) {
                        Class<?> verifyType = Class.forName(options.verifier(), false, loader);
                        if (options.runServer() || options.client()) {
                            try { verifier = verifyType.getMethod(options.client() ? "verifyClient" : "verifyServer", Object.class); }
                            catch (NoSuchMethodException ordinaryVerifier) { verifier = verifyType.getMethod("verify"); }
                        } else verifier = verifyType.getMethod("verify");
                        if (!Modifier.isPublic(verifyType.getModifiers()) || !Modifier.isStatic(verifier.getModifiers()) || verifier.getReturnType() != void.class)
                            throw new Failure("VERIFIER_TYPE", "Verifier requires public static void verify()");
                    }
                    if (runtime == null) {
                        initialize(initializers, initialized); invokeMain(main, options);
                    } else {
                        ModCatalog clientCatalog = catalog;
                        NativeFabricRuntime activeFabric = fabricRuntime;
                        try (GameHooks.Session hooks = GameHooks.attach(() -> {
                            phase("REGISTRY_OPEN"); audit.record(phase, "registry-window", "builtin", Map.of("state", "open", "anchor", activeFabric != null && activeFabric.defersRegistries() ? "minecraft-before-gameThread-fabric-deferred-freeze" : "createContents-before-freeze"));
                            if (activeFabric == null) initialize(initializers.stream().filter(i -> i.group().equals("main") || i.group().equals("prototype")).toList(), initialized);
                            else activeFabric.initializeMain();
                        }, () -> {
                            phase("REGISTRY_FROZEN"); audit.record(phase, "registry-window", "builtin", Map.of("state", "frozen", "freeze", "vanilla"));
                            if (activeFabric == null) {
                                initialize(initializers.stream().filter(i -> i.group().equals(options.side())).toList(), initialized);
                                if (clientCatalog != null) clientCatalog.loaded(mods);
                            }
                        })) {
                            if (options.client()) {
                                Method menuVerifier = verifier;
                                try (AutoCloseable fabricClient = activeFabric == null ? () -> {} : FabricRuntimeHooks.attachClient(instance -> {
                                    activeFabric.prepareClient(instance);
                                    if (activeFabric.defersRegistries()) GameHooks.beforeRegistryFreeze();
                                    activeFabric.initializeClient(instance); clientCatalog.loaded(mods);
                                }); ClientHooks.Session client = ClientHooks.attach(state -> {
                                    audit.record("CLIENT", "client-lifecycle", state, Map.of("thread", Thread.currentThread().getName()));
                                    if (state.equals("main-menu")) {
                                        try { audit.write(options.audit(), "RUNNING"); }
                                        catch (IOException error) { throw new Failure("AUDIT_IO", "Could not write client audit", error); }
                                    }
                                }, instance -> {
                                    if (menuVerifier != null) {
                                        try { menuVerifier.invoke(null, menuVerifier.getParameterCount() == 0 ? new Object[0] : new Object[]{instance}); }
                                        catch (ReflectiveOperationException error) { throw new Failure("CLIENT_VERIFY", "Main menu verifier failed", error instanceof InvocationTargetException wrapper ? wrapper.getCause() : error); }
                                        audit.record("CLIENT", "verification-complete", options.verifier(), Map.of("loader", "G", "thread", Thread.currentThread().getName()));
                                    }
                                }, options.stopAfterFrames(), screen -> {
                                    if (titleUi != null) {
                                        try { titleUi.invoke(null, screen); }
                                        catch (ReflectiveOperationException error) { throw new Failure("CLIENT_UI", "Title screen UI hook failed", error instanceof InvocationTargetException wrapper ? wrapper.getCause() : error); }
                                        audit.record("CLIENT", "title-ui-initialized", "Mods", Map.of("loader", "G"));
                                    }
                                })) {
                                    invokeMain(main, options); hooks.verifyComplete(); client.verifyComplete();
                                    audit.record("CLIENT", "client-complete", "Minecraft", Map.of("framesAfterMenu", Integer.toString(client.frames())));
                                }
                            } else if (options.runServer()) {
                                Method firstTickVerifier = verifier;
                                try (ServerHooks.Session server = ServerHooks.attach(state -> {
                                    audit.record("SERVER", "server-lifecycle", state, Map.of("thread", Thread.currentThread().getName()));
                                    if (state.equals("first-tick")) {
                                        try { audit.write(options.audit(), "RUNNING"); }
                                        catch (IOException error) { throw new Failure("AUDIT_IO", "Could not write running server audit", error); }
                                    }
                                }, instance -> {
                                    if (firstTickVerifier != null) {
                                        try { firstTickVerifier.invoke(null, firstTickVerifier.getParameterCount() == 0 ? new Object[0] : new Object[]{instance}); }
                                        catch (ReflectiveOperationException error) { throw new Failure("SERVER_VERIFY", "First tick verifier failed", error instanceof InvocationTargetException wrapper ? wrapper.getCause() : error); }
                                        audit.record("SERVER", "verification-complete", options.verifier(), Map.of("loader", "G", "thread", Thread.currentThread().getName()));
                                    }
                                }, options.stopAfterTicks())) {
                                    shutdownHook[0] = new Thread(() -> {
                                        server.requestStop();
                                        boolean interrupted = false;
                                        for (;;) {
                                            try { completed.await(); break; }
                                            catch (InterruptedException ignored) { interrupted = true; }
                                        }
                                        if (interrupted) Thread.currentThread().interrupt();
                                    }, "NeoForbric shutdown");
                                    shutdownHook[0].setContextClassLoader(parent);
                                    Runtime.getRuntime().addShutdownHook(shutdownHook[0]);
                                    invokeMain(main, options); hooks.verifyComplete();
                                    phase("SERVER_RUNNING"); server.await();
                                    audit.record("SERVER", "server-complete", "Minecraft", Map.of("ticks", Integer.toString(server.ticks()), "loaderClosedAfterThread", "true"));
                                }
                            } else { invokeMain(main, options); hooks.verifyComplete(); }
                            if (!options.runServer() && !options.client() && verifier != null) {
                                phase("VERIFY"); verifier.invoke(null);
                                audit.record(phase, "verification-complete", options.verifier(), Map.of("loader", "G"));
                            }
                        }
                    }
                } finally {
                    if (options.client()) {
                        if (previousLwjgl == null) System.clearProperty("org.lwjgl.librarypath"); else System.setProperty("org.lwjgl.librarypath", previousLwjgl);
                    }
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
            if (catalog != null) catalog.failed(failure.getMessage());
            boolean defined = audit.events().stream().anyMatch(e -> e.type().equals("class-defined"));
            audit.record(phase, "failure", "launch", Map.of("code", failure.code(), "message", failure.getMessage(), "severity", "INSTANCE_FATAL",
                    "stateTainted", Boolean.toString(defined || !initialized.isEmpty()), "initializedMods", initialized.toString(), "recovery", "restart"));
            phase("FAILED");
            Failure.rethrowFatal(cause);
            throw failure;
        } finally {
            if (fabricRuntime != null) {
                try { fabricRuntime.close(); }
                catch (Exception error) { audit.record("CLEANUP", "fabric-runtime-close-failed", "adapter", Map.of("severity", "RESOURCE_WARNING", "message", error.toString())); }
            }
            for (Path artifact : remapArtifacts) {
                try { Files.deleteIfExists(artifact); }
                catch (IOException error) { audit.record("CLEANUP", "remap-cleanup-failed", artifact.toString(), Map.of("message", error.toString(), "severity", "RESOURCE_WARNING")); }
            }
            try { audit.write(options.audit(), outcome); }
            catch (IOException io) {
                if (failure != null) failure.addSuppressed(io);
                else throw new Failure("AUDIT_IO", "Could not write audit " + options.audit(), io);
            } finally {
                if (catalog != null) catalog.close();
                completed.countDown();
                if (shutdownHook[0] != null) {
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook[0]); }
                    catch (IllegalStateException shutdownInProgress) { /* Shutdown hook waits for this final report. */ }
                }
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
