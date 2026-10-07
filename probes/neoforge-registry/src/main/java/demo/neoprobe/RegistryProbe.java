package demo.neoprobe;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.fml.event.lifecycle.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.*;

@Mod("nf_registry_probe")
public final class RegistryProbe {
    public static final List<String> EVENTS = new ArrayList<>();
    public static final ResourceKey<CreativeModeTab> TAB_KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.withDefaultNamespace("building_blocks"));
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, "nf_registry_probe");
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, "nf_registry_probe");
    public static final DeferredHolder<Block, Block> BLOCK = BLOCKS.register("probe", () -> new Block(BlockBehaviour.Properties.of()));
    public static final DeferredHolder<Item, BlockItem> ITEM = ITEMS.register("probe", () -> new BlockItem(BLOCK.get(), new Item.Properties()));
    public static IEventBus bus;
    public RegistryProbe(IEventBus modBus, ModContainer container, FMLModContainer concrete, Dist dist) {
        if (container != concrete || concrete.getEventBus() != modBus || dist != Dist.CLIENT || modBus == NeoForge.EVENT_BUS)
            throw new AssertionError("Constructor injection did not preserve facade identities");
        EVENTS.add("constructor"); bus = modBus;
        if (BLOCK.isBound() || ITEM.isBound()) throw new AssertionError("Deferred content bound before RegisterEvent");
        BLOCKS.register(modBus); ITEMS.register(modBus);
        modBus.addListener((RegisterEvent event) -> {
            if (event.getRegistryKey() == Registries.BLOCK) EVENTS.add("block-register");
            if (event.getRegistryKey() == Registries.ITEM) EVENTS.add("item-register");
        });
        modBus.addListener((FMLCommonSetupEvent event) -> { EVENTS.add("common-setup"); event.enqueueWork(() -> EVENTS.add("common-work")); });
        modBus.addListener((FMLClientSetupEvent event) -> EVENTS.add("client-setup"));
        modBus.addListener((FMLLoadCompleteEvent event) -> EVENTS.add("complete"));
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == TAB_KEY) { event.accept(ITEM.get()); EVENTS.add("creative-tab"); }
        });
    }
    public static void verifyClient(Object client) {
        try {
            var field = AccessTarget.class.getDeclaredField("VALUE");
            if (!java.lang.reflect.Modifier.isPublic(field.getModifiers()) || java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                throw new AssertionError("First access transformer did not open the field and remove final");
            field.set(null, "after");
            if (!AccessTarget.class.getMethod("value").invoke(null).equals("after")) throw new AssertionError("Second access transformer did not open the method");
        } catch (ReflectiveOperationException error) { throw new AssertionError("Access transformer contract failed", error); }
        if (!EVENTS.equals(List.of("constructor", "block-register", "item-register", "common-setup", "common-work", "client-setup", "complete")))
            throw new AssertionError("Wrong canonical lifecycle: " + EVENTS);
        if (BuiltInRegistries.BLOCK.get(BLOCK.getId()) != BLOCK.get() || BuiltInRegistries.ITEM.get(ITEM.getId()) != ITEM.get()
                || ITEM.get().getBlock() != BLOCK.get() || BuiltInRegistries.ITEM.wrapAsHolder(ITEM.get()) != ITEM.getDelegate())
            throw new AssertionError("Registry / DeferredHolder identity mismatch");
        try { Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("nf_registry_probe", "late"), ITEM.get()); throw new AssertionError("Registry did not freeze"); }
        catch (IllegalStateException frozen) { /* expected */ }
        var tab = BuiltInRegistries.CREATIVE_MODE_TAB.get(TAB_KEY);
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS, true,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
        if (tab.getDisplayItems().stream().noneMatch(stack -> stack.is(ITEM.get()))) throw new AssertionError("Creative tab event did not reach actual vanilla tab contents");
        System.out.println("NEOFORGE_REGISTRY_PROBE_OK injected=4 accessFiles=2 vanillaRegistry=true frozen=true phases=" + EVENTS);
    }
}
