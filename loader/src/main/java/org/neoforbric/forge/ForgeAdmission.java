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
        for (String resource : List.of("META-INF/coremods.json", "META-INF/services/net.minecraftforge.forgespi.coremod.ICoreMod", "META-INF/services/cpw.mods.modlauncher.api.ITransformationService", "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService", "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider", "META-INF/services/net.minecraftforge.fml.IModStateProvider"))
            if (candidate.archive().read(resource) != null) throw unsupported(candidate, resource);
        candidate.archive().requireSupportedLayout(); return candidate;
    }
    private static Failure unsupported(Discovery.Candidate candidate, String feature) { return new Failure("FORGE_FEATURE_UNSUPPORTED", candidate.metadata().id() + " requires an unadapted facility: " + feature); }
}
