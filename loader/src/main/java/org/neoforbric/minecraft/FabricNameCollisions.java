package org.neoforbric.minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.neoforbric.loader.Failure;

/** Distinct source methods must not become accidental overrides in the destination namespace. */
final class FabricNameCollisions {
    private record Header(List<String> parents, List<MethodNode> methods, boolean mixin) {}
    static IMappingProvider mappings(Map<String, byte[]> inputs, Path game, MappingTree mappings) throws IOException {
        Map<String, Header> hierarchy = new HashMap<>();
        try (JarFile jar = new JarFile(game.toFile())) {
            for (var entry : Collections.list(jar.entries())) if (entry.getName().endsWith(".class"))
                try (var stream = jar.getInputStream(entry)) { header(stream.readAllBytes(), hierarchy); }
        }
        inputs.values().forEach(bytes -> header(bytes, hierarchy));
        int from = mappings.getNamespaceId("intermediary"), to = mappings.getNamespaceId("mojang");
        Map<IMappingProvider.Member, String> renamed = new LinkedHashMap<>();
        for (String resource : new TreeSet<>(inputs.keySet())) {
            String owner = resource.substring(0, resource.length() - 6);
            Header type = hierarchy.get(owner);
            if (type.mixin()) continue;
            Set<String> ancestors = ancestors(type.parents(), hierarchy);
            for (MethodNode method : type.methods()) {
                if (method.name.startsWith("<")) continue;
                // Keep genuine source overrides, including members intentionally
                // omitted from intermediary mappings because their name is stable.
                boolean overrides = ancestors.stream().map(hierarchy::get).filter(Objects::nonNull)
                        .flatMap(parent -> parent.methods().stream()).anyMatch(inherited ->
                                inherited.name.equals(method.name) && inherited.desc.equals(method.desc)
                                        && (inherited.access & Opcodes.ACC_PRIVATE) == 0);
                if (overrides) continue;
                boolean collision = ancestors.stream().map(parent -> mappings.getClass(parent, from)).filter(Objects::nonNull)
                        .flatMap(parent -> parent.getMethods().stream()).anyMatch(inherited ->
                                method.name.equals(inherited.getName(to)) && !method.name.equals(
                                        inherited.getName(from) == null ? inherited.getSrcName() : inherited.getName(from))
                                        && method.desc.equals(inherited.getDesc(from)));
                if (!collision) continue;
                String name = "neoforbric$distinct$" + method.name;
                if (type.methods().stream().anyMatch(other -> other.name.equals(name) && other.desc.equals(method.desc)))
                    throw new Failure("REMAP_NAME_COLLISION", owner + "." + name + method.desc);
                renamed.put(new IMappingProvider.Member(owner, method.name, method.desc), name);
            }
        }
        if (!renamed.isEmpty()) System.out.println("Separated Fabric method name collisions: " + renamed.size());
        return acceptor -> renamed.forEach(acceptor::acceptMethod);
    }
    private static void header(byte[] bytes, Map<String, Header> hierarchy) {
        ClassNode type = new ClassNode(); new ClassReader(bytes).accept(type, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> parents = new ArrayList<>(type.interfaces); if (type.superName != null) parents.add(type.superName);
        hierarchy.put(type.name, new Header(parents, type.methods, mixin(type.visibleAnnotations) || mixin(type.invisibleAnnotations)));
    }
    private static boolean mixin(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"));
    }
    private static Set<String> ancestors(List<String> parents, Map<String, Header> hierarchy) {
        Set<String> result = new HashSet<>(); Deque<String> pending = new ArrayDeque<>(parents);
        while (!pending.isEmpty()) {
            String parent = pending.removeFirst();
            if (result.add(parent) && hierarchy.containsKey(parent)) pending.addAll(hierarchy.get(parent).parents());
        }
        return result;
    }
}
