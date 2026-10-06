package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.loader.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientCacheTest {
    @TempDir Path directory;
    @Test void regeneratedLibrariesDoNotInvalidateRetainedGamesButChangedGameBytesFail() throws Exception {
        List<Map<String,String>> files = new ArrayList<>();
        for (String role : List.of("game", "game-intermediary")) {
            String name = role.equals("game") ? "client-mojang.jar" : "client-intermediary.jar";
            Files.writeString(directory.resolve(name), role);
            files.add(Map.of("role", role, "path", name, "sha256", Archive.sha256(Files.readAllBytes(directory.resolve(name))), "originalSha256", "raw-game"));
        }
        files.add(Map.of("role", "library", "path", "regenerated.jar", "sha256", "old-library-hash"));
        Files.writeString(directory.resolve("runtime.json"), new Gson().toJson(Map.of("schemaVersion", 1, "minecraft", "1.21.1", "java", 21,
                "namespace", "mojang", "side", "client", "inputLockSha256", Archive.sha256(GamePreparation.lockBytes()),
                "versionSha1", GamePreparation.lock().get("versionSha1").getAsString(), "files", files)));
        Files.writeString(directory.resolve("regenerated.jar"), "new verified derivation");
        ClientPreparation.verifyCachedGames(directory, "raw-game");
        Files.writeString(directory.resolve("client-mojang.jar"), "tampered game");
        assertEquals("INPUT_CHECKSUM", assertThrows(Failure.class, () -> ClientPreparation.verifyCachedGames(directory, "raw-game")).code());
    }
}
