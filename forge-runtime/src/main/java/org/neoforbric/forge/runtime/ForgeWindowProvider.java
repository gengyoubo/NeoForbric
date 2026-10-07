package org.neoforbric.forge.runtime;

import java.util.*;
import java.util.function.*;
import net.minecraftforge.fml.loading.ImmediateWindowProvider;
import net.minecraftforge.client.loading.NoVizFallback;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadInstance;
import com.mojang.blaze3d.platform.Monitor;

/** The native no-early-window implementation, called directly inside G instead of by module-name reflection. */
public final class ForgeWindowProvider implements ImmediateWindowProvider {
    public String name() { return "neoforbric-native-window"; }
    public Runnable initialize(String[] args) { return () -> {}; }
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {}
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        return NoVizFallback.windowHandoff(width, height, title, monitor).getAsLong();
    }
    @SuppressWarnings("unchecked")
    public boolean positionWindow(Optional<Object> monitor, IntConsumer width, IntConsumer height, IntConsumer x, IntConsumer y) {
        return NoVizFallback.windowPositioning((Optional<Monitor>)(Optional<?>)monitor, width, height, x, y);
    }
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> loadingOverlay(Supplier<?> client, Supplier<?> reload, Consumer<Optional<Throwable>> complete, boolean fade) {
        return (Supplier<T>)NoVizFallback.loadingOverlay((Supplier<Minecraft>)client, (Supplier<ReloadInstance>)reload, complete, fade);
    }
    public void updateModuleReads(ModuleLayer layer) {}
    public void periodicTick() {}
    public String getGLVersion() { return NoVizFallback.glVersion(); }
}
