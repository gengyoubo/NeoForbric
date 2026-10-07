package org.neoforbric.neoforge.runtime;

import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.*;
import net.neoforged.fml.config.*;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Versioned Forge calls whose equivalent NeoForge API has a different owner or shape. */
public final class ForgeCompatibilityHooks {
    private ForgeCompatibilityHooks() {}
    public static void registerConfigScreen(Function<Screen, Screen> factory) {
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (container, parent) -> factory.apply(parent));
    }
    public static void registerConfigScreen(BiFunction<Minecraft, Screen, Screen> factory) {
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (container, parent) -> factory.apply(Minecraft.getInstance(), parent));
    }
    public static void registerConfig(ModLoadingContext context, ModConfig.Type type, IConfigSpec spec) { context.getActiveContainer().registerConfig(type, spec); }
    public static void registerConfig(ModLoadingContext context, ModConfig.Type type, IConfigSpec spec, String name) { context.getActiveContainer().registerConfig(type, spec, name); }
    public static <T extends IExtensionPoint> void registerExtensionPoint(ModLoadingContext context, Class<T> type, Supplier<T> factory) {
        context.registerExtensionPoint(type, factory);
        if (type == ConfigScreenFactory.class) context.registerExtensionPoint(IConfigScreenFactory.class, () -> (IConfigScreenFactory)factory.get());
    }
    public static final class ConfigScreenFactory implements IConfigScreenFactory {
        private final BiFunction<Minecraft, Screen, Screen> factory;
        public ConfigScreenFactory(BiFunction<Minecraft, Screen, Screen> factory) { this.factory = factory; }
        public ConfigScreenFactory(Function<Screen, Screen> factory) { this((minecraft, parent) -> factory.apply(parent)); }
        public Screen createScreen(ModContainer container, Screen parent) { return factory.apply(Minecraft.getInstance(), parent); }
    }
}
