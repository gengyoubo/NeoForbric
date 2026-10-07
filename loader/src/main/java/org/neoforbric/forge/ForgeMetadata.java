package org.neoforbric.forge;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Manifest;
import java.util.regex.*;
import org.neoforbric.loader.*;
import org.tomlj.*;

/** Forge's mods.toml contract, translated before any candidate can be defined. */
public record ForgeMetadata(String modLoader, String loaderVersion, List<Metadata> mods) {
    public ForgeMetadata { mods = List.copyOf(mods); }
    public static ForgeMetadata read(Archive archive) {
        try {
            var toml = Toml.parse(new String(Objects.requireNonNull(archive.read("META-INF/mods.toml")), StandardCharsets.UTF_8));
            if (toml.hasErrors()) throw new IllegalArgumentException(toml.errors().toString());
            String language = required(toml, "modLoader"), range = required(toml, "loaderVersion");
            MavenVersions.matches(range, "0"); required(toml, "license");
            var entries = toml.getArray("mods");
            if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("Missing [[mods]]");
            var dependencies = toml.getTable("dependencies");
            List<Metadata> mods = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getTable(i); String id = id(required(entry, "modId"));
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate modId " + id);
                List<Dependency> constraints = new ArrayList<>();
                var deps = dependencies == null ? null : dependencies.getArray(List.of(id));
                if (deps != null) for (int j = 0; j < deps.size(); j++) {
                    var dep = deps.getTable(j); String versions = value(dep, "versionRange", ""); MavenVersions.matches(versions, "0");
                    Boolean mandatory = dep.getBoolean("mandatory");
                    if (mandatory == null) throw new IllegalArgumentException("Forge dependency requires mandatory");
                    constraints.add(new Dependency(id(required(dep, "modId")), versions, Dependency.Scheme.MAVEN,
                            mandatory ? Dependency.Kind.REQUIRED : Dependency.Kind.OPTIONAL,
                            Dependency.Ordering.valueOf(value(dep, "ordering", "NONE")), Dependency.Side.valueOf(value(dep, "side", "BOTH")), ""));
                }
                mods.add(new Metadata(id, version(value(entry, "version", "1"), toml, archive), Metadata.Ecosystem.FORGE,
                        "*", null, Map.of(), Map.of(), Set.of(), entry.getString("displayName"), entry.getString("description"),
                        entry.getString("logoFile") == null ? toml.getString("logoFile") : entry.getString("logoFile"), constraints));
            }
            return new ForgeMetadata(language, range, mods);
        } catch (Failure failure) { throw failure; }
        catch (Exception error) { throw new Failure("METADATA_INVALID", archive.path() + ": " + error.getMessage(), error); }
    }
    private static String id(String value) { if (!value.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("Invalid Forge modId " + value); return value; }
    private static String value(TomlTable table, String key, String fallback) { return Objects.requireNonNullElse(table.getString(key), fallback); }
    private static String required(TomlTable table, String key) { String value = table.getString(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key); return value; }
    private static String version(String version, TomlTable toml, Archive archive) throws Exception {
        Map<String, String> properties = new HashMap<>(); var declared = toml.getTable("properties");
        if (declared != null) declared.keySet().forEach(key -> properties.put(key, declared.get(List.of(key)).toString()));
        byte[] manifest = archive.read("META-INF/MANIFEST.MF");
        properties.put("jarVersion", manifest == null ? "0.0NONE" : Objects.requireNonNullElse(new Manifest(new java.io.ByteArrayInputStream(manifest)).getMainAttributes().getValue("Implementation-Version"), "0.0NONE"));
        var matcher = Pattern.compile("\\$\\{file\\.([^}]+)}").matcher(version); StringBuffer result = new StringBuffer();
        while (matcher.find()) { String replacement = properties.get(matcher.group(1)); if (replacement == null) throw new IllegalArgumentException("Unknown substitution " + matcher.group()); matcher.appendReplacement(result, Matcher.quoteReplacement(replacement)); }
        matcher.appendTail(result); if (result.isEmpty() || result.indexOf("${") >= 0) throw new IllegalArgumentException("Unresolved mod version " + version); return result.toString();
    }
}
