package org.neoforbric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.narration.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import org.neoforbric.api.*;
import java.nio.file.*;
import java.util.*;

/** Renders the published loader decisions; metadata and diagnostics are never reparsed here. */
public final class NeoForbricModsScreen extends Screen {
    private final Screen parent;
    private final List<LoadedModInfo> mods = LoadedMods.snapshot();
    private final GuiIcons icons = new GuiIcons();
    private ModList list;
    private DetailPane details;
    private Button back;
    private int listWidth, rendered;
    private boolean compactDetails;
    private List<Component> hoveredTooltip;
    public NeoForbricModsScreen(Screen parent) { super(Component.literal("Mods")); this.parent = parent; }
    @Override protected void init() {
        LoadedModInfo previous = list == null || list.getSelected() == null ? null : list.getSelected().info;
        listWidth = width < 420 ? width - 24 : Math.max(190, Math.min(340, width / 2));
        list = addRenderableWidget(new ModList(minecraft, listWidth, Math.max(60, height - 88), 44)); list.setX(12);
        int x = width < 420 ? 12 : listWidth + 24;
        details = addRenderableWidget(new DetailPane(x, 44, width - x - 12, Math.max(60, height - 84)));
        for (LoadedModInfo info : mods) list.add(info);
        if (!list.children().isEmpty()) list.setSelected(list.children().stream().filter(row -> row.info == previous).findFirst().orElse(list.children().getFirst()));
        back = addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
        updateCompactView();
    }
    private void updateCompactView() {
        list.visible = width >= 420 || !compactDetails;
        details.visible = width >= 420 || compactDetails;
        back.setMessage(Component.literal(width < 420 && compactDetails ? "Back to list" : "Back"));
    }
    @Override public void onClose() {
        if (width < 420 && compactDetails) { compactDetails = false; updateCompactView(); setFocused(list); }
        else minecraft.setScreen(parent);
    }
    @Override public void removed() { icons.close(); }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoveredTooltip = null;
        if (Boolean.getBoolean("neoforbric.probe.diagnostics") && rendered == 12) {
            mouseX = list.getX() + 70; mouseY = list.selectedStatusY();
        }
        renderBackground(graphics, mouseX, mouseY, partialTick);
        long loaded = mods.stream().filter(mod -> mod.status() == LoadStatus.LOADED).count();
        super.render(graphics, mouseX, mouseY, partialTick);
        // Selection lists can blur their backdrop. Draw the title after widgets to keep it crisp.
        graphics.fill(0, 0, width, 38, 0xe5142830);
        graphics.drawString(font, compactDetails && width < 420 ? "Mod details" : "Mods", 16, 12, 0xffffff);
        String count = loaded + " loaded · " + mods.size() + " listed";
        graphics.drawString(font, count, width - font.width(count) - 16, 14, 0x9acbbf);
        if (hoveredTooltip != null) graphics.renderTooltip(font, hoveredTooltip, Optional.empty(), mouseX, mouseY);
        if (Boolean.getBoolean("neoforbric.probe.mods")) probe(graphics, loaded);
    }
    void selectDiagnosticProbe() {
        ModRow row = list.children().stream().filter(r -> r.info.ecosystem() == ModEcosystem.FABRIC && r.info.status() == LoadStatus.UNSUPPORTED).findFirst().orElse(null);
        if (row != null) list.setSelected(row);
    }
    private void probe(GuiGraphics graphics, long loaded) {
        rendered++;
        try {
            if (rendered == 12) {
                screenshot(graphics, "neoforbric-mods.png");
                if (loaded < 2 || mods.stream().noneMatch(mod -> mod.ecosystem() == ModEcosystem.FABRIC && mod.status() == LoadStatus.LOADED)) throw new AssertionError("Loaded mod catalog differs");
                net.minecraft.server.Bootstrap.realStdoutPrintln("MODS_SCREEN_OK loaded=" + loaded + " listed=" + mods.size() + " sourceAndRuntime=true icons=true");
                if (Boolean.getBoolean("neoforbric.probe.diagnostics")) {
                    if (details.info == null || details.info.diagnostics().isEmpty() || details.maxScroll() <= 0) throw new AssertionError("Diagnostic details are not scrollable");
                    double before = details.scrollPosition();
                    if (!details.mouseScrolled(details.getX() + 10, details.getY() + 10, 0, -100) || details.scrollPosition() <= before) throw new AssertionError("Detail scrolling did not advance");
                } else { onClose(); NeoForbricClientUi.verifyBack(minecraft); }
            }
            if (rendered == 13 && Boolean.getBoolean("neoforbric.probe.diagnostics")) {
                if (hoveredTooltip == null || hoveredTooltip.stream().noneMatch(line -> line.getString().contains("diagnostic.mixins.json"))) throw new AssertionError("Hovered status tooltip omits Mixin config");
                if (hoveredTooltip.stream().anyMatch(line -> font.width(line) > Math.min(320, width - 32))) throw new AssertionError("Status tooltip exceeds its width");
                screenshot(graphics, "neoforbric-mod-tooltip.png");
            }
            if (rendered == 16 && Boolean.getBoolean("neoforbric.probe.diagnostics")) {
                screenshot(graphics, "neoforbric-mod-diagnostics.png");
                net.minecraft.server.Bootstrap.realStdoutPrintln("MODS_DIAGNOSTICS_OK scroll=true tooltip=true blockers=" + details.info.diagnostics().stream().filter(d -> d.kind() == ModDiagnostic.Kind.UNSUPPORTED_FEATURE).count());
                onClose(); NeoForbricClientUi.verifyBack(minecraft);
            }
        } catch (java.io.IOException error) { throw new IllegalStateException("Mods screenshot failed", error); }
    }
    private void screenshot(GuiGraphics graphics, String filename) throws java.io.IOException {
        graphics.flush(); Path path = minecraft.gameDirectory.toPath().resolve("screenshots").resolve(filename); Files.createDirectories(path.getParent());
        try (var image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) { image.writeToFile(path); }
    }
    private List<Component> reasonTooltip(LoadedModInfo info) {
        String reason = info.reason().isBlank() ? status(info.status()) : info.reason();
        int tooltipWidth = Math.min(320, width - 32);
        var wrapped = wrapText(reason, tooltipWidth);
        int limit = Math.max(5, (height - 40) / 10 - 2);
        List<Component> result = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, wrapped.size()); index++) result.add(wrapped.get(index));
        if (wrapped.size() > limit) {
            result.addAll(wrapText("Select this mod; scroll details for the full report.", tooltipWidth));
        }
        return List.copyOf(result);
    }
    private List<Component> wrapText(String value, int available) {
        List<Component> result = new ArrayList<>();
        for (var line : font.getSplitter().splitLines(Component.literal(value), available, net.minecraft.network.chat.Style.EMPTY)) {
            String remaining = line.getString();
            if (remaining.isEmpty()) result.add(Component.empty());
            // Vanilla word wrapping can leave long file paths and compact JSON wider than the pane.
            while (!remaining.isEmpty()) {
                String part = font.plainSubstrByWidth(remaining, Math.max(1, available));
                if (part.isEmpty()) part = remaining.substring(0, remaining.offsetByCodePoints(0, 1));
                result.add(Component.literal(part)); remaining = remaining.substring(part.length());
            }
        }
        return result;
    }
    private String shortText(String text, int available) { return font.plainSubstrByWidth(text.replace('\n', ' '), Math.max(20, available)); }
    static String ecosystem(ModEcosystem source) { return switch (source) { case FABRIC -> "Fabric"; case FORGE -> "Forge"; case NEOFORGE -> "NeoForge"; case NEOFORBRIC -> "NeoForbric"; }; }
    static String status(LoadStatus state) { return switch (state) { case LOADED -> "Loaded"; case DISABLED -> "Disabled"; case FAILED -> "Failed"; case UNSUPPORTED -> "Unsupported"; }; }
    static int color(LoadStatus state) { return switch (state) { case LOADED -> 0x7be2bd; case DISABLED -> 0xa7b0bd; case FAILED -> 0xee827e; case UNSUPPORTED -> 0xf4bd72; }; }
    private final class ModList extends ObjectSelectionList<ModRow> {
        ModList(Minecraft client, int width, int height, int y) { super(client, width, height, y, 66); }
        void add(LoadedModInfo info) { addEntry(new ModRow(info)); }
        int selectedStatusY() { return getRowTop(children().indexOf(getSelected())) + 45; }
        @Override public void setSelected(ModRow row) { super.setSelected(row); if (details != null && row != null) details.select(row.info); }
        @Override public int getRowWidth() { return listWidth - 18; }
        @Override protected int getScrollbarPosition() { return getX() + listWidth - 6; }
    }
    private final class ModRow extends ObjectSelectionList.Entry<ModRow> {
        private final LoadedModInfo info;
        ModRow(LoadedModInfo info) { this.info = info; }
        @Override public Component getNarration() { return Component.literal(info.name() + ", " + ecosystem(info.ecosystem()) + ", " + status(info.status()) + ", " + info.runtimeAdapter() + ", " + info.reason()); }
        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) return false; list.setSelected(this);
            if (width < 420) { compactDetails = true; updateCompactView(); NeoForbricModsScreen.this.setFocused(details); }
            return true;
        }
        @Override public void render(GuiGraphics graphics, int index, int y, int x, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
            icons.draw(graphics, info, x + 4, y + 9, 30); int text = x + 42, space = rowWidth - 47;
            graphics.drawString(font, shortText(info.name(), space), text, y + 5, 0xffffff);
            graphics.drawString(font, shortText(info.version() + " · " + info.id(), space), text, y + 18, 0xb5c0c7);
            graphics.drawString(font, "[" + ecosystem(info.ecosystem()) + "]", text, y + 32, 0x9ad8c8);
            String state = status(info.status()) + (info.status() == LoadStatus.UNSUPPORTED ? " (hover for reason)" : "");
            graphics.drawString(font, shortText(state, space), text, y + 45, color(info.status()));
            if (hovered) {
                if (info.status() == LoadStatus.UNSUPPORTED || info.status() == LoadStatus.FAILED || info.status() == LoadStatus.DISABLED) hoveredTooltip = reasonTooltip(info);
                else hoveredTooltip = List.of(Component.literal("Source: " + ecosystem(info.ecosystem())), Component.literal("Runtime: " + info.runtimeAdapter()), Component.literal("Namespace: " + info.namespace()), Component.literal("Status: " + status(info.status())));
            }
        }
    }
    private record DetailLine(FormattedCharSequence text, int color, int y) {}
    private final class DetailPane extends AbstractScrollWidget {
        private LoadedModInfo info;
        private final List<DetailLine> lines = new ArrayList<>();
        private int contentHeight;
        int maxScroll() { return getMaxScrollAmount(); }
        double scrollPosition() { return scrollAmount(); }
        DetailPane(int x, int y, int width, int height) { super(x, y, width, height, Component.literal("Mod details")); }
        void select(LoadedModInfo next) {
            info = next; lines.clear(); contentHeight = 50;
            field("Status", status(info.status()));
            if (info.status() != LoadStatus.UNSUPPORTED || info.diagnostics().isEmpty()) {
                if (!info.reason().isBlank()) field(info.status() == LoadStatus.UNSUPPORTED ? "Why this mod is unsupported" : "Reason", info.reason());
            } else {
                diagnosticField("Unsupported features", ModDiagnostic.Kind.UNSUPPORTED_FEATURE);
                diagnosticField("Required dependencies (not evaluated)", ModDiagnostic.Kind.REQUIRED_DEPENDENCY);
            }
            field("Mod ID", info.id()); field("Source ecosystem", ecosystem(info.ecosystem()));
            boolean planned = info.status() != LoadStatus.LOADED;
            field(planned ? "Runtime adapter (planned)" : "Runtime adapter", info.runtimeAdapter());
            field(planned ? "Namespace (planned)" : "Namespace", info.namespace());
            if (info.sourceJar() != null) field("Source JAR", info.sourceJar().toString());
            if (!info.description().isBlank()) field("Description", info.description());
            setScrollAmount(0);
        }
        private void diagnosticField(String label, ModDiagnostic.Kind kind) {
            List<ModDiagnostic> diagnostics = info.diagnostics().stream().filter(item -> item.kind() == kind).toList();
            if (diagnostics.isEmpty()) return;
            StringJoiner report = new StringJoiner("\n\n");
            for (ModDiagnostic item : diagnostics) report.add(item.subject() + ": " + item.value() + "\n" + item.explanation());
            field(label + " (" + diagnostics.size() + ")", report.toString());
        }
        private void field(String label, String value) {
            for (var line : wrapText(label, getWidth() - 24)) { lines.add(new DetailLine(line.getVisualOrderText(), 0x7bbfaf, contentHeight)); contentHeight += 10; }
            for (var line : wrapText(value, getWidth() - 24)) { lines.add(new DetailLine(line.getVisualOrderText(), 0xdfe8eb, contentHeight)); contentHeight += 10; }
            contentHeight += 6;
        }
        @Override protected int getInnerHeight() { return contentHeight; }
        @Override protected double scrollRate() { return 20; }
        @Override protected void renderBackground(GuiGraphics graphics) { graphics.fill(getX(), getY(), getRight(), getBottom(), 0xe514242c); }
        @Override protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (info == null) return;
            icons.draw(graphics, info, getX() + 10, getY() + 8, 32);
            graphics.drawString(font, shortText(info.name(), getWidth() - 66), getX() + 52, getY() + 12, 0xffffff);
            graphics.drawString(font, info.version(), getX() + 52, getY() + 26, 0xb1bcc2);
            for (DetailLine line : lines) graphics.drawString(font, line.text(), getX() + 10, getY() + line.y(), line.color());
        }
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (isFocused()) {
                if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) { setScrollAmount(scrollAmount() + (key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * (getHeight() - 20)); return true; }
                if (key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) { setScrollAmount(key == GLFW.GLFW_KEY_HOME ? 0 : getMaxScrollAmount()); return true; }
            }
            return super.keyPressed(key, scanCode, modifiers);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { output.add(NarratedElementType.TITLE, info == null ? Component.literal("Mod details") : Component.literal(info.name() + ", " + status(info.status()) + ", " + info.reason())); }
    }
}
