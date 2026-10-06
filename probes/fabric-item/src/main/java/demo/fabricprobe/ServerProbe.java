package demo.fabricprobe;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ServerProbe implements DedicatedServerModInitializer {
    public static int initialized;
    @Override public void onInitializeServer() {
        if (++initialized != 1 || BuiltInRegistries.ITEM.get(ItemProbe.ID) != ItemProbe.ITEM)
            throw new AssertionError("Fabric server entrypoint did not observe the registered Item");
    }
}
