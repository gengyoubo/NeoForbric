package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.loader.Failure;
import static org.junit.jupiter.api.Assertions.*;

class ForgePreparationTest {
    @TempDir Path temporary;
    @Test void pinsEveryNativeVersionAndNormalizedInput() throws Exception {
        var lock = ForgePreparation.lock();
        assertEquals("1.21.1", lock.get("minecraft").getAsString()); assertEquals("52.1.0", lock.get("forge").getAsString());
        assertEquals("1.21.1-52.1.0", lock.get("fml").getAsString()); assertEquals("10.2.4", lock.get("modlauncher").getAsString());
        assertEquals("0.8.7", lock.get("mixin").getAsString()); assertEquals(39, lock.getAsJsonArray("preparedFiles").size());
        assertEquals(lock.get("installerSha256"), org.neoforbric.forge.ForgeAnchors.lock().get("installerSha256"));
    }
    @Test void rejectsInstallerPathsAndCoordinatesWhichEscapeWorkspace() {
        for (String path : new String[]{"../outside", "C:/outside", "/absolute", "sub\\outside", "."})
            assertEquals("FORGE_PATH", assertThrows(Failure.class, () -> ForgePreparation.contained(temporary, path)).code());
        for (String coordinate : new String[]{"group:../module:1", "group:module:1:..", "group:module:1@../zip", "group:module"})
            assertEquals("FORGE_COORDINATE", assertThrows(Failure.class, () -> ForgePreparation.artifactPath(coordinate)).code());
        assertEquals("net/minecraftforge/forge/1.21.1-52.1.0/forge-1.21.1-52.1.0-universal.jar", ForgePreparation.artifactPath("net.minecraftforge:forge:1.21.1-52.1.0:universal"));
    }
    private Path preparedPlan() {
        Path plan = Path.of(System.getProperty("forge.testDirectory", ""), "forge-runtime.json");
        Assumptions.assumeTrue(Files.isRegularFile(plan), "Pinned Forge inputs not prepared"); return plan;
    }
    @Test void rejectsNearbyVersionsRatherThanGuessingCompatibility() throws Exception {
        Path original = preparedPlan();
        for (String version : new String[]{"forge", "fml", "modlauncher", "mixin"}) {
            var json = JsonParser.parseString(Files.readString(original)).getAsJsonObject(); json.addProperty(version, json.get(version).getAsString() + ".1");
            Path plan = Files.createTempFile(original.toAbsolutePath().getParent(), ".forge-negative-", ".json");
            try { Files.writeString(plan, json.toString()); assertEquals("FORGE_PLAN", assertThrows(Failure.class, () -> ForgePreparation.verify(plan)).code()); }
            finally { Files.delete(plan); }
        }
    }
    @Test void rejectsSelfAttestedLibraryChecksumsAndMissingInventory() throws Exception {
        Path original = preparedPlan();
        for (boolean remove : new boolean[]{false, true}) {
            var json = JsonParser.parseString(Files.readString(original)).getAsJsonObject(); var files = json.getAsJsonArray("files");
            if (remove) files.remove(files.size() - 2); else files.get(2).getAsJsonObject().addProperty("sha256", "0".repeat(64));
            Path plan = Files.createTempFile(original.toAbsolutePath().getParent(), ".forge-negative-", ".json");
            try { Files.writeString(plan, json.toString()); assertEquals("FORGE_PLAN", assertThrows(Failure.class, () -> ForgePreparation.verify(plan)).code()); }
            finally { Files.delete(plan); }
        }
    }
}
