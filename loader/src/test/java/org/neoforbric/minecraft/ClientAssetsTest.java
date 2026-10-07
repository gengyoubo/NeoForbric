package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.loader.Failure;
import static org.junit.jupiter.api.Assertions.*;

class ClientAssetsTest {
    @TempDir Path assets;
    Path index, object;
    String indexHash;

    private void prepare() throws Exception {
        byte[] bytes = "asset-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = GamePreparation.hash(bytes, "SHA-1");
        object = assets.resolve("objects/" + hash.substring(0, 2) + "/" + hash);
        Files.createDirectories(object.getParent()); Files.write(object, bytes);
        index = assets.resolve("index.json");
        Files.writeString(index, new Gson().toJson(Map.of("objects", Map.of(
                "first.ogg", Map.of("hash", hash, "size", bytes.length),
                "alias.ogg", Map.of("hash", hash, "size", bytes.length)))));
        indexHash = GamePreparation.hash(Files.readAllBytes(index), "SHA-1");
    }

    @Test void unchangedObjectsAreReusedAndAliasesAreHashedOnce() throws Exception {
        prepare();
        assertEquals(new ClientAssets.Verification(1, 1, 0), ClientAssets.verify(assets, index, indexHash, false));
        assertEquals(new ClientAssets.Verification(1, 0, 1), ClientAssets.verify(assets, index, indexHash, false));
        Files.setLastModifiedTime(object, FileTime.fromMillis(Files.getLastModifiedTime(object).toMillis() + 10_000));
        assertEquals(new ClientAssets.Verification(1, 1, 0), ClientAssets.verify(assets, index, indexHash, false));
    }

    @Test void changedOrMissingAssetsFailWithoutUpdatingTheReceipt() throws Exception {
        prepare(); ClientAssets.verify(assets, index, indexHash, false);
        byte[] receipt = Files.readAllBytes(assets.resolve(".neoforbric-verified.json"));
        Files.writeString(object, "changed-content");
        assertEquals("INPUT_CHECKSUM", assertThrows(Failure.class, () -> ClientAssets.verify(assets, index, indexHash, false)).code());
        assertArrayEquals(receipt, Files.readAllBytes(assets.resolve(".neoforbric-verified.json")));
        Files.delete(object);
        assertEquals("INPUT_CHECKSUM", assertThrows(Failure.class, () -> ClientAssets.verify(assets, index, indexHash, false)).code());
    }

    @Test void missingCorruptOrDifferentIndexReceiptsRequireHashing() throws Exception {
        prepare(); ClientAssets.verify(assets, index, indexHash, false);
        Path receipt = assets.resolve(".neoforbric-verified.json");
        Files.writeString(receipt, "broken json");
        assertEquals(1, ClientAssets.verify(assets, index, indexHash, false).hashed());
        JsonObject saved = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
        saved.addProperty("indexSha1", "old-index"); Files.writeString(receipt, saved.toString());
        assertEquals(1, ClientAssets.verify(assets, index, indexHash, false).hashed());
        Files.delete(receipt);
        assertEquals(1, ClientAssets.verify(assets, index, indexHash, false).hashed());
    }

    @Test void fullVerificationDetectsEditsWithRestoredSizeAndTimestamp() throws Exception {
        prepare(); ClientAssets.verify(assets, index, indexHash, false);
        FileTime modified = Files.getLastModifiedTime(object);
        Files.writeString(object, "other-content"); Files.setLastModifiedTime(object, modified);
        assertEquals("INPUT_CHECKSUM", assertThrows(Failure.class, () -> ClientAssets.verify(assets, index, indexHash, true)).code());
    }
}
