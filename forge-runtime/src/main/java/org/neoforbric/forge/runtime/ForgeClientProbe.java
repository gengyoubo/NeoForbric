package org.neoforbric.forge.runtime;

import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.*;
import net.minecraftforge.fml.*;

/** Called after the real title screen finishes its fade and resource reload. */
public final class ForgeClientProbe {
    private ForgeClientProbe() {}
    public static void verifyClient(Object instance) throws Exception {
        Minecraft client = (Minecraft)instance; RenderSystem.assertOnRenderThread();
        if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null || client.getWindow().getWindow() == 0 || !ModLoader.isLoadingStateValid())
            throw new IllegalStateException("FORGE_CLIENT_PROBE: Main menu/window/loading contract incomplete");
        Map<String, Object> mods = new LinkedHashMap<>();
        for (String id : List.of("forge", "jei", "mezz_config")) {
            Object mod = ModList.get().getModContainerById(id).orElseThrow().getMod();
            if (mod == null || mod.getClass().getClassLoader() != ForgeClientProbe.class.getClassLoader()) throw new IllegalStateException("FORGE_CLIENT_PROBE: Wrong mod owner " + id);
            mods.put(id, mod.getClass().getName());
        }
        var buttons = client.screen.children().stream().filter(child -> child instanceof AbstractWidget widget && widget.visible && !(widget instanceof PlainTextButton)).map(child -> (AbstractWidget)child).toList();
        List<Map<String, Object>> layout = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            var a = buttons.get(i);
            if (a.getX() < 0 || a.getY() < 0 || a.getX() + a.getWidth() > client.screen.width || a.getY() + a.getHeight() > client.screen.height) throw new IllegalStateException("FORGE_CLIENT_PROBE: Button outside menu " + a.getMessage().getString());
            for (int j = i + 1; j < buttons.size(); j++) {
                var b = buttons.get(j);
                if (a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth() && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight())
                    throw new IllegalStateException("FORGE_CLIENT_PROBE: Buttons overlap: " + a.getMessage().getString() + " / " + b.getMessage().getString());
            }
            layout.add(Map.of("label", a.getMessage().getString(), "x", a.getX(), "y", a.getY(), "width", a.getWidth(), "height", a.getHeight()));
        }
        if (buttons.stream().filter(button -> button.getMessage().getString().equals("Mods")).count() != 1) throw new IllegalStateException("FORGE_CLIENT_PROBE: Duplicate or missing Mods button");
        Path output = client.gameDirectory.toPath();
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(output.resolve("forge-main-menu.png")); }
        Map<String, Object> report = Map.of("result", "PASS", "screen", client.screen.getClass().getName(), "thread", Thread.currentThread().getName(), "mods", mods, "singleG", true, "buttons", layout, "buttonsOverlap", false);
        Files.writeString(output.resolve("forge-client-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("FORGE_CLIENT_MENU_PASS screenshot=" + output.resolve("forge-main-menu.png"));
    }
}
