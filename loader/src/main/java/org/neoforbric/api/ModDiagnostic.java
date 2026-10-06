package org.neoforbric.api;

import java.util.Objects;

/** A concrete blocker or an unevaluated requirement, supplied by the loader rather than the GUI. */
public record ModDiagnostic(Kind kind, String subject, String value, String explanation) {
    public enum Kind { UNSUPPORTED_FEATURE, REQUIRED_DEPENDENCY }
    public ModDiagnostic {
        Objects.requireNonNull(kind); Objects.requireNonNull(subject); Objects.requireNonNull(value); Objects.requireNonNull(explanation);
    }
}
