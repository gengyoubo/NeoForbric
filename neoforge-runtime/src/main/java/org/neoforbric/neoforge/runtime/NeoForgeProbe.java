package org.neoforbric.neoforge.runtime;

import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;

/** Runs only when requested by the development launch, after the title screen renders. */
public final class NeoForgeProbe {
    private NeoForgeProbe() {}
    public static void verifyClient(Object client) {
        var minecraft = (net.minecraft.client.Minecraft)client;
        if (minecraft.screen.children().stream().anyMatch(child -> child instanceof net.neoforged.neoforge.client.gui.widget.ModsButton))
            throw new IllegalStateException("Native NeoForge Mods button must be replaced by the kernel UI");
        long buttons = minecraft.screen.children().stream().filter(child -> child.getClass().getName().equals("org.neoforbric.client.NeoForbricClientUi$ModsButton")).count();
        if (buttons != 1) throw new IllegalStateException("Expected one NeoForbric Mods button, found " + buttons);
        System.out.println("NEOFORGE_TITLE_UI_OK neoforbricButtons=" + buttons + " nativeButtons=0");
        if (Boolean.getBoolean("neoforbric.probe.mods")) {
            try (var image = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                image.writeToFile(minecraft.gameDirectory.toPath().resolve("neoforge-title-probe.png"));
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        String namespace = "ecologicalgarden";
        if (!ModList.get().isLoaded("neoforge")) throw new IllegalStateException("NeoForge did not construct successfully");
        if (!ModList.get().isLoaded(namespace)) {
            if (Boolean.getBoolean("neoforbric.neoforge.worldProbe")) throw new IllegalStateException("World probe requires EcologicalGarden");
            System.out.println("NEOFORGE_PROBE_OK mods=" + ModList.get().getMods().stream().map(mod -> mod.getModId()).toList());
            return;
        }
        if (ModList.get().getModContainerById(namespace).isEmpty())
            throw new IllegalStateException("EcologicalGarden did not construct successfully");
        long items = BuiltInRegistries.ITEM.keySet().stream().filter(id -> id.getNamespace().equals(namespace)).count();
        long blocks = BuiltInRegistries.BLOCK.keySet().stream().filter(id -> id.getNamespace().equals(namespace)).count();
        long entities = BuiltInRegistries.ENTITY_TYPE.keySet().stream().filter(id -> id.getNamespace().equals(namespace)).count();
        if (items == 0 || blocks == 0 || entities == 0) throw new IllegalStateException("EcologicalGarden content was not registered");
        System.out.println("NEOFORGE_PROBE_OK target=" + namespace + " items=" + items + " blocks=" + blocks + " entityTypes=" + entities
                + " gameLoader=" + client.getClass().getClassLoader().getName() + " itemIdentity=" + (net.minecraft.world.item.Item.class.getClassLoader() == client.getClass().getClassLoader()));
        if (Boolean.getBoolean("neoforbric.neoforge.worldProbe")) NeoForgeWorldProbe.start((net.minecraft.client.Minecraft)client);
    }
}
