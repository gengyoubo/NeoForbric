package org.neoforbric.installer;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InstallerTest {
    @TempDir Path directory;

    @Test void versionHasOnlyBootstrapLibrariesAndUsesTheInstalledRuntime() throws Exception {
        JsonObject vanilla = JsonParser.parseString("{\"releaseTime\":\"2024-08-08T12:24:45Z\",\"javaVersion\":{\"majorVersion\":21},\"assetIndex\":{\"id\":\"17\"}}").getAsJsonObject();
        JsonObject version = Installer.versionJson(directory, directory.resolve("versions/NF"), "NF", Installer.catalog(), vanilla);
        assertEquals("org.neoforbric.bootstrap.InstalledClient", version.get("mainClass").getAsString());
        assertFalse(version.has("inheritsFrom")); assertEquals("NF", version.getAsJsonObject("assetIndex").get("id").getAsString());
        assertTrue(version.getAsJsonObject("arguments").getAsJsonArray("game").asList().stream().anyMatch(value -> value.getAsString().endsWith("runtime.json")));
        for (JsonElement element : version.getAsJsonArray("libraries")) {
            String name = element.getAsJsonObject().get("name").getAsString();
            assertFalse(name.startsWith("org.neoforbric:loader:"));
            assertFalse(name.startsWith("com.mojang:") || name.startsWith("org.lwjgl:"));
            assertTrue(element.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").get("url").getAsString().startsWith("https://"));
        }
    }
    @Test void profileUpdatePreservesExistingDataAndMakesAnExactBackup() throws Exception {
        Path profiles = directory.resolve("launcher_profiles.json");
        String original = "{\"profiles\":{\"existing\":{\"name\":\"Keep me\",\"lastVersionId\":\"1.21.1\"}},\"selectedProfile\":\"existing\",\"extra\":{\"preserve\":true}}";
        Files.writeString(profiles, original);
        Installer.updateProfiles(directory, directory.resolve("versions/NF"), "NF");
        JsonObject updated = JsonParser.parseString(Files.readString(profiles)).getAsJsonObject();
        assertEquals("existing", updated.get("selectedProfile").getAsString());
        assertEquals(JsonParser.parseString(original).getAsJsonObject().getAsJsonObject("profiles").get("existing"), updated.getAsJsonObject("profiles").get("existing"));
        assertTrue(updated.getAsJsonObject("extra").get("preserve").getAsBoolean());
        assertEquals("NF", updated.getAsJsonObject("profiles").getAsJsonObject("neoforbric-NF").get("lastVersionId").getAsString());
        try (var paths = Files.list(directory)) {
            Path backup = paths.filter(path -> path.getFileName().toString().contains(".neoforbric-backup-")).findFirst().orElseThrow();
            assertEquals(original, Files.readString(backup));
        }
    }
    @Test void invalidProfileAndPathsCannotOverwriteOtherFiles() throws Exception {
        Path profiles = directory.resolve("launcher_profiles.json"); Files.writeString(profiles, "invalid JSON");
        assertThrows(java.io.IOException.class, () -> Installer.updateProfiles(directory, directory.resolve("versions/NF"), "NF"));
        assertEquals("invalid JSON", Files.readString(profiles));
        assertThrows(java.io.IOException.class, () -> Installer.contained(directory, "../outside.jar"));
        for (String id : java.util.List.of("../NF", ".", "", "NF/other", "NF ")) assertThrows(java.io.IOException.class, () -> Installer.validateId(id));
    }
    @Test void installerLayoutRendersWithoutClippedFields() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = InstallerWindow.content(); panel.setSize(750, 500);
            layout(panel);
            BufferedImage image = new BufferedImage(750, 500, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
            Path output = Path.of("build/installer-preview.png");
            try { Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile()); }
            catch (java.io.IOException error) { throw new RuntimeException(error); }
            for (Component component : panel.getComponents()) assertTrue(component.getWidth() > 0 && component.getHeight() > 0);
        });
    }
    private static void layout(Container container) {
        container.doLayout(); for (Component component : container.getComponents()) if (component instanceof Container child) layout(child);
    }
}
