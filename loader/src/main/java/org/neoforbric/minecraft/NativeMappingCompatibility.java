package org.neoforbric.minecraft;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.*;

/** Match compiler-renumbered lambdas by enclosing method and exact descriptor in the selected game. */
public final class NativeMappingCompatibility {
    private NativeMappingCompatibility() {}
    public static void reconcile(MemoryMappingTree tree, Path game) throws IOException {
        int mojang = tree.getNamespaceId("mojang");
        try (JarFile jar = new JarFile(game.toFile())) {
            for (var type : tree.getClasses()) {
                var lambdas = type.getMethods().stream().filter(method -> {
                    String name = method.getName(mojang); return name != null && name.startsWith("lambda$");
                }).toList();
                if (lambdas.isEmpty()) continue;
                var entry = jar.getJarEntry(type.getName(mojang) + ".class"); if (entry == null) continue;
                Map<String, List<String>> actual = new HashMap<>();
                try (InputStream input = jar.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            if (name.startsWith("lambda$")) actual.computeIfAbsent(key(name, descriptor), ignored -> new ArrayList<>()).add(name);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                for (var method : lambdas) {
                    String name = method.getName(mojang), signature = key(name, method.getDesc(mojang));
                    List<String> candidates = actual.getOrDefault(signature, List.of());
                    long originals = lambdas.stream().filter(other -> key(other.getName(mojang), other.getDesc(mojang)).equals(signature)).count();
                    if (!candidates.contains(name) && candidates.size() == 1 && originals == 1) method.setDstName(candidates.getFirst(), mojang);
                }
            }
        }
    }
    private static String key(String name, String descriptor) { return name.substring(0, name.lastIndexOf('$')) + descriptor; }
}
