package org.neoforbric.neoforge.runtime;

import cpw.mods.jarhandling.*;
import java.lang.module.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.*;
import net.neoforged.fml.javafmlmod.FMLJavaModLanguageProvider;
import net.neoforged.fml.loading.*;
import net.neoforged.fml.loading.moddiscovery.*;
import net.neoforged.fml.mclanguageprovider.MinecraftModLanguageProvider;
import net.neoforged.neoforgespi.language.*;
import net.neoforged.neoforgespi.locating.*;

/** Passive FML host. All mod and Minecraft classes remain in NeoForbric's game loader. */
public final class NeoForgeBridge {
    private static List<ModFile> files;
    private static LoadingModList plan;
    private static boolean constructed;
    private static ModuleLayer gameLayer;
    private static Map<String, IModLanguageLoader> languages;
    public static Optional<String> jarVersion(Class<?> type) {
        if (Set.of("net.neoforged.fml.javafmlmod.FMLJavaModLanguageProvider", "net.neoforged.fml.lowcodemod.LowCodeModLanguageProvider").contains(type.getName()))
            return Optional.of(FMLLoader.versionInfo().fmlVersion());
        var descriptor = type.getModule().getDescriptor();
        if (descriptor != null && descriptor.rawVersion().isPresent()) return descriptor.rawVersion();
        return Optional.ofNullable(type.getPackage().getImplementationVersion());
    }
    private NeoForgeBridge() {}
    public static void forbiddenNativeLaunch() { throw new IllegalStateException("NeoForbric owns main and the game classloader; native ModLauncher execution is forbidden"); }
    public static Optional<cpw.mods.modlauncher.api.IModuleLayerManager> layerManager() {
        return Optional.of(layer -> layer == cpw.mods.modlauncher.api.IModuleLayerManager.Layer.GAME ? Optional.ofNullable(gameLayer) : Optional.of(ModuleLayer.boot()));
    }
    private static void launcherInfo(Path gameDirectory) throws Exception {
        var type = cpw.mods.modlauncher.Launcher.class;
        if (cpw.mods.modlauncher.Launcher.INSTANCE != null) throw new IllegalStateException("Native launcher already initialized");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        // The kernel replaces this constructor with Object initialization only, before definition.
        var launcher = constructor.newInstance();
        var environmentConstructor = cpw.mods.modlauncher.Environment.class.getDeclaredConstructor(type); environmentConstructor.setAccessible(true);
        var environment = environmentConstructor.newInstance(launcher);
        for (var entry : Map.of("environment", environment, "blackboard", new cpw.mods.modlauncher.api.TypesafeMap()).entrySet()) {
            var field = type.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(launcher, entry.getValue());
        }
        environment.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.LAUNCHTARGET.get(), key -> "neoforbricclient");
        environment.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.GAMEDIR.get(), key -> gameDirectory);
        environment.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.VERSION.get(), key -> "1.21.1");
        cpw.mods.modlauncher.Launcher.INSTANCE = launcher;
    }

    public static List<java.nio.file.spi.FileSystemProvider> fileSystemProviders() {
        var providers = new ArrayList<>(java.nio.file.spi.FileSystemProvider.installedProviders());
        providers.add(new cpw.mods.niofs.union.UnionFileSystemProvider());
        return providers;
    }

    private static void field(Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }

    public static List<String> prepare(Path gameDirectory, Path game, Path universal, List<Path> mods, ClassLoader loader, List<String> canonicalOrder, Map<String, Set<String>> canonicalPredecessors, Map<String, List<String>> entrypoints, boolean registryContract, List<Path> libraries, Map<Path, java.util.function.Consumer<java.util.function.Consumer<byte[]>>> scans) throws Exception {
        launcherInfo(gameDirectory);
        field(FMLLoader.class, "dist", Dist.CLIENT);
        field(FMLLoader.class, "production", true);
        field(FMLLoader.class, "gamePath", gameDirectory);
        field(FMLLoader.class, "versionInfo", new VersionInfo("21.1.248", "4.0.43", "1.21.1", "1.21.1-20240808.144430"));
        field(FMLLoader.class, "launchHandlerName", "neoforbricclient");
        FMLLoader.progressWindowTick = () -> {};
        FMLPaths.loadAbsolutePaths(gameDirectory);
        FMLConfig.load();
        languages = new HashMap<>();
        languages.put("javafml", new FMLJavaModLanguageProvider());
        languages.put("lowcodefml", new net.neoforged.fml.lowcodemod.LowCodeModLanguageProvider());
        for (Path library : libraries) try (var jar = new java.util.jar.JarFile(library.toFile())) {
            var service = jar.getJarEntry("META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader");
            if (service == null) continue;
            String text; try (var stream = jar.getInputStream(service)) { text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
            for (String line : text.split("\\R")) {
                String name = line.split("#", 2)[0].trim(); if (name.isEmpty()) continue;
                // Forgified Fabric Loader uses this service to inject a second Fabric
                // loader into ModLauncher. The kernel already owns the passive Fabric
                // facade and classloader; the port's API modules use javafml/lowcodefml.
                if (name.equals("net.fabricmc.loader.impl.bootstrap.FabricLoaderHackyInjector")) continue;
                IModLanguageLoader provider = (IModLanguageLoader)Class.forName(name, true, loader).getConstructor().newInstance();
                if (languages.putIfAbsent(provider.name(), provider) != null) throw new IllegalStateException("Duplicate language provider " + provider.name());
            }
        }
        Class<?> dummy = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler$DummyProvider");
        var dummyConstructor = dummy.getDeclaredConstructor(); dummyConstructor.setAccessible(true);
        field(ImmediateWindowHandler.class, "provider", dummyConstructor.newInstance());
        field(ImmediateWindowHandler.class, "earlyProgress", net.neoforged.fml.loading.progress.StartupNotificationManager.addProgressBar("NeoForbric", 0));
        var languageConstructor = LanguageProviderLoader.class.getDeclaredConstructor(net.neoforged.neoforgespi.ILaunchContext.class);
        languageConstructor.setAccessible(true);
        field(FMLLoader.class, "languageProviderLoader", languageConstructor.newInstance(new net.neoforged.neoforgespi.ILaunchContext() {
            public cpw.mods.modlauncher.api.IEnvironment environment() { return cpw.mods.modlauncher.Launcher.INSTANCE.environment(); }
            public <T> java.util.stream.Stream<ServiceLoader.Provider<T>> loadServices(Class<T> service) {
                if (service != IModLanguageLoader.class) return java.util.stream.Stream.empty();
                return new TreeMap<>(languages).values().stream().map(provider -> new ServiceLoader.Provider<T>() {
                    public Class<? extends T> type() { return provider.getClass().asSubclass(service); }
                    public T get() { return service.cast(provider); }
                });
            }
            public List<String> modLists() { return List.of(); }
            public List<String> mods() { return List.copyOf(canonicalOrder); }
            public List<String> mavenRoots() { return List.of(); }
            public boolean isLocated(Path path) { return mods.contains(path) || libraries.contains(path); }
            public boolean addLocated(Path path) { throw new IllegalStateException("NeoForbric owns the immutable discovery plan"); }
        }));
        files = new ArrayList<>();
        Class<?> minecraftInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.locators.MinecraftModInfo");
        Method minecraftParser = minecraftInfo.getMethod("buildMinecraftModInfo", IModFile.class); minecraftParser.setAccessible(true);
        files.add(file(game, modFile -> {
            try { return (IModFileInfo)minecraftParser.invoke(null, modFile); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }, true));
        files.add(file(universal, ModFileParser::modsTomlParser, false));
        for (Path mod : mods) files.add(file(mod, ModFileParser::modsTomlParser, false));
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModFile file : files) for (var info : file.getModInfos()) {
            if (byId.putIfAbsent(info.getModId(), (ModInfo)info) != null) throw new IllegalStateException("Duplicate passive mod " + info.getModId());
        }
        List<String> order = new ArrayList<>(List.of("minecraft", "neoforge")); order.addAll(canonicalOrder);
        if (!byId.keySet().equals(new HashSet<>(order))) throw new IllegalStateException("Facade metadata differs from canonical mod selection");
        List<ModInfo> sorted = order.stream().map(byId::get).toList();
        Map<ModInfo, List<ModInfo>> predecessors = new IdentityHashMap<>();
        for (ModInfo info : sorted) predecessors.put(info, canonicalPredecessors.getOrDefault(info.getModId(), Set.of()).stream()
                .map(byId::get).filter(Objects::nonNull).toList());
        plan = LoadingModList.of(List.of(), files, sorted, List.of(), predecessors);
        field(FMLLoader.class, "loadingModList", plan);
        ModuleLayer layer = layer(files, loader);
        gameLayer = layer;
        loader.getClass().getMethod("moduleNames", Set.class).invoke(loader, layer.modules().stream().map(Module::getName).collect(java.util.stream.Collectors.toSet()));
        field(FMLLoader.class, "gameLayer", layer);
        ImmediateWindowHandler.acceptGameLayer(layer);
        // Scan the already admitted immutable bytes without invoking native discovery services.
        for (ModFile file : files) {
            var result = new ModFileScanData(); result.addModFileInfo(file.getModFileInfo());
            var source = Objects.requireNonNull(scans.get(file.getFilePath()), "No immutable class snapshot for " + file.getFilePath());
            source.accept(bytes -> {
                var visitor = new net.neoforged.fml.loading.modscan.ModClassVisitor();
                new org.objectweb.asm.ClassReader(bytes).accept(visitor, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_DEBUG);
                visitor.buildData(result.getClasses(), result.getAnnotations());
            });
            file.setScanResult(result, null);
        }
        plan.addEnumExtenders();
        NativeNeoForgeTransforms.initialize(plan.getModFiles());
        System.out.println("NEOFORGE_HOST_READY mods=" + plan.getMods().stream().map(IModInfo::getModId).toList()
                + " gameLoader=" + loader.getName() + " nativeLauncher=false");
        List<String> configs = new ArrayList<>();
        for (ModFile file : files) if (file != files.get(0) && file != files.get(1)) for (var config : file.getMixinConfigs())
            if (byId.keySet().containsAll(config.requiredMods())) configs.add(config.config());
        return List.copyOf(new LinkedHashSet<>(configs));
    }

    public static void finish(Map<String, List<String>> entrypoints, boolean registryContract) throws Exception {
        field(FMLLoader.class, "bindings", Class.forName("net.neoforged.neoforge.internal.NeoForgeBindings", true, gameLayer.findLoader("neoforge")).getConstructor().newInstance());
        // Mixin plugins inspect LoadingModList before game-side containers exist, as in FML.
        ModList.of(plan.getModFiles().stream().map(ModFileInfo::getFile).toList(), plan.getMods());
        NativeNeoForgeRuntime.prepare(plan, gameLayer, entrypoints, registryContract);
    }

    private static ModFile file(Path path, ModFileInfoParser parser, boolean minecraft) throws Exception {
        JarContents contents = JarContents.of(path);
        ModJarMetadata metadata = new ModJarMetadata(contents);
        SecureJar jar = SecureJar.from(contents, metadata);
        ModFile file = new ModFile(jar, parser, IModFile.Type.MOD, ModFileDiscoveryAttributes.DEFAULT);
        metadata.setModFile(file);
        file.identifyMods();
        Field loaders = ModFile.class.getDeclaredField("loaders"); loaders.setAccessible(true);
        String language = minecraft ? "minecraft" : file.getModFileInfo().requiredLanguageLoaders().getFirst().languageName();
        IModLanguageLoader provider = minecraft ? new MinecraftModLanguageProvider() : languages.get(language);
        if (provider == null) throw new IllegalStateException("Missing language provider " + language + " for " + path);
        String languageVersion = Set.of("javafml", "lowcodefml").contains(language) ? FMLLoader.versionInfo().fmlVersion() : minecraft ? "1.21.1" : provider.version();
        if (!minecraft && !file.getModFileInfo().requiredLanguageLoaders().getFirst().acceptedVersions().containsVersion(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(languageVersion)))
            throw new IllegalStateException("Unsupported language version " + language + " " + languageVersion);
        loaders.set(file, List.of(provider));
        return file;
    }

    private static ModuleLayer layer(List<ModFile> files, ClassLoader loader) {
        Map<String, ModuleReference> references = new LinkedHashMap<>();
        for (ModFile file : files) {
            ModuleDescriptor original = file.getSecureJar().moduleDataProvider().descriptor();
            var builder = ModuleDescriptor.newAutomaticModule(file.getModFileInfo().moduleName()).packages(original.packages());
            original.provides().forEach(provider -> builder.provides(provider.service(), provider.providers()));
            original.rawVersion().ifPresent(builder::version);
            ModuleDescriptor descriptor = builder.build();
            references.put(descriptor.name(), new ModuleReference(descriptor, file.getFilePath().toUri()) {
                @Override public ModuleReader open() { throw new UnsupportedOperationException("NeoForbric's bytecode index owns module resources"); }
            });
        }
        ModuleFinder finder = new ModuleFinder() {
            @Override public Optional<ModuleReference> find(String name) { return Optional.ofNullable(references.get(name)); }
            @Override public Set<ModuleReference> findAll() { return Set.copyOf(references.values()); }
        };
        Configuration configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), references.keySet());
        return ModuleLayer.defineModules(configuration, List.of(ModuleLayer.boot()), name -> loader).layer();
    }

    /** Called at NeoForge's original gather/construct anchor, under kernel ownership. */
    public static void construct(Executor syncExecutor, Executor parallelExecutor, Runnable periodicTask) {
        if (constructed) throw new IllegalStateException("NeoForge construction executed twice");
        constructed = true;
        NativeNeoForgeRuntime.construct();
    }
    public static void close() throws Exception {
        if (files != null) for (ModFile file : files) file.getSecureJar().close();
    }
}
