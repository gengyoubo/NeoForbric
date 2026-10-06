package org.neoforbric.loader;

import java.util.*;

public final class Order {
    private Order() {}
    /** Edges name predecessors. Every predecessor must belong to this graph. */
    public static List<String> sort(Map<String, Set<String>> graph, String kind) {
        Map<String, Set<String>> remaining = new TreeMap<>();
        graph.forEach((id, predecessors) -> {
            Set<String> missing = new TreeSet<>(predecessors);
            missing.removeAll(graph.keySet());
            if (!missing.isEmpty()) throw new Failure("ORDER_MISSING", kind + " " + id + " requires unknown predecessors " + missing);
            remaining.put(id, new TreeSet<>(predecessors));
        });
        List<String> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String next = remaining.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).findFirst().orElse(null);
            if (next == null) throw new Failure("ORDER_CYCLE", kind + " has an ordering cycle: " + remaining);
            result.add(next);
            remaining.remove(next);
            remaining.values().forEach(edges -> edges.remove(next));
        }
        return List.copyOf(result);
    }
}
