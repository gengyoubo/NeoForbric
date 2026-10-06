package org.neoforbric.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.neoforbric.api.*;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

final class GuiIcons implements AutoCloseable {
    record Icon(ResourceLocation location, int width, int height) {}
    private final Map<String, Icon> icons = new HashMap<>();
    Icon icon(LoadedModInfo mod) {
        return icons.computeIfAbsent(mod.ecosystem() + ":" + mod.id() + ":" + mod.sourceSha256(), key -> {
            byte[] bytes = mod.iconPng();
            if (bytes != null) {
                try { return load(bytes); } catch (IOException | RuntimeException invalidIcon) { /* Display a source mark; icon errors do not change mod state. */ }
            }
            String file = switch (mod.ecosystem()) { case FABRIC -> "fabric.png"; case FORGE -> "forge.png"; case NEOFORGE -> "neoforge.png"; case NEOFORBRIC -> "neoforbric-32.png"; };
            try (var stream = GuiIcons.class.getResourceAsStream("/assets/neoforbric/icons/" + file)) {
                if (stream == null) throw new IOException("Missing bundled icon " + file);
                return load(stream.readAllBytes());
            } catch (IOException failed) { throw new IllegalStateException("Cannot load built-in ecosystem icon", failed); }
        });
    }
    private Icon load(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Not an image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0) > 512 || reader.getHeight(0) > 512) throw new IOException("Icon must be a PNG at most 512x512");
            } finally { reader.dispose(); }
        }
        NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
        int width = image.getWidth(), height = image.getHeight();
        var location = Minecraft.getInstance().getTextureManager().register("neoforbric_mod_icon", new DynamicTexture(image));
        return new Icon(location, width, height);
    }
    void draw(GuiGraphics graphics, LoadedModInfo mod, int x, int y, int size) {
        Icon icon = icon(mod); graphics.blit(icon.location(), x, y, size, size, 0F, 0F, icon.width(), icon.height(), icon.width(), icon.height());
    }
    @Override public void close() {
        icons.values().forEach(icon -> Minecraft.getInstance().getTextureManager().release(icon.location())); icons.clear();
    }
}
