package org.neoforbric.neoforge;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.neoforbric.loader.*;
import org.tomlj.*;

/** Translate the pack's explicit FML dependency overrides into canonical constraints. */
public final class NeoForgeOverrides {
    private NeoForgeOverrides() {}
    public static List<Discovery.Candidate> apply(List<Discovery.Candidate> mods, Path gameDirectory, AuditLog audit) throws IOException {
        Path config = gameDirectory.resolve("config/fml.toml"); if (!Files.exists(config)) return mods;
        var toml = Toml.parse(Files.readString(config));
        if (toml.hasErrors()) throw new Failure("NEOFORGE_CONFIG", "Invalid FML configuration: " + toml.errors());
        var overrides = toml.getTable("dependencyOverrides"); if (overrides == null) return mods;
        List<Discovery.Candidate> result = new ArrayList<>();
        for (var candidate : mods) {
            Metadata mod = candidate.metadata(); Object value = overrides.get(List.of(mod.id()));
            List<String> rules = new ArrayList<>();
            if (value instanceof String rule) rules.add(rule);
            else if (value instanceof TomlArray array) for (int i = 0; i < array.size(); i++) rules.add(array.getString(i));
            else if (value != null) throw new Failure("NEOFORGE_CONFIG", "Invalid dependencyOverrides for " + mod.id());
            if (rules.isEmpty()) { result.add(candidate); continue; }
            List<Dependency> constraints = new ArrayList<>(mod.constraints());
            for (String rule : rules) {
                if (rule.length() < 2 || (rule.charAt(0) != '+' && rule.charAt(0) != '-')) throw new Failure("NEOFORGE_CONFIG", "Invalid dependency override " + rule);
                String target = rule.substring(1);
                if (rule.charAt(0) == '-') constraints.removeIf(dep -> dep.id().equals(target));
                else constraints.add(new Dependency(target, "", Dependency.Scheme.MAVEN, Dependency.Kind.OPTIONAL, Dependency.Ordering.AFTER, Dependency.Side.BOTH, "Explicit pack FML override"));
                audit.record("RESOLVE", "neoforge-dependency-override", mod.id(), Map.of("rule", rule, "config", config.toString()));
            }
            result.add(new Discovery.Candidate(candidate.archive(), new Metadata(mod.id(), mod.version(), mod.ecosystem(), mod.environment(), mod.entrypoint(), mod.depends(), mod.optionalDepends(), mod.after(), mod.name(), mod.description(), mod.iconPath(), constraints)));
        }
        return List.copyOf(result);
    }
}
