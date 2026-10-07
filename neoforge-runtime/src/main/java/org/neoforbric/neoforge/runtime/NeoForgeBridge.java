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
    private NeoForgeBridge() {}

    public static List<java.nio.file.spi.FileSystemProvider> fileSystemProviders() {
        var providers = new ArrayList<>(java.nio.file.spi.FileSystemProvider.installedProviders());
        providers.add(new cpw.mods.niofs.union.UnionFileSystemProvider());
        return providers;
    }

    private static void field(Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }

    public static void prepare(Path gameDirectory, Path game, Path universal, List<Path> mods, ClassLoader loader, List<String> canonicalOrder, Map<String, Set<String>> canonicalPredecessors, Map<String, List<String>> entrypoints, boolean registryContract) throws Exception {
        field(FMLLoader.class, "dist", Dist.CLIENT);
        field(FMLLoader.class, "production", true);
        field(FMLLoader.class, "gamePath", gameDirectory);
        field(FMLLoader.class, "versionInfo", new VersionInfo("21.1.244", "4.0.43", "1.21.1", "1.21.1-20240808.144430"));
        field(FMLLoader.class, "launchHandlerName", "neoforbricclient");
        FMLLoader.progressWindowTick = () -> {};
        FMLPaths.loadAbsolutePaths(gameDirectory);
        FMLConfig.load();
        Class<?> dummy = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler$DummyProvider");
        var dummyConstructor = dummy.getDeclaredConstructor(); dummyConstructor.setAccessible(true);
        field(ImmediateWindowHandler.class, "provider", dummyConstructor.newInstance());
        field(ImmediateWindowHandler.class, "earlyProgress", net.neoforged.fml.loading.progress.StartupNotificationManager.addProgressBar("NeoForbric", 0));
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
        loader.getClass().getMethod("moduleNames", Set.class).invoke(loader, layer.modules().stream().map(Module::getName).collect(java.util.stream.Collectors.toSet()));
        field(FMLLoader.class, "gameLayer", layer);
        field(FMLLoader.class, "bindings", Class.forName("net.neoforged.neoforge.internal.NeoForgeBindings", true, loader).getConstructor().newInstance());
        ImmediateWindowHandler.acceptGameLayer(layer);
        ModList.of(plan.getModFiles().stream().map(ModFileInfo::getFile).toList(), plan.getMods());
        // Scan the already admitted snapshots; no locator or language provider service is executed.
        for (ModFile file : files) file.setScanResult(file.compileContent(), null);
        NativeNeoForgeRuntime.prepare(plan, layer, entrypoints, registryContract);
        System.out.println("NEOFORGE_HOST_READY mods=" + plan.getMods().stream().map(IModInfo::getModId).toList()
                + " gameLoader=" + loader.getName() + " nativeLauncher=false");
    }

    private static ModFile file(Path path, ModFileInfoParser parser, boolean minecraft) throws Exception {
        JarContents contents = JarContents.of(path);
        ModJarMetadata metadata = new ModJarMetadata(contents);
        SecureJar jar = SecureJar.from(contents, metadata);
        ModFile file = new ModFile(jar, parser, IModFile.Type.MOD, ModFileDiscoveryAttributes.DEFAULT);
        metadata.setModFile(file);
        file.identifyMods();
        Field loaders = ModFile.class.getDeclaredField("loaders"); loaders.setAccessible(true);
        loaders.set(file, List.of(minecraft ? new MinecraftModLanguageProvider() : new FMLJavaModLanguageProvider()));
        return file;
    }

    private static ModuleLayer layer(List<ModFile> files, ClassLoader loader) {
        Map<String, ModuleReference> references = new LinkedHashMap<>();
        for (ModFile file : files) {
            ModuleDescriptor descriptor = ModuleDescriptor.newAutomaticModule(file.getModFileInfo().moduleName())
                    .packages(file.getSecureJar().moduleDataProvider().descriptor().packages()).build();
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
