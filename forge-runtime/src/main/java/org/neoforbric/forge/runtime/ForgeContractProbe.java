package org.neoforbric.forge.runtime;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import net.minecraftforge.common.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.*;
import net.minecraftforge.fml.config.*;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.forgespi.language.*;
import net.minecraftforge.registries.*;
import net.minecraft.core.Registry;
import net.minecraft.resources.*;

/** Internal contracts, not a reference mod or a substitute for native loading order. */
@SuppressWarnings("removal")
public final class ForgeContractProbe {
    public static class ProbeEvent extends Event implements IModBusEvent {}
    public static final class ProbeValue {}
    private ForgeContractProbe() {}
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException("FORGE_PROBE: " + message); }
    private static Map<String, Object> thread() { return Map.of("id", Thread.currentThread().threadId(), "name", Thread.currentThread().getName()); }
    private static final class Container extends ModContainer {
        final IEventBus bus = BusBuilder.builder().markerType(IModBusEvent.class).setExceptionHandler((eventBus, event, listeners, index, error) -> {
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException(error);
        }).build();
        Container() {
            super(info()); contextExtension = () -> this; configHandler = Optional.of(event -> bus.post(event.self()));
        }
        public boolean matches(Object mod) { return mod == this; }
        public Object getMod() { return this; }
        protected <T extends Event & IModBusEvent> void acceptEvent(T event) { bus.post(event); }
    }
    public static Map<String, Object> versionSupport() throws Exception {
        require(net.minecraftforge.fml.loading.FMLLoader.versionInfo().mcVersion().equals("1.21.1"), "Wrong pinned Minecraft version");
        Map<String, Object> report = new LinkedHashMap<>();
        var nativeLookup = net.minecraftforge.fml.loading.ModSorter.class.getDeclaredMethod("modVersionContained", IModInfo.ModVersion.class, Map.class); nativeLookup.setAccessible(true);
        for (String[] query : List.of(new String[]{"mod", "minecraft", "[1.21,1.21.1)", "1.21.1", "true"},
                new String[]{"mod", "forge", "[51,52)", "52.1.0", "true"},
                new String[]{"languageloader", "javafml", "[51,52)", "52.1.0", "true"},
                new String[]{"mod", "minecraft", "[1.22,)", "1.21.1", "false"},
                new String[]{"mod", "javafml", "[51,52)", "52.1.0", "false"},
                new String[]{"languageloader", "lowcodefml", "[51,52)", "52.1.0", "false"})) {
            var range = org.apache.maven.artifact.versioning.VersionRange.createFromVersionSpec(query[2]);
            boolean expected = Boolean.parseBoolean(query[4]);
            boolean accepted = net.minecraftforge.fml.loading.VersionSupportMatrix.testVersionSupportMatrix(range, query[1], query[0]);
            require(accepted == expected, "Native VersionSupportMatrix differs: " + Arrays.toString(query));
            if (query[0].equals("mod")) {
                var dependency = (IModInfo.ModVersion)Proxy.newProxyInstance(ForgeContractProbe.class.getClassLoader(), new Class<?>[]{IModInfo.ModVersion.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getModId" -> query[1]; case "getVersionRange" -> range; default -> throw new UnsupportedOperationException(method.getName());
                });
                require((boolean)nativeLookup.invoke(null, dependency, Map.of(query[1], new org.apache.maven.artifact.versioning.DefaultArtifactVersion(query[3]))) == expected, "Native ModSorter differs");
            }
            report.put(query[0] + "." + query[1] + " " + query[2], accepted);
        }
        return report;
    }
    private static IModInfo info() {
        var loader = ForgeContractProbe.class.getClassLoader();
        var config = (IConfigurable)Proxy.newProxyInstance(loader, new Class<?>[]{IConfigurable.class}, (proxy, method, args) -> Optional.of("IGNORE_ALL_VERSION"));
        return (IModInfo)Proxy.newProxyInstance(loader, new Class<?>[]{IModInfo.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getModId", "getDisplayName", "toString" -> "nf_headless_probe";
            case "getConfig" -> config;
            default -> throw new UnsupportedOperationException("Probe metadata query " + method.getName());
        });
    }
    public static Map<String, Object> run(Path directory) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>(); report.put("ownerLaunchThread", thread());
        report.put("versionSupportMatrix", versionSupport());
        List<Map<String, String>> states = new ArrayList<>();
        for (IModStateProvider provider : List.of(new net.minecraftforge.fml.core.ModStateProvider(), new ForgeStatesProvider()))
            for (var state : provider.getAllStates()) states.add(Map.of("name", state.name(), "previous", state.previous(), "nativePhase", state.phase().name()));
        report.put("nativeStateDefinitions", states);
        require(ForgeContractProbe.class.getClassLoader() == ModContainer.class.getClassLoader(), "Forge classes must share G");
        require(Launcher.INSTANCE.environment().getProperty(IEnvironment.Keys.NAMING.get()).orElseThrow().equals("mojang"), "Missing launcher environment");
        require(Launcher.INSTANCE.blackboard() != null, "Missing launcher blackboard");
        var pluginHandlerField = Launcher.class.getDeclaredField("launchPlugins"); pluginHandlerField.setAccessible(true);
        var pluginHandler = Objects.requireNonNull(pluginHandlerField.get(Launcher.INSTANCE), "Missing passive launch plugin handler");
        var pluginsField = pluginHandler.getClass().getDeclaredField("plugins"); pluginsField.setAccessible(true);
        @SuppressWarnings("unchecked") var pluginState = (Map<String, cpw.mods.modlauncher.serviceapi.ILaunchPluginService>)pluginsField.get(pluginHandler);
        require(pluginState.get("eventbus") == Launcher.INSTANCE.environment().findLaunchPlugin("eventbus").orElseThrow(), "Reflected plugin state differs from the adapter");
        try { pluginState.put("unadapted", pluginState.get("eventbus")); throw new IllegalStateException("Dynamic plugin takeover was admitted"); }
        catch (UnsupportedOperationException expected) { require(expected.getMessage().startsWith("FORGE_PLUGIN_REGISTRATION"), "Wrong dynamic plugin rejection"); }
        report.put("launcher", "environment / blackboard / reflected plugin state available; dynamic plugin insertion and native launch refused");
        try { Launcher.main(); throw new IllegalStateException("Native launcher was admitted"); }
        catch (UnsupportedOperationException expected) { require(expected.getMessage().startsWith("FORGE_LAUNCH_OWNERSHIP"), "Wrong launcher refusal"); }
        var container = new Container();
        var gameBus = MinecraftForge.EVENT_BUS;
        require(gameBus != container.bus && gameBus == MinecraftForge.EVENT_BUS, "MOD and GAME bus identities");
        var modCount = new AtomicInteger(); var gameCount = new AtomicInteger();
        container.bus.addListener(EventPriority.NORMAL, false, ProbeEvent.class, event -> modCount.incrementAndGet());
        gameBus.addListener(EventPriority.NORMAL, false, ProbeEvent.class, event -> gameCount.incrementAndGet());
        container.bus.post(new ProbeEvent()); require(modCount.get() == 1 && gameCount.get() == 0, "MOD event leaked to GAME bus");
        gameBus.post(new ProbeEvent()); require(gameCount.get() == 0, "GAME bus started before explicit native start");
        gameBus.start(); gameBus.post(new ProbeEvent()); require(modCount.get() == 1 && gameCount.get() == 1, "GAME event leaked to MOD bus");
        IEventBus failedBus = BusBuilder.builder().setExceptionHandler((bus, event, listeners, index, error) -> { throw (RuntimeException)error; }).build();
        var failure = new IllegalArgumentException("native event failure");
        failedBus.addListener(EventPriority.NORMAL, false, ProbeEvent.class, event -> { throw failure; });
        try { failedBus.post(new ProbeEvent()); throw new IllegalStateException("Listener failure disappeared"); }
        catch (IllegalArgumentException expected) { require(expected == failure, "Listener exception identity changed"); }
        var threads = new ConcurrentHashMap<String, Object>(); var deferred = new AtomicReference<CompletableFuture<Void>>();
        var queue = new DeferredWorkQueue(ModLoadingStage.COMMON_SETUP);
        container.bus.addListener(EventPriority.NORMAL, false, FMLCommonSetupEvent.class, event -> {
            threads.put("parallelEvent", thread()); deferred.set(event.enqueueWork(() -> { threads.put("enqueueWork", thread()); }));
        });
        CompletableFuture.runAsync(() -> container.acceptEvent(new FMLCommonSetupEvent(container, ModLoadingStage.COMMON_SETUP)), ModWorkManager.parallelExecutor()).get(15, TimeUnit.SECONDS);
        require(!deferred.get().isDone(), "Deferred work ran during parallel dispatch");
        var sync = ModWorkManager.syncExecutor(); sync.execute(() -> { threads.put("syncDriver", thread()); queue.runTasks(); });
        require(!sync.selfDriven() && !deferred.get().isDone(), "Forge sync executor must be explicitly driven");
        require(sync.driveOne(), "No queued native work"); deferred.get().get(15, TimeUnit.SECONDS);
        long owner = Thread.currentThread().threadId();
        require(((Map<?, ?>)threads.get("enqueueWork")).get("id").equals(owner), "enqueueWork did not execute on the driver thread");
        require(!((Map<?, ?>)threads.get("parallelEvent")).get("id").equals(owner), "Parallel event ran on owner thread");
        try (var resource = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "nf-forge-resource-probe"))) {
            var work = new CompletableFuture<Void>();
            ModWorkManager.wrappedExecutor(resource).execute(() -> { threads.put("resourceWorker", thread()); work.complete(null); });
            work.get(15, TimeUnit.SECONDS);
        }
        report.put("threads", threads); report.put("eventBus", "separate identities, listeners isolated, exceptions preserved");
        report.put("configs", configs(directory, container));
        String optionsClass = "net.minecraft.client.Options";
        try (var input = ForgeContractProbe.class.getClassLoader().getResourceAsStream(optionsClass.replace('.', '/') + ".class")) {
            byte[] original = Objects.requireNonNull(input).readAllBytes(), transformed = ForgeBridge.access(optionsClass, original);
            var node = new org.objectweb.asm.tree.ClassNode(); new org.objectweb.asm.ClassReader(transformed).accept(node, 0);
            var field = node.fields.stream().filter(candidate -> candidate.name.equals("keyMappings")).findFirst().orElseThrow();
            require((field.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0 && (field.access & org.objectweb.asm.Opcodes.ACC_FINAL) == 0, "Forge public-f AT did not modify keyMappings");
            require(!Arrays.equals(original, transformed), "Forge AT did not change its pinned target");
            report.put("accessTransformer", "SRG public-f rule changed Options.keyMappings without defining the client class");
        }
        var registryResult = new AtomicReference<Map<String, Object>>();
        sync.execute(() -> {
            threads.put("registryThread", thread());
            try { registryResult.set(registry(container)); } catch (Exception error) { throw new IllegalStateException("Forge registry probe failed", error); }
        });
        require(sync.driveOne(), "Custom registry task was not driven"); report.put("registry", registryResult.get());
        Map<String, Object> coremods = new LinkedHashMap<>();
        for (String name : List.of("net.minecraft.world.level.biome.Biome", "net.minecraft.world.effect.MobEffectInstance", "net.minecraft.world.level.block.LiquidBlock", "net.minecraft.world.item.BucketItem", "net.minecraft.world.level.block.FlowerPotBlock")) {
            byte[] original;
            try (var input = ForgeContractProbe.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) { original = Objects.requireNonNull(input).readAllBytes(); }
            byte[] access = ForgeBridge.access(name, original), output = ForgeBridge.coremods(name, access);
            require(!Arrays.equals(access, output), "Forge coremod did not transform " + name); coremods.put(name, "changed after AT");
        }
        report.put("jsCoremods", coremods); report.put("classloader", "G");
        String redirectTarget = "net.minecraft.world.entity.monster.Zombie";
        try (var input = ForgeContractProbe.class.getClassLoader().getResourceAsStream(redirectTarget.replace('.', '/') + ".class")) {
            byte[] original = Objects.requireNonNull(input).readAllBytes(), transformed = ForgeBridge.coremods(redirectTarget, original);
            require(!Arrays.equals(original, transformed), "Forge finalizeSpawn method redirector did not transform a pinned target");
            coremods.put(redirectTarget, "finalizeSpawn redirected");
        }
        report.put("dist", dist());
        return report;
    }
    private static Map<String, Object> dist() {
        String other = ForgeBridge.dist().isClient() ? "DEDICATED_SERVER" : "CLIENT";
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "nfp/WrongDist", null, "java/lang/Object", null);
        var annotation = writer.visitAnnotation("Lnet/minecraftforge/api/distmarker/OnlyIn;", true);
        annotation.visitEnum("value", "Lnet/minecraftforge/api/distmarker/Dist;", other); annotation.visitEnd(); writer.visitEnd();
        try { ForgeBridge.plugins("nfp.WrongDist", writer.toByteArray()); throw new IllegalStateException("Opposite-Dist class was admitted"); }
        catch (RuntimeException expected) { require(expected.getMessage().contains("invalid dist"), "Wrong Dist refusal reason"); }
        return Map.of("physicalSide", ForgeBridge.dist().name(), "oppositeOnlyIn", "rejected before definition");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> registry(Container container) throws Exception {
        var id = ResourceLocation.fromNamespaceAndPath("nf_headless_probe", "values");
        // A standalone native registry factory exercises registration without running
        // NewRegistryEvent.fill's global Minecraft bootstrap / root registry rewrite.
        var builder = new RegistryBuilder<ProbeValue>().setName(id).disableSaving().disableSync();
        var create = RegistryBuilder.class.getDeclaredMethod("create"); create.setAccessible(true);
        var registry = (ForgeRegistry<ProbeValue>)create.invoke(builder); require(registry != null, "Custom registry was not built");
        var entries = DeferredRegister.create(registry, "nf_headless_probe");
        var value = new ProbeValue(); var handle = entries.register("value", () -> value);
        require(!handle.isPresent(), "Deferred RegistryObject bound before RegisterEvent");
        try { handle.get(); throw new IllegalStateException("Unbound RegistryObject was readable"); } catch (NullPointerException expected) {}
        entries.register(container.bus);
        var constructor = RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, ForgeRegistry.class, Registry.class); constructor.setAccessible(true);
        ModLoadingContext.get().setActiveContainer(container);
        try { container.bus.post(constructor.newInstance(registry.getRegistryKey(), registry, null)); }
        finally { ModLoadingContext.get().setActiveContainer(null); }
        require(handle.get() == value && registry.getValue(handle.getId()) == value, "RegistryObject and registry object identity differ");
        require(value.getClass().getClassLoader() == ForgeRegistry.class.getClassLoader(), "Registry content is not owned by G");
        var foreignHandle = RegistryObject.create(handle.getId(), registry);
        require(foreignHandle.get() == value, "Cross-owner RegistryObject query did not return the canonical object");
        try { entries.register("late", ProbeValue::new); throw new IllegalStateException("Late deferred registration was accepted"); }
        catch (IllegalStateException expected) { require(expected.getMessage().contains("RegisterEvent"), "Late registration failed for a different reason"); }
        registry.freeze(); require(registry.isLocked(), "Forge registry did not lock at freeze");
        try { registry.register(ResourceLocation.fromNamespaceAndPath("nf_headless_probe", "after_freeze"), new ProbeValue()); throw new IllegalStateException("Frozen registry write was accepted"); }
        catch (IllegalStateException expected) { require(expected.getMessage().contains("being added too late"), "Freeze failed for a different reason"); }
        return Map.of("customRegistry", id.toString(), "deferredRegister", "RegisterEvent binds RegistryObject", "identity", "same object in G and cross-owner query", "lateRegistration", "rejected", "writeAfterFreeze", "rejected");
    }
    private static Map<String, Object> configs(Path directory, Container container) throws Exception {
        var configs = new EnumMap<ModConfig.Type, ModConfig>(ModConfig.Type.class);
        var values = new EnumMap<ModConfig.Type, ForgeConfigSpec.IntValue>(ModConfig.Type.class);
        var loading = new EnumMap<ModConfig.Type, AtomicInteger>(ModConfig.Type.class); var reloading = new AtomicInteger();
        container.bus.addListener(EventPriority.NORMAL, false, ModConfigEvent.Loading.class, event -> loading.get(event.getConfig().getType()).incrementAndGet());
        container.bus.addListener(EventPriority.NORMAL, false, ModConfigEvent.Reloading.class, event -> reloading.incrementAndGet());
        for (var type : ModConfig.Type.values()) {
            var builder = new ForgeConfigSpec.Builder(); var value = builder.defineInRange("answer", 42, 0, 1000); var spec = builder.build();
            var config = new ModConfig(type, spec, container, "nf-headless-" + type.name().toLowerCase(Locale.ROOT) + ".toml");
            configs.put(type, config); values.put(type, value); loading.put(type, new AtomicInteger());
            require(config.getConfigData() == null, "Config loaded before explicit native load");
        }
        Path configDirectory = Files.createDirectories(directory.resolve("config")); var tracker = ConfigTracker.INSTANCE;
        try {
            tracker.loadConfigs(ModConfig.Type.CLIENT, configDirectory); tracker.loadConfigs(ModConfig.Type.COMMON, configDirectory);
            require(configs.get(ModConfig.Type.SERVER).getConfigData() == null, "SERVER was silently loaded with COMMON");
            tracker.loadDefaultServerConfigs();
            for (var type : ModConfig.Type.values()) require(values.get(type).get() == 42 && loading.get(type).get() == 1, "Config defaults / Loading count " + type);
            require(!Files.exists(configDirectory.resolve(configs.get(ModConfig.Type.SERVER).getFileName())), "Default SERVER config created a disk file");
            configs.get(ModConfig.Type.SERVER).acceptSyncedConfig("answer = 73\n".getBytes(StandardCharsets.UTF_8));
            require(values.get(ModConfig.Type.SERVER).get() == 73 && reloading.get() == 1, "SERVER sync did not replace defaults through Reloading");
            return Map.of("client", "loaded from config", "common", "loaded from config", "server", "unloaded before explicit defaults; sync overwrites in memory", "loadingEvents", 3, "reloadingEvents", reloading.get());
        } finally {
            tracker.unloadConfigs(ModConfig.Type.CLIENT, configDirectory); tracker.unloadConfigs(ModConfig.Type.COMMON, configDirectory);
            // SERVER defaults / remote sync are in-memory configs, not open file configs.
            tracker.fileMap().clear(); tracker.configSets().values().forEach(Set::clear);
        }
    }
}
