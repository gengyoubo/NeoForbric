package org.neoforbric.forge.runtime;

import java.io.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/** Computes native plugin frames from bytecode without defining a Mixin target. */
final class ForgeBytecodeWriter extends ClassWriter {
    private record Header(String parent, List<String> interfaces, boolean isInterface) {}
    private final Map<String, Header> headers = new HashMap<>();
    ForgeBytecodeWriter(int flags, ClassNode node) {
        super(flags); headers.put(node.name, new Header(node.superName, List.copyOf(node.interfaces), (node.access & Opcodes.ACC_INTERFACE) != 0));
    }
    private Header header(String name) {
        return headers.computeIfAbsent(name, key -> {
            try {
                InputStream stream = ForgeBytecodeWriter.class.getClassLoader().getResourceAsStream(key + ".class");
                if (stream == null) stream = ClassLoader.getPlatformClassLoader().getResourceAsStream(key + ".class");
                if (stream == null) throw new IllegalStateException("FORGE_FRAME: Missing hierarchy bytecode " + key);
                try (var input = stream) {
                    var reader = new ClassReader(input); return new Header(reader.getSuperName(), List.of(reader.getInterfaces()), (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
                }
            } catch (IOException error) { throw new IllegalStateException("FORGE_FRAME: Cannot read " + key, error); }
        });
    }
    private boolean assignable(String target, String source, Set<String> visited) {
        if (target.equals(source) || target.equals("java/lang/Object")) return true;
        if (!visited.add(source)) return false;
        if (source.startsWith("[")) {
            if (target.equals("java/lang/Cloneable") || target.equals("java/io/Serializable")) return true;
            if (!target.startsWith("[")) return false;
            String a = component(target), b = component(source);
            return a != null && b != null && assignable(a, b, visited);
        }
        if (target.startsWith("[")) return false;
        var info = header(source);
        if (info.parent != null && assignable(target, info.parent, visited)) return true;
        return info.interfaces.stream().anyMatch(face -> assignable(target, face, visited));
    }
    private static String component(String array) {
        String type = array.substring(1);
        return type.startsWith("[") ? type : type.startsWith("L") ? type.substring(1, type.length() - 1) : null;
    }
    @Override protected String getCommonSuperClass(String a, String b) {
        if (assignable(a, b, new HashSet<>())) return a;
        if (assignable(b, a, new HashSet<>())) return b;
        if (a.startsWith("[") && b.startsWith("[")) {
            String left = component(a), right = component(b);
            if (left != null && right != null) {
                String common = getCommonSuperClass(left, right); return "[" + (common.startsWith("[") ? common : "L" + common + ";");
            }
            return "java/lang/Object";
        }
        if (a.startsWith("[") || b.startsWith("[") || header(a).isInterface || header(b).isInterface) return "java/lang/Object";
        String parent = header(a).parent;
        while (parent != null) {
            if (assignable(parent, b, new HashSet<>())) return parent;
            parent = header(parent).parent;
        }
        return "java/lang/Object";
    }
}
