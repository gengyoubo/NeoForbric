package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.neoforbric.loader.*;

/** A declared, limited Java-only Fabric server profile. No language adapter or Mixin fallback. */
public final class FabricAdmission {
    public record Entry(String group, String className, String api, String method) {}
    private FabricAdmission() {}
    private static JsonObject json(Archive archive) { return JsonParser.parseString(new String(archive.read("fabric.mod.json"), StandardCharsets.UTF_8)).getAsJsonObject(); }
    public static Discovery.Candidate admit(Discovery.Candidate candidate) {
        if (candidate.metadata().ecosystem() != Metadata.Ecosystem.FABRIC) return candidate;
        JsonObject json = json(candidate.archive());
        if (!candidate.metadata().id().matches("[a-z][a-z0-9_-]{0,63}")) throw new Failure("FABRIC_SCHEMA", "Invalid Fabric id " + candidate.metadata().id());
        if (!json.has("schemaVersion") || !json.get("schemaVersion").toString().equals("1")) throw new Failure("FABRIC_SCHEMA", "Only Fabric schemaVersion 1 is supported");
        for (String field : List.of("mixins", "accessWidener", "jars", "languageAdapters", "breaks", "conflicts", "recommends", "suggests")) {
            JsonElement value = json.get(field);
            if (value != null && !(value.isJsonArray() && value.getAsJsonArray().isEmpty()) && !(value.isJsonObject() && value.getAsJsonObject().isEmpty()))
                throw new Failure("FABRIC_FEATURE_UNSUPPORTED", candidate.metadata().id() + " requests " + field + "; profile supports plain Java main/server entrypoints only");
        }
        try { Version.parse(candidate.metadata().version()); }
        catch (Exception invalid) { throw new Failure("FABRIC_VERSION", candidate.metadata().id(), invalid); }
        Map<String, String> depends = new TreeMap<>();
        if (json.has("depends")) json.getAsJsonObject("depends").entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                throw new Failure("FABRIC_FEATURE_UNSUPPORTED", "Fabric dependency arrays are not supported: " + entry.getKey());
            String expression = entry.getValue().getAsString();
            try { VersionPredicate.parse(expression); }
            catch (Exception invalid) { throw new Failure("FABRIC_VERSION", "Invalid dependency " + entry.getKey() + ": " + expression, invalid); }
            depends.put(entry.getKey(), expression);
        });
        entries(candidate); // Admission validates all executable entry declarations without loading classes.
        Metadata old = candidate.metadata();
        return new Discovery.Candidate(candidate.archive(), new Metadata(old.id(), old.version(), old.ecosystem(), old.environment(), null, depends, Map.of(), Set.of()));
    }
    public static List<Entry> entries(Discovery.Candidate candidate) {
        JsonObject json = json(candidate.archive()); List<Entry> result = new ArrayList<>();
        if (!json.has("entrypoints")) return List.of();
        JsonObject groups = json.getAsJsonObject("entrypoints");
        for (String group : groups.keySet()) {
            if (!Set.of("main", "server", "client").contains(group)) throw new Failure("FABRIC_FEATURE_UNSUPPORTED", "Unsupported entrypoint group " + group);
            JsonArray values = groups.getAsJsonArray(group);
            for (JsonElement value : values) {
                String name;
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) name = value.getAsString();
                else if (value.isJsonObject() && value.getAsJsonObject().get("adapter") != null
                        && value.getAsJsonObject().get("adapter").getAsString().equals("default")) name = value.getAsJsonObject().get("value").getAsString();
                else throw new Failure("FABRIC_FEATURE_UNSUPPORTED", "Only default Java class entrypoints are supported");
                if (!name.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")) throw new Failure("FABRIC_FEATURE_UNSUPPORTED", "Method / field entrypoints are not supported: " + name);
                if (!group.equals("client")) result.add(new Entry(group, name,
                        group.equals("main") ? "net.fabricmc.api.ModInitializer" : "net.fabricmc.api.DedicatedServerModInitializer",
                        group.equals("main") ? "onInitialize" : "onInitializeServer"));
            }
        }
        // Main must finish before dedicated-server initializers, irrespective of JSON key order.
        result.sort(Comparator.comparing(e -> e.group().equals("main") ? 0 : 1)); return List.copyOf(result);
    }
    public static boolean matches(String expression, String version) {
        try { return VersionPredicate.parse(expression).test(Version.parse(version)); }
        catch (Exception invalid) { throw new Failure("FABRIC_VERSION", expression + " against " + version, invalid); }
    }
}
