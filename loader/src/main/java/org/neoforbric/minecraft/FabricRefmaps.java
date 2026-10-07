package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import net.fabricmc.mappingio.tree.MappingTree;
import org.neoforbric.loader.Failure;
import org.neoforbric.loader.Archive;
import org.neoforbric.loader.FabricJson;

/** Refmap keys retain the annotation's source spelling; only resolved target values change. */
final class FabricRefmaps {
    private static final Pattern SYMBOL = Pattern.compile("(?:net/minecraft|com/mojang)/[\\w/$]+|\\b(?:method|field|comp)_\\d+\\b");
    static Set<String> paths(Archive source) {
        Set<String> paths = new TreeSet<>();
        for (String name : source.names()) if (name.endsWith("refmap.json")) paths.add(name);
        JsonObject metadata = FabricJson.metadata(source);
        if (metadata.has("mixins")) for (JsonElement entry : metadata.getAsJsonArray("mixins")) {
            String config = entry.isJsonObject() ? entry.getAsJsonObject().get("config").getAsString() : entry.getAsString();
            byte[] bytes = source.read(config);
            if (bytes == null) continue; // Mixin reports missing configs using its required/optional policy.
            JsonObject mixin = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (mixin.has("refmap")) {
                String refmap = mixin.get("refmap").getAsString();
                if (source.names().contains(refmap)) paths.add(refmap);
            }
        }
        return paths;
    }
    static byte[] remap(byte[] bytes, MappingTree tree) {
        SelectorMapper mapper = new SelectorMapper(tree);
        JsonObject refmap = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        for (String group : List.of("mappings", "data")) if (refmap.has(group)) values(refmap.get(group), mapper);
        return new Gson().toJson(refmap).getBytes(StandardCharsets.UTF_8);
    }
    static final class SelectorMapper {
        private final Map<String, String> symbols = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();
        SelectorMapper(MappingTree tree) {
            int from = tree.getNamespaceId("intermediary"), to = tree.getNamespaceId("mojang");
            for (var type : tree.getClasses()) {
                add(symbols, ambiguous, type.getName(from), type.getName(to));
                for (var method : type.getMethods()) add(symbols, ambiguous, method.getName(from), method.getName(to));
                for (var field : type.getFields()) add(symbols, ambiguous, field.getName(from), field.getName(to));
            }
        }
        String map(String value) {
            Matcher matcher = SYMBOL.matcher(value); StringBuilder mapped = new StringBuilder();
            while (matcher.find()) {
                String symbol = matcher.group();
                if (ambiguous.contains(symbol)) throw new Failure("FABRIC_REFMAP", "Ambiguous intermediary selector " + symbol);
                matcher.appendReplacement(mapped, Matcher.quoteReplacement(symbols.getOrDefault(symbol, symbol)));
            }
            matcher.appendTail(mapped); return mapped.toString();
        }
    }
    private static void add(Map<String, String> symbols, Set<String> ambiguous, String from, String to) {
        if (from == null || to == null) return;
        String old = symbols.putIfAbsent(from, to); if (old != null && !old.equals(to)) ambiguous.add(from);
    }
    private static void values(JsonElement element, SelectorMapper mapper) {
        if (!element.isJsonObject()) throw new Failure("FABRIC_REFMAP", "Expected resolved selector object");
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonObject()) { values(entry.getValue(), mapper); continue; }
            entry.setValue(new JsonPrimitive(mapper.map(entry.getValue().getAsString())));
        }
    }
}
