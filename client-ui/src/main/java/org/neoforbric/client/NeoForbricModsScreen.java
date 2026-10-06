package org.neoforbric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.neoforbric.api.*;
import java.nio.file.*;
import java.util.*;

/** Every row is the kernel's immutable final decision, including original JAR provenance. */
public final class NeoForbricModsScreen extends Screen {
    private final Screen parent;
    private final List<LoadedModInfo> mods = LoadedMods.snapshot();
    private final GuiIcons icons = new GuiIcons();
    private ModList list;
    private int listWidth, rendered;
    public NeoForbricModsScreen(Screen parent) { super(Component.literal("Mods")); this.parent = parent; }
    @Override protected void init() {
        listWidth = width < 420 ? width - 24 : Math.max(190, Math.min(340, width / 2));
        list = addRenderableWidget(new ModList(minecraft, listWidth, Math.max(60, height - 88), 44)); list.setX(12);
        for (LoadedModInfo info : mods) list.add(info);
        if (!list.children().isEmpty()) list.setSelected(list.children().getFirst());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void removed() { icons.close(); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, 38, 0xe5142830);
        graphics.drawString(font, "Mods", 16, 12, 0xffffff);
        long loaded = mods.stream().filter(mod -> mod.status() == LoadStatus.LOADED).count();
        String count = loaded + " loaded · " + mods.size() + " listed";
        graphics.drawString(font, count, width - font.width(count) - 16, 14, 0x9acbbf);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (width >= 420 && list.getSelected() != null) details(graphics, list.getSelected().info);
        if (Boolean.getBoolean("neoforbric.probe.mods") && ++rendered == 12) {
            try {
                graphics.flush(); Path path = minecraft.gameDirectory.toPath().resolve("screenshots/neoforbric-mods.png"); Files.createDirectories(path.getParent());
                try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
                if (loaded < 2 || mods.stream().noneMatch(mod -> mod.ecosystem() == ModEcosystem.FABRIC && mod.status() == LoadStatus.LOADED)) throw new AssertionError("Loaded mod catalog differs");
                net.minecraft.server.Bootstrap.realStdoutPrintln("MODS_SCREEN_OK loaded=" + loaded + " listed=" + mods.size() + " sourceAndRuntime=true icons=true");
                onClose(); NeoForbricClientUi.verifyBack(minecraft);
            } catch (java.io.IOException error) { throw new IllegalStateException("Mods screenshot failed", error); }
        }
    }
    private void details(GuiGraphics graphics, LoadedModInfo info) {
        int x = listWidth + 24, right = width - 12, available = right - x - 16, y = 52;
        graphics.fill(x, 44, right, height - 40, 0xc414242c);
        icons.draw(graphics, info, x + 10, y, 32);
        graphics.drawString(font, shortText(info.name(), available - 42), x + 52, y + 4, 0xffffff);
        graphics.drawString(font, info.version(), x + 52, y + 18, 0xb1bcc2); y += 46;
        y = field(graphics, "Mod ID", info.id(), x + 10, y, available);
        y = field(graphics, "Source ecosystem", ecosystem(info.ecosystem()), x + 10, y, available);
        y = field(graphics, "Runtime adapter", info.runtimeAdapter(), x + 10, y, available);
        y = field(graphics, "Namespace", info.namespace(), x + 10, y, available);
        y = field(graphics, "Status", status(info.status()), x + 10, y, available);
        if (!info.reason().isBlank()) y = field(graphics, "Reason", info.reason(), x + 10, y, available);
        if (!info.description().isBlank() && y < height - 74) field(graphics, "Description", info.description(), x + 10, y, available);
    }
    private int field(GuiGraphics graphics, String label, String value, int x, int y, int available) {
        if (y + 20 > height - 46) return y;
        graphics.drawString(font, label, x, y, 0x7bbfaf); y += 10;
        for (var line : font.split(Component.literal(value), Math.max(20, available))) {
            if (y + 9 > height - 46) break;
            graphics.drawString(font, line, x, y, 0xdfe8eb); y += 10;
        }
        return y + 4;
    }
    private String shortText(String text, int available) { return font.plainSubstrByWidth(text.replace('\n', ' '), Math.max(20, available)); }
    static String ecosystem(ModEcosystem source) { return switch (source) { case FABRIC -> "Fabric"; case FORGE -> "Forge"; case NEOFORGE -> "NeoForge"; case NEOFORBRIC -> "NeoForbric"; }; }
    static String status(LoadStatus state) { return switch (state) { case LOADED -> "Loaded"; case DISABLED -> "Disabled"; case FAILED -> "Failed"; case UNSUPPORTED -> "Unsupported"; }; }
    static int color(LoadStatus state) { return switch (state) { case LOADED -> 0x7be2bd; case DISABLED -> 0xa7b0bd; case FAILED -> 0xee827e; case UNSUPPORTED -> 0xf4bd72; }; }
    private final class ModList extends ObjectSelectionList<ModRow> {
        ModList(Minecraft client, int width, int height, int y) { super(client, width, height, y, 66); }
        void add(LoadedModInfo info) { addEntry(new ModRow(info)); }
        @Override public int getRowWidth() { return listWidth - 18; }
        @Override protected int getScrollbarPosition() { return getX() + listWidth - 6; }
    }
    private final class ModRow extends ObjectSelectionList.Entry<ModRow> {
        private final LoadedModInfo info;
        ModRow(LoadedModInfo info) { this.info = info; }
        @Override public Component getNarration() { return Component.literal(info.name() + ", " + info.version() + ", " + ecosystem(info.ecosystem()) + ", " + status(info.status()) + ", " + info.runtimeAdapter()); }
        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { if (button != 0) return false; list.setSelected(this); return true; }
        @Override public void render(GuiGraphics graphics, int index, int y, int x, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
            icons.draw(graphics, info, x + 4, y + 9, 30); int text = x + 42, space = rowWidth - 47;
            graphics.drawString(font, shortText(info.name(), space), text, y + 5, 0xffffff);
            graphics.drawString(font, shortText(info.version() + " · " + info.id(), space), text, y + 18, 0xb5c0c7);
            graphics.drawString(font, "[" + ecosystem(info.ecosystem()) + "]", text, y + 32, 0x9ad8c8);
            graphics.drawString(font, status(info.status()), text, y + 45, color(info.status()));
            if (hovered) graphics.renderTooltip(font, List.of(Component.literal("Source: " + ecosystem(info.ecosystem())), Component.literal("Runtime: " + info.runtimeAdapter()),
                    Component.literal("Namespace: " + info.namespace()), Component.literal("Status: " + status(info.status()) + (info.reason().isBlank() ? "" : " · " + info.reason())),
                    Component.literal(info.sourceJar() == null ? "Built-in loader" : info.sourceJar().getFileName().toString())), Optional.empty(), mouseX, mouseY);
        }
    }
}
