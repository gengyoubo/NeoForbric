package org.neoforbric.loader;

import java.util.*;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.neoforbric.minecraft.FabricAdmission;

public final class Resolver {
    private static final Map<String, String> BUILTINS = Map.of("minecraft", "1.21.1", "java", "21.0.0", "neoforbric", "0.1.0");
    private Resolver() {}

    public static List<Discovery.Candidate> resolve(List<Discovery.Candidate> candidates, String side, AuditLog audit) {
        return resolve(candidates, side, audit, false);
    }
    public static List<Discovery.Candidate> resolve(List<Discovery.Candidate> candidates, String side, AuditLog audit, boolean fabric) {
        Map<String, String> builtins = new HashMap<>(BUILTINS);
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
        List<String> nativeMods = selected.values().stream().filter(c -> c.metadata().ecosystem() != Metadata.Ecosystem.PROTOTYPE && !(fabric && c.metadata().ecosystem() == Metadata.Ecosystem.FABRIC))
                .map(c -> c.metadata().id() + " (" + c.metadata().ecosystem() + ", " + c.archive().path() + ")").toList();
        if (!nativeMods.isEmpty()) throw new Failure("NATIVE_RUNTIME_UNSUPPORTED", "Native metadata discovery only; entrypoint / ABI / transforms not implemented: " + nativeMods);
        Map<String, Set<String>> graph = new TreeMap<>();
        for (var candidate : selected.values()) {
            Metadata mod = candidate.metadata();
            Set<String> predecessors = new TreeSet<>();
            validateDependencies(mod, mod.depends(), false, selected, predecessors, builtins);
            validateDependencies(mod, mod.optionalDepends(), true, selected, predecessors, builtins);
            mod.after().stream().filter(selected::containsKey).forEach(predecessors::add);
            graph.put(mod.id(), predecessors);
        }
        List<Discovery.Candidate> ordered = Order.sort(graph, "mod").stream().map(selected::get).toList();
        audit.record("RESOLVE", "mod-order", "plan", Map.of("ids", ordered.stream().map(c -> c.metadata().id()).toList().toString()));
        return ordered;
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
