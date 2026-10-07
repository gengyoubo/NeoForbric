package org.neoforbric.neoforge;

import java.util.*;
import org.neoforbric.loader.*;

/** Reject runtime facilities which have not been connected before executing a mod. */
public final class NeoForgeAdmission {
    private NeoForgeAdmission() {}
    public static Discovery.Candidate admit(Discovery.Candidate candidate) {
        if (candidate.metadata().ecosystem() != Metadata.Ecosystem.NEOFORGE) return candidate;
        NeoForgeMetadata metadata = NeoForgeMetadata.read(candidate.archive());
        String loader = metadata.modLoader();
        if (!Set.of("javafml", "lowcodefml", "kotlinforforge", "kotori_scala").contains(loader)) throw unsupported(candidate, "language loader " + loader);
        if (candidate.archive().names().contains("META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod")) throw unsupported(candidate, "mod coremod services");
        return candidate;
    }
    private static Failure unsupported(Discovery.Candidate candidate, String feature) {
        return new Failure("NEOFORGE_FEATURE_UNSUPPORTED", candidate.metadata().id() + " requires unsupported " + feature);
    }
}
