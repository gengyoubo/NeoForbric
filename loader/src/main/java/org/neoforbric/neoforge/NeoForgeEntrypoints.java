package org.neoforbric.neoforge;

import java.util.*;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Scan annotations and constructor shapes without defining or initializing candidate classes. */
public final class NeoForgeEntrypoints {
    private static final String MOD = "Lnet/neoforged/fml/common/Mod;";
    private static final Set<String> PARAMETERS = Set.of("net.neoforged.bus.api.IEventBus", "net.neoforged.fml.ModContainer",
            "net.neoforged.fml.javafmlmod.FMLModContainer", "net.neoforged.api.distmarker.Dist");
    private NeoForgeEntrypoints() {}
    public static Map<String, List<String>> scan(Archive archive, String side) {
        Set<String> ids = new HashSet<>(); NeoForgeMetadata.read(archive).mods().forEach(mod -> ids.add(mod.id()));
        Map<String, List<Map.Entry<String, Integer>>> entries = new TreeMap<>(); ids.forEach(id -> entries.put(id, new ArrayList<>()));
        for (String resource : new TreeSet<>(archive.names())) if (resource.endsWith(".class")) {
            ClassNode node = new ClassNode(); new ClassReader(archive.read(resource)).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            List<AnnotationNode> annotations = new ArrayList<>();
            if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
            if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) if (annotation.desc.equals(MOD)) {
                Map<String, Object> values = new HashMap<>();
                for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2) values.put((String)annotation.values.get(i), annotation.values.get(i + 1));
                String id = (String)values.get("value");
                if (!ids.contains(id)) throw new Failure("NEOFORGE_ENTRYPOINT", "@Mod(" + id + ") is not declared in " + archive.path());
                Set<String> sides = new HashSet<>(Set.of("CLIENT", "DEDICATED_SERVER"));
                if (values.containsKey("dist")) {
                    sides.clear();
                    for (Object value : (List<?>)values.get("dist")) sides.add(((String[])value)[1]);
                }
                if (!sides.contains(side.equals("client") ? "CLIENT" : "DEDICATED_SERVER")) continue;
                if ((node.access & Opcodes.ACC_PUBLIC) == 0) throw new Failure("NEOFORGE_ENTRYPOINT", "@Mod class must be public: " + node.name);
                var constructors = node.methods.stream().filter(method -> method.name.equals("<init>") && (method.access & Opcodes.ACC_PUBLIC) != 0).toList();
                if (constructors.size() != 1) throw new Failure("NEOFORGE_ENTRYPOINT", node.name + " requires exactly one public constructor");
                Set<String> parameters = new HashSet<>();
                for (Type type : Type.getArgumentTypes(constructors.getFirst().desc))
                    if (!PARAMETERS.contains(type.getClassName()) || !parameters.add(type.getClassName()))
                        throw new Failure("NEOFORGE_ENTRYPOINT", node.name + " has unsupported or duplicate constructor parameter " + type.getClassName());
                entries.get(id).add(Map.entry(node.name.replace('/', '.'), sides.size()));
            }
        }
        Map<String, List<String>> result = new TreeMap<>();
        entries.forEach((id, classes) -> result.put(id, classes.stream().sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).toList()));
        return Map.copyOf(result);
    }
}
