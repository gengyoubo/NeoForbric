package org.neoforbric.fabric;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.api.*;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricMixinBootstrap;
import net.fabricmc.loader.impl.transformer.FabricTransformer;
import org.neoforbric.bootstrap.LaunchOptions;
import org.neoforbric.loader.*;
import org.neoforbric.minecraft.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

/** One process, one passive Fabric facade; NeoForbric owns discovery, preparation, G and Minecraft main. */
public final class NativeFabricRuntime implements AutoCloseable {
    private static volatile boolean active;
    public static boolean active() { return active; }
    private final NeoGameProvider provider;
    private final NeoFabricLauncher launcher;
    private final FabricLoaderImpl facade;
    private final AuditLog audit;
    private FabricRuntimePlan plan;
    private org.spongepowered.asm.mixin.extensibility.IRemapper mixinRemapper;
    private boolean mainInvoked, clientInvoked;
    public NativeFabricRuntime(LaunchOptions options, AuditLog audit) {
        if (!options.client()) throw new Failure("FABRIC_RUNTIME_SCOPE", "Initial experimental Fabric runtime is client-only");
        active = true;
        this.audit = audit; provider = new NeoGameProvider(options);
        launcher = new NeoFabricLauncher(EnvType.CLIENT, options.mainClass(), audit);
        facade = FabricLoaderImpl.INSTANCE; facade.setGameProvider(provider);
        audit.record("BOOTSTRAP", "fabric-runtime-owner", "NeoForbric", Map.of("fabricVersion", FabricLoaderImpl.VERSION, "nativeLauncher", "false", "namespace", "mojang"));
    }
    private static Path codeSource(Class<?> type) {
        try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (URISyntaxException error) { throw new Failure("TOOL_ORIGIN", type.getName(), error); }
    }
    public List<Discovery.Candidate> discover(List<Discovery.Candidate> roots, RuntimeInputs inputs) throws IOException {
        plan = new FabricRuntimePlan(provider.getLaunchDirectory().resolve(".neoforbric/nested"), EnvType.CLIENT, audit);
        List<Discovery.Candidate> all = plan.discover(roots);
        plan.builtin("minecraft", "1.21.1", List.of(inputs.game()));
        plan.builtin("java", "21", List.of(Path.of(System.getProperty("java.home"))));
        plan.builtin("fabricloader", FabricLoaderImpl.VERSION, List.of(codeSource(FabricLoaderImpl.class)));
        plan.builtin("mixinextras", "0.5.5", List.of(codeSource(MixinExtrasBootstrap.class)));
        plan.builtin("neoforbric", "0.1.0", List.of(codeSource(NativeFabricRuntime.class)));
        return all;
    }
    public List<Discovery.Candidate> resolve() { return plan.resolve(); }
    public Map<Path, String> exclusions() { return plan.exclusions(); }
    public boolean defersRegistries() { return plan.selectedNative().stream().anyMatch(mod -> mod.getId().equals("fabric-registry-sync-v0")); }
    public void prepareClient(Object minecraft) { facade.prepareModInit(provider.getLaunchDirectory(), minecraft); }
    public Set<String> nestedPaths(Discovery.Candidate candidate) { return plan.node(candidate).nestedPaths(); }
    public GameClassLoader.Generated generated(String name, ClassIndex index) { return NeoMixinService.generated(name, index); }
    public void install(List<Discovery.Candidate> prepared, RuntimeInputs inputs, TransformPipeline pipeline) throws IOException {
        Map<String, Discovery.Candidate> byId = new HashMap<>(); prepared.forEach(c -> byId.put(c.metadata().id(), c));
        for (var candidate : plan.selectedNative()) {
            if (byId.containsKey(candidate.getId())) candidate.setPaths(List.of(byId.get(candidate.getId()).archive().path()));
            NativeAccess.call(facade, FabricLoaderImpl.class, "addMod", new Class<?>[]{net.fabricmc.loader.impl.discovery.ModCandidateImpl.class}, candidate);
        }
        var mappingTree = GamePreparation.mappings(inputs.mappings(), inputs.intermediaryMappings());
        NativeAccess.set(facade, FabricLoaderImpl.class, "mappingResolver", new NeoMappingResolver(mappingTree));
        // The passive Fabric implementation is shaded against its own mapping-io ABI.
        // Serialize our verified tree so both implementations consume exactly the same mappings.
        StringWriter mappingText = new StringWriter();
        try (var writer = net.fabricmc.mappingio.MappingWriter.create(mappingText, net.fabricmc.mappingio.format.MappingFormat.TINY_2_FILE)) { mappingTree.accept(writer); }
        var nativeTree = new net.fabricmc.loader.impl.lib.mappingio.tree.MemoryMappingTree();
        net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny2FileReader.read(new StringReader(mappingText.toString()), nativeTree);
        mixinRemapper = new net.fabricmc.loader.impl.util.mappings.MixinIntermediaryDevRemapper(nativeTree, "intermediary", "mojang");
        NativeAccess.call(facade, FabricLoaderImpl.class, "setupLanguageAdapters", new Class<?>[0]);
        NativeAccess.call(facade, FabricLoaderImpl.class, "setupMods", new Class<?>[0]);
        NativeAccess.set(facade, FabricLoaderImpl.class, "frozen", true);
        facade.loadClassTweakers();
        Set<String> preceding = pipeline.registeredIds();
        pipeline.add(new TransformPipeline.Transformer() {
            @Override public String id() { return "fabric-runtime-access-and-environment"; }
            @Override public Set<String> after() { return preceding; }
            @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) { return FabricTransformer.transform(false, EnvType.CLIENT, context.name(), bytes); }
        });
        pipeline.add(new TransformPipeline.Transformer() {
            @Override public String id() { return "fabric-runtime-mixin"; }
            @Override public Set<String> after() { return Set.of("fabric-runtime-access-and-environment"); }
            @Override public byte[] transform(TransformPipeline.Context context, byte[] bytes) {
                // Fabric API also targets shipped libraries (e.g. DFU TaggedChoice) and
                // post-processes accessor interfaces in its own modules.
                return NeoMixinService.transform(context.name(), bytes);
            }
        });
        audit.record("PREPARE", "fabric-runtime-installed", "plan", Map.of("mods", Integer.toString(prepared.size()), "nativeLoadInvoked", "false", "nativeFreezeInvoked", "false"));
    }
    public void bindAndPrepare(ClassIndex index, GameClassLoader game, TransformPipeline pipeline, List<Archive> archives) {
        launcher.bind(index, game, pipeline, archives);
        System.setProperty("mixin.bootstrapService", NeoMixinBootstrap.class.getName());
        System.setProperty("mixin.service", NeoMixinService.class.getName());
        NeoMixinService.attach(audit);
        // FabricMixinBootstrap overwrites these properties with Knot. Select our service first;
        // its subsequent init call only registers configs and their Fabric compatibility metadata.
        org.spongepowered.asm.launch.MixinBootstrap.init();
        if (!(org.spongepowered.asm.service.MixinService.getService() instanceof NeoMixinService))
            throw new Failure("MIXIN_OWNERSHIP", "Mixin did not select the NeoForbric service");
        FabricMixinBootstrap.init(EnvType.CLIENT, facade);
        org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment().getRemappers().add(mixinRemapper);
        MixinExtrasBootstrap.init(); launcher.finishMixin();
        facade.prepareModInit(provider.getLaunchDirectory(), null);
        invoke("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
        audit.record("PREPARE", "fabric-runtime-ready", "plan", Map.of("mixinService", "NeoForbric", "loader", "G", "targetNamespace", "mojang"));
    }
    public void initializeMain() {
        if (mainInvoked) throw new Failure("DUPLICATE_INITIALIZATION", "Fabric main invoked twice"); mainInvoked = true;
        invoke("main", ModInitializer.class, ModInitializer::onInitialize);
    }
    public void initializeClient(Object minecraft) {
        if (!mainInvoked || clientInvoked) throw new Failure("FABRIC_LIFECYCLE", "Client initialization out of order"); clientInvoked = true;
        facade.prepareModInit(provider.getLaunchDirectory(), minecraft);
        invoke("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
    }
    private <T> void invoke(String group, Class<T> type, Consumer<T> callback) {
        for (var entry : facade.getEntrypointContainers(group, type)) {
            String id = entry.getProvider().getMetadata().getId();
            audit.record("MOD_INIT", "entrypoint-start", id, Map.of("group", group, "definition", entry.getDefinition(), "loader", "G"));
            try { callback.accept(entry.getEntrypoint()); }
            catch (Exception | Error failed) { Failure.rethrowFatal(failed); throw new Failure("FABRIC_ENTRYPOINT", id + " " + group + ": " + entry.getDefinition(), failed); }
            audit.record("MOD_INIT", "entrypoint-complete", id, Map.of("group", group, "loader", "G"));
        }
    }
    @Override public void close() {
        active = false;
        Set<FileSystem> filesystems = new HashSet<>();
        for (var mod : facade.getModsInternal()) for (Path path : mod.getRootPaths()) if (path.getFileSystem() != FileSystems.getDefault()) filesystems.add(path.getFileSystem());
        for (FileSystem filesystem : filesystems) {
            try { filesystem.close(); }
            catch (IOException error) { audit.record("CLEANUP", "fabric-filesystem-close-failed", "mod", Map.of("severity", "RESOURCE_WARNING", "message", error.toString())); }
        }
    }
}
