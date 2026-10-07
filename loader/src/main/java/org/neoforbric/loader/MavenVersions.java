package org.neoforbric.loader;

import org.apache.maven.artifact.versioning.*;

/** Version grammar only; selection and graph ordering belong to NeoForbric. */
public final class MavenVersions {
    private MavenVersions() {}
    public static boolean matches(String expression, String version) {
        try {
            if (expression.isBlank()) return true;
            VersionRange range = VersionRange.createFromVersionSpec(expression);
            // A bare version is a Maven recommendation, not an exact restriction.
            return range.getRestrictions().isEmpty() || range.containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException invalid) {
            throw new Failure("VERSION_SYNTAX", "Invalid Maven range " + expression, invalid);
        }
    }
}
