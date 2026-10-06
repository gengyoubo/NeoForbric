package org.neoforbric.loader;

import java.util.*;

/** Explicitly registered tools only. No native launch service is discovered or started. */
public final class TransformPipeline {
    @FunctionalInterface public interface BytecodeAccess { byte[] original(String name); }
    public record Context(String name, BytecodeAccess bytecode) {}
    public interface Transformer {
        String id();
        default Set<String> after() { return Set.of(); }
        byte[] transform(Context context, byte[] bytes) throws Exception;
    }
    private final Map<String, Transformer> tools = new TreeMap<>();
    private List<Transformer> ordered;
    private final ThreadLocal<String> activeRule = new ThreadLocal<>();

    public synchronized void add(Transformer transformer) {
        if (ordered != null) throw new Failure("PLAN_SEALED", "Cannot register transformer after seal");
        if (transformer.id() == null || transformer.id().isBlank()) throw new Failure("TRANSFORMER_ID", "Missing transformer id");
        if (tools.putIfAbsent(transformer.id(), transformer) != null) throw new Failure("TRANSFORMER_ID", "Duplicate transformer " + transformer.id());
    }

    public synchronized void seal(AuditLog audit) {
        if (ordered != null) throw new Failure("PLAN_SEALED", "Transformation plan already sealed");
        Map<String, Set<String>> graph = new TreeMap<>();
        tools.forEach((id, tool) -> graph.put(id, Set.copyOf(tool.after())));
        ordered = Order.sort(graph, "transformer").stream().map(tools::get).toList();
        audit.record("PREPARE", "transform-order", "plan", Map.of("ids", ordered.stream().map(Transformer::id).toList().toString()));
    }

    public synchronized boolean sealed() { return ordered != null; }
    public synchronized Set<String> registeredIds() { return Set.copyOf(tools.keySet()); }
    public String activeRule() { return activeRule.get(); }

    // Serializes tooling until parallel-safety contracts exist. ClassLoader locks still enforce one definition per class.
    public synchronized byte[] apply(String name, byte[] original, BytecodeAccess source, AuditLog audit) throws Exception {
        return applyUntil(name, original, source, audit, null);
    }

    /** Mixin metadata reads the same preceding stages, without recursively applying Mixin itself. */
    public synchronized byte[] applyBefore(String name, byte[] original, BytecodeAccess source, AuditLog audit, String boundary) throws Exception {
        if (!tools.containsKey(boundary)) throw new Failure("TRANSFORM_BOUNDARY", "Unknown boundary " + boundary);
        return applyUntil(name, original, source, audit, boundary);
    }
    private byte[] applyUntil(String name, byte[] original, BytecodeAccess source, AuditLog audit, String boundary) throws Exception {
        if (ordered == null) throw new Failure("TRANSFORM_NOT_READY", "Transformation plan is not sealed");
        byte[] bytes = original.clone();
        for (Transformer tool : ordered) {
            if (tool.id().equals(boundary)) break;
            String before = Archive.sha256(bytes);
            String previousRule = activeRule.get();
            try {
                activeRule.set(tool.id());
                bytes = Objects.requireNonNull(tool.transform(new Context(name, source), bytes.clone()), "transform output").clone();
                ClassIndex.validateName(name, bytes);
            } catch (Exception | Error failed) {
                Failure.rethrowFatal(failed);
                audit.record("FAILED", "transform-failed", name, Map.of("rule", tool.id(), "inputSha256", before,
                        "cause", failed.getClass().getName(), "severity", "INSTANCE_FATAL"));
                throw new Failure("TRANSFORM_FAILED", "Transformer " + tool.id() + " failed for " + name, failed);
            } finally {
                if (previousRule == null) activeRule.remove(); else activeRule.set(previousRule);
            }
            audit.record("GAME", "transform", name, Map.of("rule", tool.id(), "inputSha256", before, "outputSha256", Archive.sha256(bytes)));
        }
        ClassIndex.validateName(name, bytes);
        return bytes;
    }
}
