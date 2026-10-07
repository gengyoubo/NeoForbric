package org.neoforbric.minecraft;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import org.neoforbric.loader.*;

/** Content-addressed assets retain verification receipts; executable runtime inputs always get hashed. */
final class ClientAssets {
    private ClientAssets() {}
    record Verification(int objects, int hashed, int reused) {}

    static Verification verify(Path assets, Path index, String indexSha1, boolean full) throws IOException {
        Path root = assets.toRealPath();
        Path receipt = root.resolve(".neoforbric-verified.json");
        JsonObject previous = new JsonObject();
        if (!full && Files.isRegularFile(receipt)) {
            try {
                JsonObject saved = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
                if (saved.get("schemaVersion").getAsInt() == 1 && saved.get("indexSha1").getAsString().equals(indexSha1))
                    previous = saved.getAsJsonObject("objects");
            } catch (IOException | RuntimeException invalid) { /* Missing/corrupt receipts require full verification. */ }
        }
        if (previous == null) previous = new JsonObject();
        JsonObject objects = JsonParser.parseString(Files.readString(index)).getAsJsonObject().getAsJsonObject("objects");
        Map<String, String> names = new LinkedHashMap<>();
        for (var entry : objects.entrySet()) {
            String hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
            if (!hash.matches("[0-9a-f]{40}")) throw new Failure("INPUT_CHECKSUM", "Invalid client asset hash: " + entry.getKey());
            names.putIfAbsent(hash, entry.getKey());
        }
        JsonObject verified = new JsonObject();
        int hashed = 0, reused = 0;
        for (var entry : names.entrySet()) {
            String hash = entry.getKey();
            Path object = root.resolve("objects/" + hash.substring(0, 2) + "/" + hash);
            if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS) || !object.toRealPath().startsWith(root))
                throw new Failure("INPUT_CHECKSUM", "Missing / invalid client asset: " + entry.getValue());
            BasicFileAttributes before = Files.readAttributes(object, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String stamp = stamp(before);
            JsonElement saved = previous.get(hash);
            if (!full && saved != null && saved.isJsonPrimitive() && saved.getAsJsonPrimitive().isString() && saved.getAsString().equals(stamp)) {
                reused++;
            } else {
                if (!digest(object).equals(hash)) throw new Failure("INPUT_CHECKSUM", "Client asset differs: " + entry.getValue());
                BasicFileAttributes after = Files.readAttributes(object, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!stamp.equals(stamp(after))) throw new Failure("INPUT_CHECKSUM", "Client asset changed during verification: " + entry.getValue());
                hashed++;
            }
            verified.addProperty(hash, stamp);
        }
        // Publish only after all objects pass. A read-only assets directory can still launch.
        if (full || hashed > 0 || previous.size() != verified.size()) {
            JsonObject saved = new JsonObject(); saved.addProperty("schemaVersion", 1); saved.addProperty("indexSha1", indexSha1); saved.add("objects", verified);
            Path temporary = null;
            try {
                temporary = Files.createTempFile(root, ".neoforbric-assets-", ".part");
                Files.writeString(temporary, new Gson().toJson(saved));
                try { Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, receipt, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException unavailable) { System.out.println("[NeoForbric] Asset verification receipt unavailable: " + unavailable.getClass().getSimpleName()); }
            finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
        }
        System.out.println("[NeoForbric] Client assets: " + hashed + " hashed, " + reused + " unchanged (" + names.size() + " objects)");
        return new Verification(names.size(), hashed, reused);
    }

    private static String stamp(BasicFileAttributes attributes) {
        return attributes.size() + "|" + attributes.lastModifiedTime() + "|" + attributes.creationTime() + "|" + attributes.fileKey();
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
