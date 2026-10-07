package org.neoforbric.forge;

import java.util.*;
import org.apache.maven.artifact.versioning.*;
import org.neoforbric.loader.*;

/** Dependency fallback from the pinned Forge 52.1.0 VersionSupportMatrix. Actual versions stay unchanged. */
public final class ForgeVersionSupport {
    private static final Map<String, String> MATRIX = Map.of(
            "mod.minecraft", "1.21", "mod.forge", "51.0.33", "languageloader.javafml", "51");
    private ForgeVersionSupport() {}

    public static Optional<String> fallback(String minecraft, String type, String id, String expression) {
        String version = minecraft.equals("1.21.1") ? MATRIX.get(type + "." + id) : null;
        return version != null && contains(expression, version) ? Optional.of(version) : Optional.empty();
    }

    public static boolean contains(String expression, String version) {
        try { return VersionRange.createFromVersionSpec(expression).containsVersion(new DefaultArtifactVersion(version)); }
        catch (InvalidVersionSpecificationException invalid) { throw new Failure("VERSION_SYNTAX", "Invalid Forge Maven range " + expression, invalid); }
    }

    public static boolean modMatches(String minecraft, String owner, String id, String expression, String actual, AuditLog audit) {
        if (actual == null) return false;
        if (contains(expression, actual) || actual.equals("0.0NONE")) return true;
        var compatible = fallback(minecraft, "mod", id, expression);
        if (compatible.isEmpty()) return false;
        audit.record("RESOLVE", "DEPENDENCY_VERSION_COMPAT", owner, Map.of("dependency", id,
                "range", expression, "selected", actual, "compatibleVersion", compatible.get(),
                "basis", "Forge 52.1.0 VersionSupportMatrix"));
        return true;
    }
}
