package demo.fabricprobe;

import net.fabricmc.api.ModInitializer;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Ordinary Fabric metadata and Java SPI; no NeoForbric API dependency. Release JAR uses intermediary. */
public final class ItemProbe implements ModInitializer {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("neoforbric_probe", "shared_item");
    public static Item ITEM;
    public static int initialized;
    @Override public void onInitialize() {
        if (++initialized != 1) throw new AssertionError("Fabric main was invoked twice");
        if (Boolean.getBoolean("neoforbric.probe.fail")) throw new IllegalStateException("intentional Fabric probe failure");
        ITEM = Registry.register(BuiltInRegistries.ITEM, ID, new Item(new Item.Properties().stacksTo(16)));
        if (BuiltInRegistries.ITEM.get(ID) != ITEM) throw new AssertionError("Immediate registry visibility failed");
        System.out.println("FABRIC_ITEM_REGISTERED " + ID);
    }
    public static void verify() throws Exception {
        Item lookedUp = BuiltInRegistries.ITEM.get(ID);
        ItemStack stack = new ItemStack(lookedUp, 3);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, ID);
        var holder = BuiltInRegistries.ITEM.getHolder(key).orElseThrow();
        var frozen = MappedRegistry.class.getDeclaredField("frozen"); frozen.setAccessible(true);
        Class<?> gameGson = Class.forName("com.google.gson.Gson", false, Item.class.getClassLoader());
        Class<?> toolsGson = Item.class.getClassLoader().getParent().loadClass("com.google.gson.Gson");
        if (initialized != 1 || ServerProbe.initialized != 1 || lookedUp != ITEM || stack.getItem() != ITEM || holder.value() != ITEM
                || ITEM.builtInRegistryHolder() != holder || BuiltInRegistries.ITEM.getResourceKey(ITEM).orElseThrow() != key
                || !frozen.getBoolean(BuiltInRegistries.ITEM) || !SharedConstants.getCurrentVersion().getName().equals("1.21.1")
                || Item.class.getClassLoader() != ItemProbe.class.getClassLoader() || ModInitializer.class.getClassLoader() == Item.class.getClassLoader()
                || gameGson == toolsGson || gameGson.getClassLoader() != Item.class.getClassLoader())
            throw new AssertionError("Registry / ItemStack / Holder / ResourceKey / version / loader identity differs");
        try {
            Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("neoforbric_probe", "late"), ITEM);
            throw new AssertionError("Frozen registry accepted a late registration");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("frozen")) throw expected;
        }
        net.minecraft.server.Bootstrap.realStdoutPrintln("MINECRAFT_PROBE_OK version=1.21.1 main=1 server=1 itemIdentity=true stackIdentity=true holderIdentity=true keyIdentity=true frozen=true lateRejected=true libraryIsolation=true gameLoader=" + Item.class.getClassLoader().getName());
    }
    public static void verifyServer(Object instance) throws Exception {
        verify();
        if (Boolean.getBoolean("neoforbric.probe.server.fail")) throw new IllegalStateException("intentional server tick failure");
        net.minecraft.server.MinecraftServer server = (net.minecraft.server.MinecraftServer) instance;
        if (Thread.currentThread() != server.getRunningThread() || server.overworld() == null || server.getTickCount() < 1)
            throw new AssertionError("World / tick / server thread is unavailable");
        var data = server.overworld().getDataStorage().computeIfAbsent(ProbeData.FACTORY, "neoforbric_item_probe");
        if (data.stack.getItem() != ITEM || data.stack.getCount() != 3) throw new AssertionError("Saved ItemStack identity differs");
        int previous = data.launches++; data.setDirty();
        net.minecraft.server.Bootstrap.realStdoutPrintln("SERVER_PROBE_OK world=true thread=Server_thread savedStackIdentity=true previousLaunches=" + previous);
    }
}
