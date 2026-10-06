package org.neoforbric.loader;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.tomlj.*;

public record Metadata(String id, String version, Ecosystem ecosystem, String environment, String entrypoint,
                       Map<String, String> depends, Map<String, String> optionalDepends, Set<String> after,
                       String name, String description, String iconPath) {
    private static final List<String> DESCRIPTOR_PRIORITY = List.of(
            "neoforbric.mod.json", "fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml");
    public Metadata(String id, String version, Ecosystem ecosystem, String environment, String entrypoint,
                    Map<String, String> depends, Map<String, String> optionalDepends, Set<String> after) {
        this(id, version, ecosystem, environment, entrypoint, depends, optionalDepends, after, id, "", null);
    }
    public enum Ecosystem { PROTOTYPE, FABRIC, FORGE, NEOFORGE }
    public Metadata {
        depends = Map.copyOf(depends);
        optionalDepends = Map.copyOf(optionalDepends);
        after = Set.copyOf(after);
        name = name == null || name.isBlank() ? id : name;
        description = description == null ? "" : description;
    }
    public boolean available(String side) { return environment.equals("*") || environment.equals(side); }

    /** A universal JAR uses one ecosystem; invalid selected metadata never falls back to another. */
    public static String descriptor(Archive archive) {
        return DESCRIPTOR_PRIORITY.stream().filter(archive.names()::contains).findFirst()
                .orElseThrow(() -> new Failure("METADATA_DESCRIPTOR", archive.path()
                        + " has no supported descriptor; expected one of " + DESCRIPTOR_PRIORITY));
    }

    public static List<Metadata> read(Archive archive) {
        String name = descriptor(archive);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(archive.read(name))).toString();
            if (name.endsWith(".toml")) {
                TomlParseResult toml = Toml.parse(text);
                if (toml.hasErrors()) throw new Failure("METADATA_INVALID", archive.path() + ": " + toml.errors());
                TomlArray mods = toml.getArray("mods");
                if (mods == null || mods.isEmpty()) throw new Failure("METADATA_INVALID", archive.path() + " has no [[mods]]");
                List<Metadata> result = new ArrayList<>();
                for (int i = 0; i < mods.size(); i++) {
                    TomlTable mod = mods.getTable(i);
                    String id = Objects.requireNonNull(mod.getString("modId"), "modId");
                    String version = Objects.requireNonNull(mod.getString("version"), "version");
                    result.add(new Metadata(id, version, name.contains("neoforge") ? Ecosystem.NEOFORGE : Ecosystem.FORGE,
                            "*", null, Map.of(), Map.of(), Set.of(), mod.getString("displayName"), mod.getString("description"),
                            mod.getString("logoFile") == null ? toml.getString("logoFile") : mod.getString("logoFile")));
                }
                return List.copyOf(result);
            }
            JsonObject json = StrictJson.object(text);
            String id = string(json, "id"), version = string(json, "version");
            String environment = json.has("environment") ? string(json, "environment") : "*";
            if (!Set.of("*", "client", "server").contains(environment)) throw new Failure("METADATA_INVALID", "Invalid environment " + environment);
            if (name.equals("fabric.mod.json"))
                return List.of(new Metadata(id, version, Ecosystem.FABRIC, environment, null, Map.of(), Map.of(), Set.of(),
                        optionalString(json, "name"), optionalString(json, "description"), icon(json)));
            Set<String> allowed = Set.of("schemaVersion", "id", "version", "environment", "entrypoint", "depends", "optionalDepends", "after", "name", "description", "icon");
            for (String key : json.keySet()) if (!allowed.contains(key)) throw new Failure("METADATA_UNSUPPORTED", "Unknown prototype field " + key);
            if (!json.has("schemaVersion") || !json.get("schemaVersion").toString().equals("1"))
                throw new Failure("METADATA_INVALID", "Prototype schemaVersion must be 1");
            if (!id.matches("[a-z][a-z0-9_-]{0,63}")) throw new Failure("METADATA_INVALID", "Invalid prototype id " + id);
            Version.parse(version);
            String entrypoint = string(json, "entrypoint");
            if (!entrypoint.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")) throw new Failure("METADATA_INVALID", "Invalid entrypoint " + entrypoint);
            Set<String> after = new TreeSet<>();
            if (json.has("after")) for (JsonElement item : json.getAsJsonArray("after")) after.add(stringValue(item, "after"));
            return List.of(new Metadata(id, version, Ecosystem.PROTOTYPE, environment, entrypoint,
                    dependencies(json, "depends"), dependencies(json, "optionalDepends"), after, optionalString(json, "name"), optionalString(json, "description"), icon(json)));
        } catch (Failure known) { throw known; }
        catch (IOException | RuntimeException invalid) { throw new Failure("METADATA_INVALID", archive.path() + ": " + invalid.getMessage(), invalid); }
    }

    private static Map<String, String> dependencies(JsonObject json, String field) {
        Map<String, String> result = new TreeMap<>();
        if (json.has(field)) json.getAsJsonObject(field).entrySet().forEach(e -> {
            String expression = stringValue(e.getValue(), field);
            Version.matches(expression, "0.0.0"); // Validate even absent optional dependencies.
            result.put(e.getKey(), expression);
        });
        return result;
    }
    private static String string(JsonObject json, String field) { return stringValue(json.get(field), field); }
    private static String optionalString(JsonObject json, String field) {
        if (!json.has(field)) return null;
        JsonElement value = json.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new Failure("METADATA_INVALID", field + " must be a string");
        return value.getAsString();
    }
    private static String icon(JsonObject json) {
        if (!json.has("icon")) return null;
        JsonElement value = json.get("icon");
        if (value.isJsonPrimitive()) return optionalString(json, "icon");
        if (!value.isJsonObject()) throw new Failure("METADATA_INVALID", "icon must be a path or size map");
        return value.getAsJsonObject().entrySet().stream().filter(e -> e.getKey().matches("[0-9]+") && e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString())
                .sorted(Comparator.comparingLong(e -> { try { return Math.abs(Long.parseLong(e.getKey()) - 128); } catch (NumberFormatException invalid) { return Long.MAX_VALUE; } }))
                .map(e -> e.getValue().getAsString()).findFirst().orElse(null);
    }
    private static String stringValue(JsonElement value, String field) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new Failure("METADATA_INVALID", field + " must be a nonempty string");
        return value.getAsString();
    }
}
