package org.neoforbric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import org.neoforbric.api.LoadedMods;
import org.neoforbric.api.ModEcosystem;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

/** G-side rendering module. Receives initialized vanilla screens; does not discover mods. */
public final class NeoForbricClientUi {
    private static final GuiIcons TITLE_ICONS = new GuiIcons();
    private static boolean windowIconSet;
    private NeoForbricClientUi() {}
    public static void onTitleScreen(Object instance) {
        TitleScreen screen = (TitleScreen) instance;
        if (screen.children().stream().anyMatch(child -> child instanceof ModsButton)) return;
        int original = screen.height / 4 + 48, base = Math.max(54, Math.min(screen.height / 4 + 36, screen.height - 128));
        for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.getY() >= original) {
            int delta = widget.getY() - original;
            widget.setY(base + delta + (delta >= 48 ? 24 : 0));
        }
        ModsButton button = new ModsButton(screen.width / 2 - 100, base + 48, screen);
        try {
            Method add = Screen.class.getDeclaredMethod("addRenderableWidget", GuiEventListener.class); add.setAccessible(true); add.invoke(screen, button);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot attach Mods button to 1.21.1 TitleScreen", error); }
        if (!windowIconSet) { setWindowIcon(); windowIconSet = true; }
    }
    private static void setWindowIcon() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer icons = GLFWImage.malloc(2, stack); int index = 0;
            for (int size : new int[]{16,32}) {
                try (var input = NeoForbricClientUi.class.getResourceAsStream("/assets/neoforbric/icons/neoforbric-" + size + ".png")) {
                    if (input == null) throw new IOException("Missing window icon");
                    var image = ImageIO.read(input); var pixels = stack.malloc(size * size * 4);
                    for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                        int color = image.getRGB(x, y); pixels.put((byte)(color >> 16)).put((byte)(color >> 8)).put((byte)color).put((byte)(color >> 24));
                    }
                    pixels.flip(); icons.position(index++).width(size).height(size).pixels(pixels);
                }
            }
            icons.position(0); GLFW.glfwSetWindowIcon(Minecraft.getInstance().getWindow().getWindow(), icons);
            GLFW.glfwSetWindowTitle(Minecraft.getInstance().getWindow().getWindow(), "NeoForbric · Minecraft 1.21.1");
        } catch (IOException error) { throw new IllegalStateException("Cannot set NeoForbric window icon", error); }
    }
    public static void verifyTitleScreen(Object instance) {
        Minecraft minecraft = (Minecraft) instance;
        var buttons = minecraft.screen.children().stream().filter(child -> child instanceof ModsButton).map(child -> (ModsButton) child).toList();
        if (buttons.size() != 1) throw new AssertionError("Expected exactly one Mods button");
        buttons.getFirst().onPress();
    }
    static void verifyBack(Minecraft minecraft) {
        if (!(minecraft.screen instanceof TitleScreen) || minecraft.screen.children().stream().filter(child -> child instanceof ModsButton).count() != 1)
            throw new AssertionError("Back did not restore the title with exactly one Mods button");
        net.minecraft.server.Bootstrap.realStdoutPrintln("MODS_BACK_OK title=TitleScreen buttons=1");
    }
    private static final class ModsButton extends Button {
        ModsButton(int x, int y, Screen parent) {
            super(x, y, 200, 20, Component.literal("Mods"), button -> Minecraft.getInstance().setScreen(new NeoForbricModsScreen(parent)), DEFAULT_NARRATION);
            setTooltip(Tooltip.create(Component.literal("View source ecosystems and loader decisions")));
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            LoadedMods.snapshot().stream().filter(mod -> mod.id().equals("neoforbric") && mod.ecosystem() == ModEcosystem.NEOFORBRIC).findFirst()
                    .ifPresent(mod -> TITLE_ICONS.draw(graphics, mod, getX() + 9, getY() + 3, 14));
        }
    }
}
