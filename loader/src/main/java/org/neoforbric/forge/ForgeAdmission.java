package org.neoforbric.forge;

import java.util.*;
import org.neoforbric.loader.*;

/** Feature admission is separate from native FML construction. */
public final class ForgeAdmission {
    private ForgeAdmission() {}
    public static Discovery.Candidate admit(Discovery.Candidate candidate) {
        if (candidate.metadata().ecosystem() != Metadata.Ecosystem.FORGE) throw new Failure("FORGE_PROFILE", "Forge profile does not admit " + candidate.metadata().ecosystem() + ": " + candidate.metadata().id());
        String language = ForgeMetadata.read(candidate.archive()).modLoader();
        if (!Set.of("javafml", "lowcodefml").contains(language)) throw unsupported(candidate, "language provider " + language);
        library(candidate.archive()); return candidate;
    }
    public static void library(Archive archive) {
        for (String resource : List.of("META-INF/services/net.minecraftforge.forgespi.coremod.ICoreMod", "META-INF/services/cpw.mods.modlauncher.api.ITransformationService", "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService", "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider", "META-INF/services/net.minecraftforge.fml.IModStateProvider"))
            if (archive.read(resource) != null) throw new Failure("FORGE_FEATURE_UNSUPPORTED", archive.path() + " requires an unadapted facility: " + resource);
        byte[] scripts = archive.read("META-INF/coremods.json");
        if (scripts != null) {
            try {
                var declared = com.google.gson.JsonParser.parseString(new String(scripts, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (var entry : declared.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Coremod script must be a string");
                    String path = entry.getValue().getAsString();
                    if (path.startsWith("/") || path.contains("\\") || path.contains(":") || Arrays.stream(path.split("/", -1)).anyMatch(part -> Set.of("", ".", "..").contains(part)) || archive.read(path) == null)
                        throw new IllegalArgumentException("Missing or invalid coremod script " + path);
                }
            } catch (RuntimeException error) { throw new Failure("FORGE_COREMOD", archive.path() + ": " + error.getMessage(), error); }
        }
        archive.requireSupportedLayout();
    }
    private static Failure unsupported(Discovery.Candidate candidate, String feature) { return new Failure("FORGE_FEATURE_UNSUPPORTED", candidate.metadata().id() + " requires an unadapted facility: " + feature); }
}
