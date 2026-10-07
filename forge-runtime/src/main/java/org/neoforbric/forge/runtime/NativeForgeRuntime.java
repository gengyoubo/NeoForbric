package org.neoforbric.forge.runtime;

import cpw.mods.jarhandling.*;
import java.lang.module.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;
import net.minecraftforge.fml.*;
import net.minecraftforge.fml.loading.*;
import net.minecraftforge.fml.loading.moddiscovery.*;
import net.minecraftforge.forgespi.language.*;
import net.minecraftforge.forgespi.locating.*;
import org.apache.maven.artifact.versioning.*;
import org.objectweb.asm.ClassReader;

/** Passive metadata host. Actual transitions, containers and events are driven by Forge's ModLoader. */
public final class NativeForgeRuntime {
    private static List<ModFile> files = List.of();
    private static ModuleLayer layer;
    private static final List<IModLanguageProvider> languages = List.of(
            new net.minecraftforge.fml.javafmlmod.FMLJavaModLanguageProvider(),
            new net.minecraftforge.fml.lowcodemod.LowCodeModLanguageProvider(),
            new net.minecraftforge.fml.mclanguageprovider.MinecraftModLanguageProvider());
    private static Consumer<String> observer = phase -> {};
    private static org.neoforbric.api.GameHooks.Session registrySession;
    private static boolean prepared;
    private NativeForgeRuntime() {}
    public static List<IModLanguageProvider> languageProviders() { return languages; }
    public static List<java.nio.file.spi.FileSystemProvider> fileSystemProviders() {
        var providers = new ArrayList<>(java.nio.file.spi.FileSystemProvider.installedProviders()); providers.add(new cpw.mods.niofs.union.UnionFileSystemProvider()); return providers;
    }
    public static IModLanguageProvider findLanguage(ModFile file, String name, VersionRange range) {
        String version = name.equals("minecraft") ? "1.21.1" : "52.1.0";
        if (!range.containsVersion(new DefaultArtifactVersion(version))) throw new IllegalStateException("FORGE_LANGUAGE_VERSION: " + name + " " + range + " != " + version);
        return languages.stream().filter(language -> language.name().equals(name)).findFirst().orElseThrow(() -> new IllegalStateException("FORGE_LANGUAGE: " + name));
    }
    public static Stream<IModStateProvider> stateProviders(ServiceLoader<?> ignored, Consumer<?> errors) {
        return Stream.of(new net.minecraftforge.fml.core.ModStateProvider(), new net.minecraftforge.common.ForgeStatesProvider());
    }
    public static ModuleLayer gameLayer() { return Objects.requireNonNull(layer, "Forge metadata layer not ready"); }
    public static Optional<cpw.mods.modlauncher.api.IModuleLayerManager> layerManager() {
        return Optional.of(kind -> Optional.of(kind == cpw.mods.modlauncher.api.IModuleLayerManager.Layer.GAME && layer != null ? layer : ModuleLayer.boot()));
    }
    private static void field(Class<?> owner, String name, Object value) throws Exception { Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(null, value); }

    public static void prepare(Path game, Path universal, List<Path> mods, List<String> order,
            ClassLoader loader, Map<Path, Consumer<Consumer<byte[]>>> scans, List<Path> accessRules) throws Exception {
        if (prepared) throw new IllegalStateException("FORGE_STATE: Metadata prepared twice"); prepared = true;
        ForgeBridge.minecraftPaths(game, universal);
        FMLLoader.progressWindowTick = () -> {};
        field(FMLLoader.class, "launchHandlerName", ForgeBridge.launchHandler().name());
        var languageConstructor = LanguageLoadingProvider.class.getDeclaredConstructor(); languageConstructor.setAccessible(true);
        field(FMLLoader.class, "languageLoadingProvider", languageConstructor.newInstance());
        // Use Forge's no-window provider. This prepares loading progress only; it never initializes GLFW.
        ImmediateWindowHandler.load("neoforbric", new String[0]);
        if (ForgeBridge.dist() == net.minecraftforge.api.distmarker.Dist.CLIENT) field(ImmediateWindowHandler.class, "provider", new ForgeWindowProvider());
        List<ModFile> admitted = new ArrayList<>();
        admitted.add(file(game, source -> new ModFileInfo((ModFile)source, config(Map.of("modLoader", "minecraft", "loaderVersion", "[1.21.1]", "license", "Minecraft EULA",
                "mods", List.of(Map.of("modId", "minecraft", "version", "1.21.1", "displayName", "Minecraft")))), ignored -> {},
                List.of(new IModFileInfo.LanguageSpec("minecraft", VersionRange.createFromVersion("1.21.1")))), "minecraft"));
        admitted.add(file(universal, ModFileParser::modsTomlParser, null));
        for (Path path : mods) admitted.add(file(path, ModFileParser::modsTomlParser, null)); files = List.copyOf(admitted);
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (var file : files) for (var mod : file.getModInfos()) if (byId.putIfAbsent(mod.getModId(), (ModInfo)mod) != null) throw new IllegalStateException("FORGE_DUPLICATE: " + mod.getModId());
        List<String> sorted = new ArrayList<>(List.of("minecraft", "forge")); sorted.addAll(order);
        if (!byId.keySet().equals(new HashSet<>(sorted))) throw new IllegalStateException("FORGE_METADATA: Canonical/native selection differs: " + byId.keySet() + " vs " + sorted);
        var plan = LoadingModList.of(files, sorted.stream().map(byId::get).toList(), null); plan.setBrokenFiles(List.of());
        field(FMLLoader.class, "loadingModList", plan);
        layer = layer(files, loader);
        loader.getClass().getMethod("moduleNames", Set.class).invoke(loader, layer.modules().stream().map(Module::getName).collect(java.util.stream.Collectors.toSet()));
        for (var file : files) {
            var scan = new ModFileScanData(); scan.addModFileInfo(file.getModFileInfo());
            Class<?> visitorType = Class.forName("net.minecraftforge.fml.loading.moddiscovery.ModClassVisitor");
            var visitorConstructor = visitorType.getDeclaredConstructor(); visitorConstructor.setAccessible(true);
            var buildData = visitorType.getDeclaredMethod("buildData", Set.class, Set.class); buildData.setAccessible(true);
            Objects.requireNonNull(scans.get(file.getFilePath()), "Missing immutable Forge scan " + file.getFilePath()).accept(bytes -> {
                try {
                    var visitor = (org.objectweb.asm.ClassVisitor)visitorConstructor.newInstance();
                    new ClassReader(bytes).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                    buildData.invoke(visitor, scan.getClasses(), scan.getAnnotations());
                } catch (ReflectiveOperationException error) { throw new IllegalStateException("FORGE_SCAN", error); }
            });
            for (var language : file.getLoaders()) language.getFileVisitor().accept(scan);
            file.setScanResult(scan, null);
        }
        var background = new BackgroundScanHandler(List.of()); background.setLoadingModList(plan); FMLLoader.backgroundScanHandler = background;
        for (Path path : accessRules) net.minecraftforge.accesstransformer.AccessTransformerEngine.INSTANCE.addResource(path, "admitted Forge mod");
        // LoadingModList is available to Mixin plugins before native construction starts.
        var initialList = ModList.of(files, plan.getMods());
        // NF exposes metadata before Minecraft's crash-report preload. No containers
        // exist yet, so use Forge's own index builder with an empty loaded list.
        // gatherAndInitializeMods subsequently builds and indexes the real containers.
        var setLoaded = ModList.class.getDeclaredMethod("setLoadedMods", List.class); setLoaded.setAccessible(true); setLoaded.invoke(initialList, List.of());
        System.out.println("FORGE_HOST_READY mods=" + sorted + " nativeLauncher=false loader=" + loader);
    }
    private static ModFile file(Path path, ModFileFactory.ModFileInfoParser parser, String language) throws Exception {
        var ctor = ModJarMetadata.class.getDeclaredConstructor(); ctor.setAccessible(true); var metadata = ctor.newInstance();
        SecureJar jar = SecureJar.from(ignored -> metadata, path);
        IModProvider provider = new IModProvider() {
            public String name() { return "neoforbric-immutable"; }
            public void initArguments(Map<String, ?> args) { throw new UnsupportedOperationException("FORGE_DISCOVERY_OWNERSHIP"); }
            public boolean isValid(IModFile file) { return true; }
            public void scanFile(IModFile file, Consumer<Path> consumer) { throw new UnsupportedOperationException("FORGE_DISCOVERY_OWNERSHIP"); }
        };
        var file = new ModFile(jar, provider, parser); metadata.setModFile(file);
        if (!file.identifyMods()) throw new IllegalStateException("FORGE_METADATA: Cannot identify " + path);
        var loaders = ModFile.class.getDeclaredField("loaders"); loaders.setAccessible(true);
        var spec = file.getModFileInfo().requiredLanguageLoaders().getFirst();
        loaders.set(file, List.of(findLanguage(file, language == null ? spec.languageName() : language, spec.acceptedVersions())));
        return file;
    }
    private static IConfigurable config(Map<String, ?> values) {
        return new IConfigurable() {
            @SuppressWarnings("unchecked") public <T> Optional<T> getConfigElement(String... keys) {
                Object value = values; for (String key : keys) { if (!(value instanceof Map<?, ?> map)) return Optional.empty(); value = map.get(key); }
                return Optional.ofNullable((T)value);
            }
            @SuppressWarnings("unchecked") public List<? extends IConfigurable> getConfigList(String... keys) {
                Object value = this.<Object>getConfigElement(keys).orElse(null);
                return value instanceof List<?> list ? list.stream().map(entry -> config((Map<String, ?>)entry)).toList() : List.of();
            }
        };
    }
    private static ModuleLayer layer(List<ModFile> files, ClassLoader loader) {
        Map<String, ModuleReference> references = new LinkedHashMap<>();
        for (var file : files) {
            var builder = ModuleDescriptor.newAutomaticModule(file.getModFileInfo().moduleName()).packages(file.getSecureJar().getPackages());
            // Named automatic modules need their declared SPI providers in the
            // descriptor; ServiceLoader otherwise ignores classpath providers in them.
            file.getSecureJar().getProviders().forEach(provider -> builder.provides(provider.serviceName(), provider.providers()));
            var descriptor = builder.build();
            if (references.putIfAbsent(descriptor.name(), new ModuleReference(descriptor, file.getFilePath().toUri()) {
                public ModuleReader open() { throw new UnsupportedOperationException("NF owns module bytes"); }
            }) != null) throw new IllegalStateException("FORGE_MODULE: Duplicate " + descriptor.name());
        }
        ModuleFinder finder = new ModuleFinder() {
            public Optional<ModuleReference> find(String name) { return Optional.ofNullable(references.get(name)); }
            public Set<ModuleReference> findAll() { return Set.copyOf(references.values()); }
        };
        var configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), references.keySet());
        return ModuleLayer.defineModules(configuration, List.of(ModuleLayer.boot()), name -> loader).layer();
    }

    /** Captured once on the launch owner, before native work moves to workers. */
    public static void captureRegistrySession() { registrySession = org.neoforbric.api.GameHooks.captureSession(); }
    public static void registryOpened() { if (registrySession != null) registrySession.beforeRegistryFreeze(); observer.accept("REGISTRY_OPEN"); }
    public static void registryFrozen() { if (registrySession != null) registrySession.afterRegistryFreeze(); observer.accept("REGISTRY_FROZEN"); }
    public static void observeState(IModLoadingState state) { observer.accept(state.name()); }
    public static void observer(Consumer<String> value) { observer = Objects.requireNonNull(value); }
    public static void gather() { ModLoader.get().gatherAndInitializeMods(ModWorkManager.syncExecutor(), ModWorkManager.parallelExecutor(), () -> {}); }
    public static void load() { ModLoader.get().loadMods(ModWorkManager.syncExecutor(), ModWorkManager.parallelExecutor(), () -> {}); }
    public static void finish() { ModLoader.get().finishMods(ModWorkManager.syncExecutor(), ModWorkManager.parallelExecutor(), () -> {}); }
    public static void close() throws Exception {
        // Native client normally unloads these from a process shutdown hook. NF owns
        // domain shutdown, including headless probes, and must stop the watcher pools.
        var tracker = net.minecraftforge.fml.config.ConfigTracker.INSTANCE;
        tracker.unloadConfigs(net.minecraftforge.fml.config.ModConfig.Type.CLIENT, FMLPaths.CONFIGDIR.get());
        tracker.unloadConfigs(net.minecraftforge.fml.config.ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());
        var handler = net.minecraftforge.fml.config.ConfigFileTypeHandler.class;
        var get = handler.getDeclaredMethod("get", net.minecraftforge.fml.config.ModConfig.Type.class); get.setAccessible(true);
        var stop = handler.getDeclaredMethod("stopWatcher"); stop.setAccessible(true);
        for (var type : net.minecraftforge.fml.config.ModConfig.Type.values()) stop.invoke(get.invoke(null, type));
        for (var file : files) { var root = file.getSecureJar().getRootPath(); if (root.getFileSystem().isOpen()) root.getFileSystem().close(); }
    }
}
