package org.neoforbric.fabric;

import java.util.*;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.mappingio.tree.MappingTree;
import org.neoforbric.loader.Failure;

/** Runtime lookups use the same verified mapping tree as the bytecode and access rules. */
final class NeoMappingResolver implements MappingResolver {
    private final MappingTree tree;
    private final int target;
    NeoMappingResolver(MappingTree tree) { this.tree = tree; this.target = namespace("mojang"); }
    private int namespace(String name) {
        int id = tree.getNamespaceId(name);
        if (id == MappingTree.NULL_NAMESPACE_ID) throw new IllegalArgumentException("Unknown namespace " + name);
        return id;
    }
    private String internal(String dotted) {
        if (dotted.contains("/")) throw new IllegalArgumentException("MappingResolver expects dotted class names: " + dotted);
        return dotted.replace('.', '/');
    }
    @Override public Collection<String> getNamespaces() {
        List<String> result = new ArrayList<>(); result.add(tree.getSrcNamespace()); result.addAll(tree.getDstNamespaces()); return List.copyOf(result);
    }
    @Override public String getCurrentRuntimeNamespace() { return "mojang"; }
    private String mapClass(int from, int to, String name) {
        var type = tree.getClass(internal(name), from); String mapped = type == null ? null : type.getName(to);
        return mapped == null ? name : mapped.replace('/', '.');
    }
    @Override public String mapClassName(String from, String name) { return mapClass(namespace(from), target, name); }
    @Override public String unmapClassName(String to, String name) { return mapClass(target, namespace(to), name); }
    @Override public String mapFieldName(String from, String owner, String name, String descriptor) {
        var field = tree.getField(internal(owner), name, descriptor, namespace(from));
        return field == null || field.getName(target) == null ? name : field.getName(target);
    }
    @Override public String mapMethodName(String from, String owner, String name, String descriptor) {
        var method = tree.getMethod(internal(owner), name, descriptor, namespace(from));
        return method == null || method.getName(target) == null ? name : method.getName(target);
    }
}
