package org.neoforbric.neoforge;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.neoforbric.loader.*;
import org.tomlj.*;

/** Reject runtime facilities which have not been connected before executing a mod. */
public final class NeoForgeAdmission {
    private NeoForgeAdmission() {}
    public static Discovery.Candidate admit(Discovery.Candidate candidate) {
        if (candidate.metadata().ecosystem() != Metadata.Ecosystem.NEOFORGE) return candidate;
        NeoForgeMetadata metadata = NeoForgeMetadata.read(candidate.archive());
        String loader = metadata.modLoader();
        if (!"javafml".equals(loader)) throw unsupported(candidate, "language loader " + loader);
        if (!metadata.mixins().isEmpty()) throw unsupported(candidate, "mod Mixin configurations " + metadata.mixins());
        if (candidate.archive().names().contains("META-INF/coremods.json")) throw unsupported(candidate, "mod coremods");
        if (candidate.archive().names().contains("META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod")) throw unsupported(candidate, "mod coremod services");
        if (!metadata.enumExtensions().isEmpty()) throw unsupported(candidate, "enum extensions " + metadata.enumExtensions());
        return candidate;
    }
    private static Failure unsupported(Discovery.Candidate candidate, String feature) {
        return new Failure("NEOFORGE_FEATURE_UNSUPPORTED", candidate.metadata().id() + " requires unsupported " + feature);
    }
}
