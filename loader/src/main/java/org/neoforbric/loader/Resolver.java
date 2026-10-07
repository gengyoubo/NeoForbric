package org.neoforbric.loader;

import java.util.*;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.neoforbric.minecraft.FabricAdmission;

public final class Resolver {
    public record Plan(List<Discovery.Candidate> mods, Map<String, Set<String>> predecessors) {
        public Plan {
            mods = List.copyOf(mods);
            Map<String, Set<String>> copy = new TreeMap<>(); predecessors.forEach((id, edges) -> copy.put(id, Set.copyOf(edges)));
            predecessors = Map.copyOf(copy);
        }
    }
    private static final Map<String, String> BUILTINS = Map.of("minecraft", "1.21.1", "java", "21.0.0", "neoforbric", LoaderVersion.VERSION);
    private Resolver() {}

    public static List<Discovery.Candidate> resolve(List<Discovery.Candidate> candidates, String side, AuditLog audit) {
        return resolve(candidates, side, audit, false);
    }
    public static List<Discovery.Candidate> resolve(List<Discovery.Candidate> candidates, String side, AuditLog audit, boolean fabric) {
        return resolve(candidates, side, audit, fabric, false);
    }
    public static List<Discovery.Candidate> resolve(List<Discovery.Candidate> candidates, String side, AuditLog audit, boolean fabric, boolean neoforge) {
        return plan(candidates, side, audit, fabric, neoforge).mods();
    }
    public static Plan plan(List<Discovery.Candidate> candidates, String side, AuditLog audit, boolean fabric, boolean neoforge) {
        return plan(candidates, side, audit, fabric, neoforge, false);
    }
    public static Plan plan(List<Discovery.Candidate> candidates, String side, AuditLog audit, boolean fabric, boolean neoforge, boolean forge) {
        Map<String, String> builtins = new HashMap<>(BUILTINS);
        if (forge) { builtins.put("forge", "52.1.0"); builtins.put("javafml", "52.1.0"); builtins.put("lowcodefml", "52.1.0"); }
        if (neoforge) {
            try {
                var contract = org.neoforbric.minecraft.NeoForgePreparation.lock();
                builtins.put("neoforge", contract.get("neoforge").getAsString()); builtins.put("javafml", contract.get("fml").getAsString());
            } catch (java.io.IOException invalid) { throw new Failure("NEOFORGE_LOCK", "Cannot read native contract", invalid); }
        }
        if (fabric) builtins.put("fabricloader", FabricLoaderImpl.VERSION); // SPI contract version, not the native loader lifecycle.
        Map<String, Discovery.Candidate> selected = new TreeMap<>();
        for (var candidate : candidates) {
            Metadata mod = candidate.metadata();
            if (!mod.available(side)) {
                audit.record("RESOLVE", "side-excluded", mod.id(), Map.of("side", side, "environment", mod.environment()));
                continue;
            }
            if (builtins.containsKey(mod.id())) throw new Failure("RESERVED_MOD_ID", "Cannot replace builtin " + mod.id());
            var previous = selected.putIfAbsent(mod.id(), candidate);
            if (previous != null) throw new Failure("DUPLICATE_MOD_ID", mod.id() + " occurs in " + previous.archive().path() + " and " + candidate.archive().path());
        }
        List<String> nativeMods = selected.values().stream().filter(c -> c.metadata().ecosystem() != Metadata.Ecosystem.PROTOTYPE && !(fabric && c.metadata().ecosystem() == Metadata.Ecosystem.FABRIC) && !(neoforge && c.metadata().ecosystem() == Metadata.Ecosystem.NEOFORGE) && !(forge && c.metadata().ecosystem() == Metadata.Ecosystem.FORGE))
                .map(c -> c.metadata().id() + " (" + c.metadata().ecosystem() + ", " + c.archive().path() + ")").toList();
        if (!nativeMods.isEmpty()) throw new Failure("NATIVE_RUNTIME_UNSUPPORTED", "Native metadata discovery only; entrypoint / ABI / transforms not implemented: " + nativeMods);
        Map<String, Set<String>> graph = new TreeMap<>(); selected.keySet().forEach(id -> graph.put(id, new TreeSet<>()));
        for (var candidate : selected.values()) {
            Metadata mod = candidate.metadata();
            Set<String> predecessors = graph.get(mod.id());
            if (forge && mod.ecosystem() == Metadata.Ecosystem.FORGE) {
                org.neoforbric.forge.ForgeAdmission.admit(candidate);
                var file = org.neoforbric.forge.ForgeMetadata.read(candidate.archive());
                // Pinned LanguageLoadingProvider checks the actual provider version directly.
                if (!org.neoforbric.forge.ForgeVersionSupport.contains(file.loaderVersion(), "52.1.0")) throw new Failure("DEPENDENCY_VERSION", mod.id() + " requires " + file.modLoader() + " " + file.loaderVersion() + ", selected 52.1.0");
            }
            validateDependencies(mod, mod.depends(), false, selected, predecessors, builtins);
            validateDependencies(mod, mod.optionalDepends(), true, selected, predecessors, builtins);
            mod.after().stream().filter(selected::containsKey).forEach(predecessors::add);
            if (mod.ecosystem() == Metadata.Ecosystem.NEOFORGE) {
                var file = org.neoforbric.neoforge.NeoForgeMetadata.read(candidate.archive());
                if (file.modLoader().equals("javafml") && !MavenVersions.matches(file.loaderVersion(), builtins.get("javafml")))
                    throw new Failure("DEPENDENCY_VERSION", mod.id() + " requires javafml " + file.loaderVersion() + ", selected " + builtins.get("javafml"));
            }
            for (Dependency dependency : mod.constraints()) {
                if (!dependency.side().applies(side)) continue;
                String version = selected.containsKey(dependency.id()) ? selected.get(dependency.id()).metadata().version() : builtins.get(dependency.id());
                boolean present = version != null, matches = forge && mod.ecosystem() == Metadata.Ecosystem.FORGE
                        ? org.neoforbric.forge.ForgeVersionSupport.modMatches(builtins.get("minecraft"), mod.id(), dependency.id(), dependency.range(), version, audit)
                        : present && (MavenVersions.matches(dependency.range(), version)
                        || (neoforge && dependency.id().equals("minecraft") && MavenVersions.matches(dependency.range(), "1.21"))
                        || (neoforge && dependency.id().equals("neoforge") && MavenVersions.matches(dependency.range(), "21.0.166")));
                switch (dependency.kind()) {
                    case REQUIRED -> { if (!present) throw new Failure("MISSING_DEPENDENCY", mod.id() + " requires " + dependency.id() + " " + dependency.range() + " " + dependency.reason()); }
                    case OPTIONAL -> { }
                    case INCOMPATIBLE -> { if (matches) throw new Failure("INCOMPATIBLE_DEPENDENCY", mod.id() + " is incompatible with " + dependency.id() + " " + version + " " + dependency.reason()); }
                    case DISCOURAGED -> { if (matches) audit.record("RESOLVE", "dependency-warning", mod.id(), Map.of("dependency", dependency.id(), "version", version, "reason", dependency.reason())); }
                }
                if (present && !matches && (dependency.kind() == Dependency.Kind.REQUIRED || dependency.kind() == Dependency.Kind.OPTIONAL))
                    throw new Failure("DEPENDENCY_VERSION", mod.id() + " requires " + dependency.id() + " " + dependency.range() + ", selected " + version);
                if (present && selected.containsKey(dependency.id()) && (dependency.kind() == Dependency.Kind.REQUIRED || dependency.kind() == Dependency.Kind.OPTIONAL)) {
                    if (dependency.ordering() == Dependency.Ordering.AFTER) predecessors.add(dependency.id());
                    if (dependency.ordering() == Dependency.Ordering.BEFORE) graph.get(dependency.id()).add(mod.id());
                }
                audit.record("RESOLVE", "dependency-constraint", mod.id(), Map.of("dependency", dependency.id(), "range", dependency.range(), "kind", dependency.kind().name(), "ordering", dependency.ordering().name(), "side", dependency.side().name(), "scheme", dependency.scheme().name()));
            }
        }
        List<Discovery.Candidate> ordered = Order.sort(graph, "mod").stream().map(selected::get).toList();
        audit.record("RESOLVE", "mod-order", "plan", Map.of("ids", ordered.stream().map(c -> c.metadata().id()).toList().toString()));
        return new Plan(ordered, graph);
    }

    private static void validateDependencies(Metadata mod, Map<String, String> dependencies, boolean optional,
                                              Map<String, Discovery.Candidate> selected, Set<String> predecessors, Map<String, String> builtins) {
        dependencies.forEach((id, expression) -> {
            String version = builtins.get(id);
            if (selected.containsKey(id)) version = selected.get(id).metadata().version();
            if (version == null) {
                if (!optional) throw new Failure("MISSING_DEPENDENCY", mod.id() + " requires " + id + " " + expression);
            } else {
                boolean matches = mod.ecosystem() == Metadata.Ecosystem.FABRIC ? FabricAdmission.matches(expression, version) : Version.matches(expression, version);
                if (!matches) throw new Failure("DEPENDENCY_VERSION", mod.id() + " requires " + id + " " + expression + ", selected " + version);
                if (selected.containsKey(id)) predecessors.add(id);
            }
        });
    }
}
