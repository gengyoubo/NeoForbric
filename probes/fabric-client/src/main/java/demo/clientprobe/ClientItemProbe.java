package demo.clientprobe;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public final class ClientItemProbe implements ModInitializer {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("neoforbric_probe", "client_item");
    public static Item ITEM;
    public static int initialized;
    @Override public void onInitialize() {
        if (++initialized != 1) throw new AssertionError("Fabric main invoked twice");
        ITEM = Registry.register(BuiltInRegistries.ITEM, ID, new Item(new Item.Properties()));
        System.out.println("FABRIC_CLIENT_ITEM_REGISTERED " + ID);
    }
}
