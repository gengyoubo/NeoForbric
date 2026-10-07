package demo.forgeprobe;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.*;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.*;

@Mod("nf_forge_probe")
public final class ForgeModProbe {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "nf_forge_probe");
    private static final RegistryObject<Item> ITEM = ITEMS.register("item", () -> new Item(new Item.Properties()));
    private record CustomContent(int value) {}
    private static final ResourceLocation CUSTOM_NAME = ResourceLocation.fromNamespaceAndPath("nf_forge_probe", "custom");
    private static final DeferredRegister<CustomContent> CUSTOM = DeferredRegister.create(CUSTOM_NAME, "nf_forge_probe");
    private static final DeferredRegister.RegistryHolder<CustomContent> CUSTOM_REGISTRY = CUSTOM.makeRegistry(() -> new RegistryBuilder<CustomContent>().disableSaving().disableSync());
    private static final RegistryObject<CustomContent> CUSTOM_ENTRY = CUSTOM.register("entry", () -> new CustomContent(17));
    private final ForgeConfigSpec serverSpec;
    private final AtomicInteger common = new AtomicInteger(), deferred = new AtomicInteger();
    private final String owner;
    private volatile String eventThread, workThread;
    private final ForgeConfigSpec.IntValue answer;
    public ForgeModProbe() {
        owner = Thread.currentThread().getName();
        var bus = FMLJavaModLoadingContext.get().getModEventBus(); ITEMS.register(bus); CUSTOM.register(bus);
        var builder = new ForgeConfigSpec.Builder(); answer = builder.defineInRange("answer", 42, 0, 100);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, builder.build());
        var server = new ForgeConfigSpec.Builder(); server.define("serverFlag", true); serverSpec = server.build(); ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, serverSpec);
        bus.addListener(this::common);
    }
    private void common(FMLCommonSetupEvent event) {
        if (answer.get() != 42 || ITEM.get() != ForgeRegistries.ITEMS.getValue(ITEM.getId())) throw new IllegalStateException("Native registration/config identity failed");
        if (serverSpec.isLoaded()) throw new IllegalStateException("Forge SERVER config loaded before a world/sync supplied it");
        if (CUSTOM_ENTRY.get() != CUSTOM_REGISTRY.get().getValue(CUSTOM_ENTRY.getId())) throw new IllegalStateException("Native custom registry identity failed");
        eventThread = Thread.currentThread().getName(); common.incrementAndGet();
        event.enqueueWork(() -> { workThread = Thread.currentThread().getName(); deferred.incrementAndGet(); });
    }
    public void verify() {
        if (common.get() != 1 || deferred.get() != 1 || Subscriber.complete.get() != 1) throw new IllegalStateException("Native setup/deferred/subscriber dispatch incomplete");
        if (eventThread.equals(workThread) || !eventThread.startsWith("modloading-worker-")) throw new IllegalStateException("Native parallel/deferred thread model differs: " + eventThread + " / " + workThread);
        if (ITEM.get().getClass().getClassLoader() != getClass().getClassLoader() || Target.value() != 2) throw new IllegalStateException("Single G / Mixin contract failed");
        RegistryObject<CustomContent> crossOwner = RegistryObject.create(CUSTOM_ENTRY.getId(), CUSTOM_NAME, "cross_owner");
        if (crossOwner.get() != CUSTOM_ENTRY.get() || CUSTOM_ENTRY.get().getClass().getClassLoader() != getClass().getClassLoader()) throw new IllegalStateException("Cross-owner custom registry identity failed");
        try { CUSTOM_REGISTRY.get().register(ResourceLocation.fromNamespaceAndPath("nf_forge_probe", "late"), new CustomContent(19)); throw new AssertionError("Native registry accepted write after freeze"); } catch (IllegalStateException expected) { }
        try { ITEMS.register("too_late", () -> new Item(new Item.Properties())); throw new IllegalStateException("Late DeferredRegister accepted"); } catch (IllegalStateException expected) { if (expected.getMessage().equals("Late DeferredRegister accepted")) throw expected; }
        System.out.println("FORGE_SYNTHETIC_MOD_PASS construct=" + owner + " event=" + eventThread + " work=" + workThread);
    }
    @Mod.EventBusSubscriber(modid="nf_forge_probe", bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Subscriber {
        private static final AtomicInteger complete = new AtomicInteger();
        @SubscribeEvent public static void complete(FMLLoadCompleteEvent event) { complete.incrementAndGet(); }
    }
}
