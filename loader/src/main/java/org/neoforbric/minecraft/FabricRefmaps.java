package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import net.fabricmc.mappingio.tree.MappingTree;
import org.neoforbric.loader.Failure;

/** Refmap keys retain the annotation's source spelling; only resolved target values change. */
final class FabricRefmaps {
    private static final Pattern SYMBOL = Pattern.compile("net/minecraft/class_\\d+(?:\\$class_\\d+)*|\\b(?:method|field)_\\d+\\b");
    static byte[] remap(byte[] bytes, MappingTree tree) {
        int from = tree.getNamespaceId("intermediary"), to = tree.getNamespaceId("mojang");
        Map<String, String> symbols = new HashMap<>(); Set<String> ambiguous = new HashSet<>();
        for (var type : tree.getClasses()) {
            add(symbols, ambiguous, type.getName(from), type.getName(to));
            for (var method : type.getMethods()) add(symbols, ambiguous, method.getName(from), method.getName(to));
            for (var field : type.getFields()) add(symbols, ambiguous, field.getName(from), field.getName(to));
        }
        JsonObject refmap = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        for (String group : List.of("mappings", "data")) if (refmap.has(group)) values(refmap.get(group), symbols, ambiguous);
        return new Gson().toJson(refmap).getBytes(StandardCharsets.UTF_8);
    }
    private static void add(Map<String, String> symbols, Set<String> ambiguous, String from, String to) {
        if (from == null || to == null) return;
        String old = symbols.putIfAbsent(from, to); if (old != null && !old.equals(to)) ambiguous.add(from);
    }
    private static void values(JsonElement element, Map<String, String> symbols, Set<String> ambiguous) {
        if (!element.isJsonObject()) throw new Failure("FABRIC_REFMAP", "Expected resolved selector object");
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonObject()) { values(entry.getValue(), symbols, ambiguous); continue; }
            String value = entry.getValue().getAsString(); Matcher matcher = SYMBOL.matcher(value); StringBuilder mapped = new StringBuilder();
            while (matcher.find()) {
                String symbol = matcher.group();
                if (ambiguous.contains(symbol)) throw new Failure("FABRIC_REFMAP", "Ambiguous intermediary selector " + symbol);
                matcher.appendReplacement(mapped, Matcher.quoteReplacement(symbols.getOrDefault(symbol, symbol)));
            }
            matcher.appendTail(mapped); entry.setValue(new JsonPrimitive(mapped.toString()));
        }
    }
}
