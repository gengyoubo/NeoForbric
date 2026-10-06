package org.neoforbric.loader;

import java.nio.file.Path;
import java.util.*;
import org.neoforbric.api.*;
import org.neoforbric.minecraft.FabricAdmission;

/** Discovery descriptors and final profile decisions; GUI only receives the published API snapshot. */
public final class ModCatalog implements AutoCloseable {
    private final List<LoadedModInfo> entries = new ArrayList<>();
    private final Set<Path> admitted = new HashSet<>();
    private final AuditLog audit;
    private final LoadedMods.Publisher publisher;
    public ModCatalog(List<Discovery.Candidate> candidates, AuditLog audit) {
        this.audit = audit;
        entries.add(new LoadedModInfo("neoforbric", "NeoForbric", "0.1.0", ModEcosystem.NEOFORBRIC,
                "One loader. Multiple mod ecosystems.", null, "", LoadStatus.LOADED, "Native", "Mojang", "Built-in loader", null));
        for (var candidate : candidates) {
            Metadata mod = candidate.metadata();
            ModEcosystem source = switch (mod.ecosystem()) { case FABRIC -> ModEcosystem.FABRIC; case FORGE -> ModEcosystem.FORGE; case NEOFORGE -> ModEcosystem.NEOFORGE; case PROTOTYPE -> ModEcosystem.NEOFORBRIC; };
            String adapter = switch (source) { case FABRIC -> "NeoForbric Fabric Adapter"; case NEOFORBRIC -> "Native"; default -> "Unavailable"; };
            byte[] icon = null;
            if (mod.iconPath() != null && !mod.iconPath().startsWith("/") && !mod.iconPath().contains("\\")
                    && Arrays.stream(mod.iconPath().split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
                byte[] bytes = candidate.archive().read(mod.iconPath());
                if (bytes != null && bytes.length <= 256 * 1024) icon = bytes;
            }
            entries.add(new LoadedModInfo(mod.id(), mod.name(), mod.version(), source, mod.description(), candidate.archive().path(), candidate.archive().hash(),
                    LoadStatus.DISABLED, adapter, source == ModEcosystem.FABRIC ? "intermediary → Mojang" : source == ModEcosystem.NEOFORBRIC ? "Mojang" : "Not transformed", "Not yet initialized", icon));
        }
        publisher = LoadedMods.install(entries);
    }
    public List<Discovery.Candidate> selectClient(List<Discovery.Candidate> candidates) {
        List<Discovery.Candidate> active = new ArrayList<>();
        for (var candidate : candidates) {
            Metadata mod = candidate.metadata();
            if (!mod.available("client")) { state(candidate, LoadStatus.DISABLED, "Excluded on client: environment=" + mod.environment()); continue; }
            if (mod.ecosystem() == Metadata.Ecosystem.FORGE || mod.ecosystem() == Metadata.Ecosystem.NEOFORGE) {
                state(candidate, LoadStatus.UNSUPPORTED, (mod.ecosystem() == Metadata.Ecosystem.FORGE ? "Forge" : "NeoForge") + " adapter not implemented"); continue;
            }
            try {
                var executable = FabricAdmission.admit(candidate);
                candidate.archive().requireSupportedLayout();
                active.add(executable); admitted.add(candidate.archive().path());
            } catch (Failure failed) {
                if (!Set.of("FABRIC_FEATURE_UNSUPPORTED", "UNSUPPORTED_LAYOUT").contains(failed.code())) throw failed;
                state(candidate, LoadStatus.UNSUPPORTED, failed.getMessage(), failed.diagnostics());
            }
        }
        return List.copyOf(active);
    }
    public void loaded(List<Discovery.Candidate> originalMods) {
        for (var candidate : originalMods) state(candidate, LoadStatus.LOADED, "");
    }
    public void selectFabricRuntime(List<Discovery.Candidate> candidates, List<Discovery.Candidate> selected, Map<Path, String> exclusions) {
        Set<Path> paths = new HashSet<>(); selected.forEach(c -> paths.add(c.archive().path()));
        for (var candidate : candidates) {
            if (paths.contains(candidate.archive().path())) admitted.add(candidate.archive().path());
            else state(candidate, LoadStatus.DISABLED, exclusions.getOrDefault(candidate.archive().path(), "Not selected by Fabric dependency resolution"));
        }
    }
    public void failed(String reason) {
        for (int i = 1; i < entries.size(); i++) {
            LoadedModInfo info = entries.get(i);
            if (admitted.contains(info.sourceJar())) replace(i, info.withStatus(LoadStatus.FAILED, reason));
        }
        publisher.publish(entries);
    }
    private void state(Discovery.Candidate candidate, LoadStatus status, String reason) {
        state(candidate, status, reason, List.of());
    }
    private void state(Discovery.Candidate candidate, LoadStatus status, String reason, List<ModDiagnostic> diagnostics) {
        for (int i = 1; i < entries.size(); i++) {
            LoadedModInfo info = entries.get(i);
            if (info.id().equals(candidate.metadata().id()) && info.sourceJar().equals(candidate.archive().path())) {
                replace(i, info.withDecision(status, reason, diagnostics)); publisher.publish(entries); return;
            }
        }
        throw new Failure("CATALOG_STATE", "Unknown candidate " + candidate.metadata().id());
    }
    private void replace(int index, LoadedModInfo info) {
        entries.set(index, info);
        audit.record("CATALOG", "mod-status", info.id(), Map.of("name", info.name(), "sourceEcosystem", info.ecosystem().name(), "status", info.status().name(),
                "runtimeAdapter", info.runtimeAdapter(), "namespace", info.namespace(), "reason", info.reason(), "sourceJar", info.sourceJar().toString(), "sourceSha256", info.sourceSha256(),
                "diagnostics", new com.google.gson.Gson().toJson(info.diagnostics())));
    }
    @Override public void close() { publisher.close(); }
}
