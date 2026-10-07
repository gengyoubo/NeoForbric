package org.neoforbric.neoforge.runtime;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.neoforged.fml.*;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.event.lifecycle.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Per-mod facades and lifecycle dispatch, always hosted by NeoForbric's G. */
public final class NativeNeoForgeRuntime {
    private static List<ModContainer> containers;
    private static boolean contract, constructed;
    private static boolean serverDefaultsLoaded;
    private static final Set<String> phases = new HashSet<>();
    private NativeNeoForgeRuntime() {}
    public static void prepare(LoadingModList plan, ModuleLayer layer, Map<String, List<String>> entrypoints, boolean registryContract) {
        contract = registryContract; List<ModContainer> result = new ArrayList<>();
        for (var info : plan.getMods()) {
            var scan = info.getOwningFile().getFile().getScanResult();
            if (entrypoints.containsKey(info.getModId())) result.add(new FMLModContainer(info, entrypoints.get(info.getModId()), scan, layer));
            else if (contract && info.getModId().equals("neoforge")) result.add(new FMLModContainer(info, List.of(), scan, layer));
            else result.add(info.getLoader().loadMod(info, scan, layer));
        }
        containers = List.copyOf(result);
        try {
            Method loaded = ModList.class.getDeclaredMethod("setLoadedMods", List.class); loaded.setAccessible(true); loaded.invoke(ModList.get(), containers);
            Field field = ModLoader.class.getDeclaredField("modList"); field.setAccessible(true); field.set(null, ModList.get());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    public static void construct() {
        if (constructed) throw new IllegalStateException("Mod construction repeated"); constructed = true;
        DeferredWorkQueue queue = new DeferredWorkQueue("NeoForbric construction");
        try {
            Method constructor = ModContainer.class.getDeclaredMethod("constructMod"); constructor.setAccessible(true);
            for (ModContainer container : containers) {
                if (contract && Set.of("minecraft", "neoforge").contains(container.getModId())) continue;
                ModLoadingContext.get().setActiveContainer(container);
                try { constructor.invoke(container); container.acceptEvent(new FMLConstructModEvent(container, queue)); }
                finally { ModLoadingContext.get().setActiveContainer(null); }
            }
            queue.runTasks();
            System.out.println("NEOFORGE_CONSTRUCTED mods=" + containers.stream().map(ModContainer::getModId).toList());
        } catch (InvocationTargetException error) { throw propagate(error.getCause()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static RuntimeException propagate(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        return error instanceof RuntimeException runtime ? runtime : new IllegalStateException(error);
    }
    public static void phase(String phase) {
        if (!contract) throw new IllegalStateException("Canonical registry contract not selected");
        if (!phases.add(phase)) throw new IllegalStateException("NeoForge phase repeated: " + phase);
        switch (phase) {
            case "REGISTRY_OPEN" -> {
                construct();
                try {
                    var constructor = RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, Registry.class); constructor.setAccessible(true);
                    for (var key : List.of(Registries.BLOCK, Registries.ITEM)) {
                        var registry = lookupRegistry(BuiltInRegistries.REGISTRY, key.location());
                        for (var container : containers) if (!Set.of("minecraft", "neoforge").contains(container.getModId())) {
                            ModLoadingContext.get().setActiveContainer(container);
                            try { container.acceptEvent(constructor.newInstance(key, registry)); }
                            finally { ModLoadingContext.get().setActiveContainer(null); }
                        }
                    }
                } catch (InvocationTargetException error) { throw propagate(error.getCause()); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            }
            case "REGISTRY_FROZEN" -> {
                if (!phases.contains("REGISTRY_OPEN")) throw new IllegalStateException("Freeze precedes registration");
                net.neoforged.fml.config.ConfigTracker.INSTANCE.loadConfigs(net.neoforged.fml.config.ModConfig.Type.CLIENT, net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
                net.neoforged.fml.config.ConfigTracker.INSTANCE.loadConfigs(net.neoforged.fml.config.ModConfig.Type.COMMON, net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
                loadServerDefaults();
                dispatch("Common setup", FMLCommonSetupEvent::new);
            }
            case "CLIENT_INIT" -> {
                if (!phases.contains("REGISTRY_FROZEN")) throw new IllegalStateException("Client init precedes freeze");
                dispatch("Client setup", FMLClientSetupEvent::new); dispatch("Load complete", FMLLoadCompleteEvent::new);
                NeoForge.EVENT_BUS.start();
            }
            default -> throw new IllegalArgumentException("Unknown canonical phase " + phase);
        }
        System.out.println("NEOFORGE_CANONICAL_PHASE " + phase);
    }
    /** Setup-time item scans need SERVER values before a world or remote server supplies them. */
    public static void loadServerDefaults() {
        if (serverDefaultsLoaded) return;
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
        serverDefaultsLoaded = true;
    }
    private static void dispatch(String name, java.util.function.BiFunction<ModContainer, DeferredWorkQueue, ParallelDispatchEvent> factory) {
        DeferredWorkQueue queue = new DeferredWorkQueue(name);
        for (var container : containers) if (!Set.of("minecraft", "neoforge").contains(container.getModId())) {
            ModLoadingContext.get().setActiveContainer(container);
            try { container.acceptEvent(factory.apply(container, queue)); }
            finally { ModLoadingContext.get().setActiveContainer(null); }
        }
        queue.runTasks();
    }
    public static void unsupportedAlias(Registry<?> registry, ResourceLocation from, ResourceLocation to) {
        throw new UnsupportedOperationException("Registry aliases require a separate canonical registry service");
    }
    public static Holder<?> vanillaDelegate(Holder<?> holder) { return holder; }
    /** Vanilla root holders bind at freeze; its already-created child registries are directly available. */
    public static Object lookupRegistry(Registry<?> root, ResourceLocation id) {
        if (root == BuiltInRegistries.REGISTRY) {
            try {
                for (Field field : BuiltInRegistries.class.getFields()) if (Modifier.isStatic(field.getModifiers()) && Registry.class.isAssignableFrom(field.getType())) {
                    Registry<?> registry = (Registry<?>)field.get(null);
                    if (registry.key().location().equals(id)) return registry;
                }
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        return root.get(id);
    }
    public static void creativeTab(net.minecraft.world.item.CreativeModeTab tab, net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters parameters) {
        if (!contract || !phases.contains("REGISTRY_FROZEN")) throw new IllegalStateException("Creative tab built before registry freeze");
        var key = BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElseThrow();
        var strategy = new it.unimi.dsi.fastutil.Hash.Strategy<net.minecraft.world.item.ItemStack>() {
            public int hashCode(net.minecraft.world.item.ItemStack stack) { return net.minecraft.world.item.ItemStack.hashItemAndComponents(stack); }
            public boolean equals(net.minecraft.world.item.ItemStack first, net.minecraft.world.item.ItemStack second) {
                return first == second || (first != null && second != null && net.minecraft.world.item.ItemStack.isSameItemSameComponents(first, second));
            }
        };
        var parent = new net.neoforged.neoforge.common.util.InsertableLinkedOpenCustomHashSet<net.minecraft.world.item.ItemStack>(strategy);
        var search = new net.neoforged.neoforge.common.util.InsertableLinkedOpenCustomHashSet<net.minecraft.world.item.ItemStack>(strategy);
        parent.addAll(tab.getDisplayItems()); search.addAll(tab.getSearchTabDisplayItems());
        var event = new net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent(tab, key, parameters, parent, search);
        for (var container : containers) if (!Set.of("minecraft", "neoforge").contains(container.getModId())) container.acceptEvent(event);
        try {
            Field contents = net.minecraft.world.item.CreativeModeTab.class.getDeclaredField("displayItems"); contents.setAccessible(true); contents.set(tab, parent);
            Field searchContents = net.minecraft.world.item.CreativeModeTab.class.getDeclaredField("displayItemsSearchTab"); searchContents.setAccessible(true); searchContents.set(tab, search);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
}
