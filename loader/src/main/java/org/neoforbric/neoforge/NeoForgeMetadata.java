package org.neoforbric.neoforge;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.regex.*;
import org.neoforbric.loader.*;
import org.tomlj.*;

/** Immutable file metadata; dependencies are translated before any game classes are defined. */
public record NeoForgeMetadata(String modLoader, String loaderVersion, String license, List<Metadata> mods,
                               List<String> mixins, List<String> accessTransformers, Set<String> enumExtensions,
                               Map<String, Object> fields) {
    public NeoForgeMetadata {
        mods = List.copyOf(mods); mixins = List.copyOf(mixins); accessTransformers = List.copyOf(accessTransformers);
        enumExtensions = Set.copyOf(enumExtensions); fields = Map.copyOf(fields);
    }
    public static NeoForgeMetadata read(Archive archive) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(archive.read("META-INF/neoforge.mods.toml"))).toString();
            TomlParseResult toml = Toml.parse(text);
            if (toml.hasErrors()) throw new IllegalArgumentException(toml.errors().toString());
            String language = required(toml, "modLoader");
            String loaderVersion = toml.getString("loaderVersion");
            if (loaderVersion == null) throw new IllegalArgumentException("Missing loaderVersion");
            MavenVersions.matches(loaderVersion, "0");
            String license = required(toml, "license");
            TomlArray entries = toml.getArray("mods");
            if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("Missing [[mods]]");
            Set<String> ids = new HashSet<>(), enums = new HashSet<>();
            if (toml.getString("enumExtensions") != null) enums.add(path(toml.getString("enumExtensions")));
            for (int i = 0; i < entries.size(); i++) {
                String id = id(required(entries.getTable(i), "modId"));
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate modId " + id);
            }
            Object dependencySection = toml.get("dependencies");
            TomlTable dependencies = dependencySection instanceof TomlTable table ? table : null;
            TomlArray sharedDependencies = dependencySection instanceof TomlArray array ? array : null;
            if (dependencySection != null && dependencies == null && sharedDependencies == null)
                throw new IllegalArgumentException("dependencies must be a table or table array");
            if (sharedDependencies != null && ids.size() != 1)
                throw new IllegalArgumentException("Unscoped [[dependencies]] requires exactly one declared mod");
            List<Metadata> mods = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                TomlTable mod = entries.getTable(i); String id = mod.getString("modId");
                List<Dependency> constraints = new ArrayList<>();
                TomlArray deps = sharedDependencies != null ? sharedDependencies : dependencies == null ? null : dependencies.getArray(List.of(id));
                if (deps != null) for (int j = 0; j < deps.size(); j++) {
                    TomlTable dep = deps.getTable(j);
                    String range = value(dep, "versionRange", ""); MavenVersions.matches(range, "0");
                    constraints.add(new Dependency(id(required(dep, "modId")), range, Dependency.Scheme.MAVEN,
                            Dependency.Kind.valueOf(value(dep, "type", "required").toUpperCase(Locale.ROOT)),
                            Dependency.Ordering.valueOf(value(dep, "ordering", "NONE")),
                            Dependency.Side.valueOf(value(dep, "side", "BOTH")), value(dep, "reason", "")));
                }
                if (mod.getString("enumExtensions") != null) enums.add(path(mod.getString("enumExtensions")));
                mods.add(new Metadata(id, version(value(mod, "version", "1"), toml, archive), Metadata.Ecosystem.NEOFORGE,
                        "*", null, Map.of(), Map.of(), Set.of(), mod.getString("displayName"), mod.getString("description"),
                        mod.getString("logoFile") == null ? toml.getString("logoFile") : mod.getString("logoFile"), constraints));
            }
            List<String> ats = paths(toml, "accessTransformers", "file");
            if (!toml.contains("accessTransformers") && archive.read("META-INF/accesstransformer.cfg") != null) ats = List.of("META-INF/accesstransformer.cfg");
            return new NeoForgeMetadata(language, loaderVersion, license, mods, paths(toml, "mixins", "config"), ats, enums, freeze(toml));
        } catch (Failure known) { throw known; }
        catch (Exception invalid) { throw new Failure("METADATA_INVALID", archive.path() + ": " + invalid.getMessage(), invalid); }
    }
    private static String id(String value) {
        if (!value.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("Invalid NeoForge modId " + value);
        return value;
    }
    private static String value(TomlTable table, String key, String fallback) { return Objects.requireNonNullElse(table.getString(key), fallback); }
    private static String required(TomlTable table, String key) {
        String value = table.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static String path(String value) {
        if (value.isBlank() || value.startsWith("/") || value.contains("\\") || value.contains(":")
                || Arrays.stream(value.split("/", -1)).anyMatch(part -> Set.of("", ".", "..").contains(part)))
            throw new IllegalArgumentException("Invalid resource path " + value);
        return value;
    }
    private static List<String> paths(TomlTable table, String key, String field) {
        TomlArray array = table.getArray(key); List<String> result = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.size(); i++) result.add(path(required(array.getTable(i), field)));
        return List.copyOf(new LinkedHashSet<>(result));
    }
    private static String version(String value, TomlTable table, Archive archive) throws Exception {
        Map<String, String> properties = new HashMap<>();
        TomlTable declared = table.getTable("properties");
        if (declared != null) declared.keySet().forEach(key -> properties.put(key, declared.get(List.of(key)).toString()));
        byte[] bytes = archive.read("META-INF/MANIFEST.MF");
        properties.put("jarVersion", bytes == null ? "0.0NONE" : Objects.requireNonNullElse(
                new Manifest(new java.io.ByteArrayInputStream(bytes)).getMainAttributes().getValue("Implementation-Version"), "0.0NONE"));
        Matcher matcher = Pattern.compile("\\$\\{file\\.([^}]+)}").matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = properties.get(matcher.group(1));
            if (replacement == null) throw new IllegalArgumentException("Unknown file substitution " + matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        if (result.isEmpty() || result.indexOf("${") >= 0) throw new IllegalArgumentException("Unresolved mod version " + value);
        return result.toString();
    }
    private static Map<String, Object> freeze(TomlTable table) {
        Map<String, Object> result = new LinkedHashMap<>();
        table.keySet().forEach(key -> result.put(key, freezeValue(table.get(List.of(key)))));
        return Collections.unmodifiableMap(result);
    }
    private static Object freezeValue(Object value) {
        if (value instanceof TomlTable table) return freeze(table);
        if (value instanceof TomlArray array) { List<Object> result = new ArrayList<>(); for (int i = 0; i < array.size(); i++) result.add(freezeValue(array.get(i))); return List.copyOf(result); }
        return value;
    }
}
