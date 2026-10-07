package org.neoforbric.minecraft;

import com.google.gson.*;
import java.util.*;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.neoforbric.api.ModDiagnostic;
import org.neoforbric.api.ModDiagnostic.Kind;
import org.neoforbric.loader.*;

/** Limited Java-only Fabric profile; aggregate blockers without loading candidate classes. */
public final class FabricAdmission {
    public record Entry(String group, String className, String api, String method) {}
    private FabricAdmission() {}
    private static JsonObject json(Archive archive) { return FabricJson.metadata(archive); }
    public static Discovery.Candidate admit(Discovery.Candidate candidate) {
        if (candidate.metadata().ecosystem() != Metadata.Ecosystem.FABRIC) return candidate;
        JsonObject json = json(candidate.archive());
        if (!candidate.metadata().id().matches("[a-z][a-z0-9_-]{0,63}")) throw new Failure("FABRIC_SCHEMA", "Invalid Fabric id " + candidate.metadata().id());
        if (!json.has("schemaVersion") || !json.get("schemaVersion").toString().equals("1")) throw new Failure("FABRIC_SCHEMA", "Only Fabric schemaVersion 1 is supported");
        try { Version.parse(candidate.metadata().version()); }
        catch (Exception invalid) { throw new Failure("FABRIC_VERSION", candidate.metadata().id(), invalid); }
        List<ModDiagnostic> diagnostics = new ArrayList<>();
        for (String field : List.of("mixins", "accessWidener", "jars", "languageAdapters", "breaks", "conflicts", "recommends", "suggests")) {
            JsonElement value = json.get(field); if (value == null) continue;
            String explanation = switch (field) {
                case "mixins" -> "The current limited Fabric profile does not apply this Mixin configuration to game classes";
                case "accessWidener" -> "The current limited Fabric profile does not apply this access widener to game classes and members";
                case "jars" -> "The current limited Fabric profile does not discover or load this bundled JAR";
                case "languageAdapters" -> "The current limited Fabric profile cannot instantiate entrypoints through this custom language adapter";
                default -> "The current limited Fabric profile does not evaluate the declared " + field + " dependency rule";
            };
            if (value.isJsonArray()) {
                int index = 0;
                for (JsonElement item : value.getAsJsonArray()) unsupported(diagnostics, field + "[" + index++ + "]", display(item), explanation);
            } else if (value.isJsonObject()) {
                new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, item) -> unsupported(diagnostics, field + "." + key, display(item), explanation));
            } else unsupported(diagnostics, field, display(value), explanation);
        }
        Map<String, String> depends = new TreeMap<>(); List<ModDiagnostic> requirements = new ArrayList<>();
        if (json.has("depends")) {
            if (!json.get("depends").isJsonObject()) throw new Failure("FABRIC_SCHEMA", "depends must be an object");
            new TreeMap<>(json.getAsJsonObject("depends").asMap()).forEach((id, value) -> {
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String expression = value.getAsString(); predicate(id, expression); depends.put(id, expression);
                } else if (value.isJsonArray()) {
                    for (JsonElement expression : value.getAsJsonArray()) {
                        if (!expression.isJsonPrimitive() || !expression.getAsJsonPrimitive().isString()) throw new Failure("FABRIC_SCHEMA", "Invalid dependency array: " + id);
                        predicate(id, expression.getAsString());
                    }
                    unsupported(diagnostics, "depends." + id, display(value), "Dependency arrays are not supported");
                } else throw new Failure("FABRIC_SCHEMA", "Invalid dependency declaration: " + id);
                if (!Set.of("java", "minecraft", "fabricloader").contains(id)) requirements.add(new ModDiagnostic(Kind.REQUIRED_DEPENDENCY, id, display(value),
                        "Not evaluated because this candidate was rejected; this requirement is not itself proof of an unsupported feature"));
            });
        }
        inspectEntries(json, "server", diagnostics);
        if (!diagnostics.isEmpty()) {
            diagnostics.addAll(requirements);
            throw new Failure("FABRIC_FEATURE_UNSUPPORTED", report(candidate.metadata().id(), diagnostics), null, diagnostics);
        }
        Metadata old = candidate.metadata();
        return new Discovery.Candidate(candidate.archive(), new Metadata(old.id(), old.version(), old.ecosystem(), old.environment(), null, depends, Map.of(), Set.of(), old.name(), old.description(), old.iconPath()));
    }
    private static void predicate(String id, String expression) {
        try { VersionPredicate.parse(expression); }
        catch (Exception invalid) { throw new Failure("FABRIC_VERSION", "Invalid dependency " + id + ": " + expression, invalid); }
    }
    private static void unsupported(List<ModDiagnostic> diagnostics, String subject, String value, String explanation) {
        diagnostics.add(new ModDiagnostic(Kind.UNSUPPORTED_FEATURE, subject, value, explanation));
    }
    private static String display(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString();
    }
    private static String report(String id, List<ModDiagnostic> diagnostics) {
        StringBuilder text = new StringBuilder(id).append(" — Unsupported features:\n");
        for (ModDiagnostic item : diagnostics) if (item.kind() == Kind.UNSUPPORTED_FEATURE)
            text.append("- ").append(item.subject()).append(": ").append(item.value()).append("\n  ").append(item.explanation()).append('\n');
        if (diagnostics.stream().anyMatch(item -> item.kind() == Kind.REQUIRED_DEPENDENCY)) {
            text.append("Required dependencies (not evaluated):\n");
            for (ModDiagnostic item : diagnostics) if (item.kind() == Kind.REQUIRED_DEPENDENCY) text.append("- ").append(item.subject()).append(": ").append(item.value()).append('\n');
        }
        return text.toString().stripTrailing();
    }
    public static List<Entry> entries(Discovery.Candidate candidate) { return entries(candidate, "server"); }
    public static List<Entry> entries(Discovery.Candidate candidate, String side) {
        List<ModDiagnostic> diagnostics = new ArrayList<>();
        List<Entry> result = inspectEntries(json(candidate.archive()), side, diagnostics);
        if (!diagnostics.isEmpty()) throw new Failure("FABRIC_FEATURE_UNSUPPORTED", report(candidate.metadata().id(), diagnostics), null, diagnostics);
        return result;
    }
    private static List<Entry> inspectEntries(JsonObject json, String side, List<ModDiagnostic> diagnostics) {
        List<Entry> result = new ArrayList<>(); if (!json.has("entrypoints")) return List.of();
        if (!json.get("entrypoints").isJsonObject()) throw new Failure("FABRIC_SCHEMA", "entrypoints must be an object");
        JsonObject groups = json.getAsJsonObject("entrypoints");
        for (String group : new TreeSet<>(groups.keySet())) {
            boolean supportedGroup = Set.of("main", "server", "client").contains(group);
            if (!supportedGroup) unsupported(diagnostics, "entrypoints." + group, display(groups.get(group)), "Custom entrypoint groups are not supported");
            if (!groups.get(group).isJsonArray()) throw new Failure("FABRIC_SCHEMA", "entrypoints." + group + " must be an array");
            int index = 0;
            for (JsonElement value : groups.getAsJsonArray(group)) {
                String subject = "entrypoints." + group + "[" + index++ + "]", name;
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) name = value.getAsString();
                else if (value.isJsonObject() && value.getAsJsonObject().get("adapter") != null
                        && value.getAsJsonObject().get("adapter").isJsonPrimitive() && value.getAsJsonObject().get("adapter").getAsString().equals("default")
                        && value.getAsJsonObject().get("value") != null && value.getAsJsonObject().get("value").isJsonPrimitive()
                        && value.getAsJsonObject().get("value").getAsJsonPrimitive().isString()) name = value.getAsJsonObject().get("value").getAsString();
                else { unsupported(diagnostics, subject, display(value), "Only default Java class entrypoints are supported"); continue; }
                if (!name.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")) {
                    unsupported(diagnostics, subject, name, "Method / field entrypoints are not supported"); continue;
                }
                if (supportedGroup && (group.equals("main") || group.equals(side))) result.add(new Entry(group, name,
                        group.equals("main") ? "net.fabricmc.api.ModInitializer" : group.equals("client") ? "net.fabricmc.api.ClientModInitializer" : "net.fabricmc.api.DedicatedServerModInitializer",
                        group.equals("main") ? "onInitialize" : group.equals("client") ? "onInitializeClient" : "onInitializeServer"));
            }
        }
        result.sort(Comparator.comparing(e -> e.group().equals("main") ? 0 : 1)); return List.copyOf(result);
    }
    public static boolean matches(String expression, String version) {
        try { return VersionPredicate.parse(expression).test(Version.parse(version)); }
        catch (Exception invalid) { throw new Failure("FABRIC_VERSION", expression + " against " + version, invalid); }
    }
}
