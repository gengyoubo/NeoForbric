package org.neoforbric.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

/** A loader decision, including original provenance; never reconstructed from a remapped JAR by UI. */
public record LoadedModInfo(String id, String name, String version, ModEcosystem ecosystem,
                            String description, Path sourceJar, String sourceSha256, LoadStatus status,
                            String runtimeAdapter, String namespace, String reason, byte[] iconPng, List<ModDiagnostic> diagnostics) {
    public LoadedModInfo(String id, String name, String version, ModEcosystem ecosystem, String description, Path sourceJar, String sourceSha256,
                         LoadStatus status, String runtimeAdapter, String namespace, String reason, byte[] iconPng) {
        this(id, name, version, ecosystem, description, sourceJar, sourceSha256, status, runtimeAdapter, namespace, reason, iconPng, List.of());
    }
    public LoadedModInfo {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(version); Objects.requireNonNull(ecosystem);
        Objects.requireNonNull(description); Objects.requireNonNull(status); Objects.requireNonNull(runtimeAdapter);
        Objects.requireNonNull(namespace); Objects.requireNonNull(reason);
        iconPng = iconPng == null ? null : iconPng.clone();
        diagnostics = List.copyOf(diagnostics);
    }
    @Override public byte[] iconPng() { return iconPng == null ? null : iconPng.clone(); }
    public LoadedModInfo withStatus(LoadStatus next, String message) {
        return withDecision(next, message, diagnostics);
    }
    public LoadedModInfo withDecision(LoadStatus next, String message, List<ModDiagnostic> diagnostics) {
        return new LoadedModInfo(id, name, version, ecosystem, description, sourceJar, sourceSha256, next, runtimeAdapter, namespace, message, iconPng, diagnostics);
    }
}
