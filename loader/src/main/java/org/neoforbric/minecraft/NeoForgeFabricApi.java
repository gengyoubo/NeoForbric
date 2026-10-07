package org.neoforbric.minecraft;

import java.nio.file.*;
import org.neoforbric.loader.*;

/** Pinned NeoForge implementations for Fabric API's events and patched game interfaces. */
public final class NeoForgeFabricApi {
    private NeoForgeFabricApi() {}
    public static Archive prepare(Path directory, AuditLog audit) throws Exception {
        Files.createDirectories(directory);
        String version = "0.116.7+2.2.4+1.21.1";
        String hash = "a9ed758355cdbcc6ee1e71c8c512f0be3e4a407079a21a4b1283c239acd9bc5f";
        Path file = GamePreparation.fetch(directory.resolve("forgified-fabric-api-" + version + ".jar"),
                "https://maven.sinytra.org/org/sinytra/forgified-fabric-api/forgified-fabric-api/" + version + "/forgified-fabric-api-" + version + ".jar", hash, "SHA-256");
        audit.record("DISCOVER", "fabric-native-api", "Forgified Fabric API", java.util.Map.of("version", version, "source", file.toString(), "sha256", hash));
        return Archive.read(file).java21View(audit);
    }
}
