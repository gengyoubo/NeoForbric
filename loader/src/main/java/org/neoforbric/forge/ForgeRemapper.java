package org.neoforbric.forge;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** Forge's globally unique SRG member names map through the pinned MCP CSV input. */
public final class ForgeRemapper {
    private ForgeRemapper() {}
    public static Map<String, String> names(Path root) throws Exception {
        Map<String, String> names = new HashMap<>();
        Path mapping = root.resolve("maven/de/oceanlabs/mcp/mcp_config/1.21.1-20240808.132146/mcp_config-1.21.1-20240808.132146-srg2off.jar");
        try (var jar = new JarFile(mapping.toFile())) {
            for (String resource : List.of("fields.csv", "methods.csv")) try (var input = jar.getInputStream(jar.getJarEntry(resource))) {
                for (String row : new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).toList()) { String[] values = row.split(",", -1); if (values.length >= 2) names.put(values[0], values[1]); }
            }
        }
        return Map.copyOf(names);
    }
    public static String selectors(String value, Map<String, String> names) {
        var matcher = Pattern.compile("(?<![A-Za-z0-9_])(?:m|f)_[0-9]+_(?![A-Za-z0-9_])").matcher(value); StringBuffer result = new StringBuffer();
        while (matcher.find()) { String mapped = names.get(matcher.group()); if (mapped == null) throw new Failure("FORGE_MAPPING", "Unmapped SRG selector " + matcher.group()); matcher.appendReplacement(result, Matcher.quoteReplacement(mapped)); }
        matcher.appendTail(result); return result.toString();
    }
    public static Archive remap(Archive input, Path directory, Map<String, String> names, AuditLog audit) throws Exception {
        input.requireSupportedLayout(); Files.createDirectories(directory); Path target = directory.resolve(input.hash() + "-mojang.jar");
        Path temporary = Files.createTempFile(directory, "forge-remap-", ".jar"); Set<String> nested = new HashSet<>();
        var remapper = new Remapper(Opcodes.ASM9) {
            private String member(String name) { if (!name.matches("[mf]_[0-9]+_")) return name; String mapped = names.get(name); if (mapped == null) throw new Failure("FORGE_MAPPING", "Unmapped SRG member " + name); return mapped; }
            public String mapFieldName(String owner, String name, String descriptor) { return member(name); }
            public String mapMethodName(String owner, String name, String descriptor) { return member(name); }
            public Object mapValue(Object value) { return value instanceof String text ? selectors(text, names) : super.mapValue(value); }
        };
        try {
            try (var output = new JarOutputStream(Files.newOutputStream(temporary))) {
                for (String resource : new TreeSet<>(input.names())) {
                    byte[] bytes = input.read(resource);
                    if (resource.endsWith(".class")) { var writer = new ClassWriter(0); new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0); bytes = writer.toByteArray(); }
                    else if (resource.endsWith(".json") && resource.toLowerCase(Locale.ROOT).contains("refmap")) bytes = selectors(new String(bytes, StandardCharsets.UTF_8), names).getBytes(StandardCharsets.UTF_8);
                    if (resource.endsWith(".jar")) nested.add(resource);
                    JarEntry entry = new JarEntry(resource); entry.setTime(0); output.putNextEntry(entry); output.write(bytes); output.closeEntry();
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        Archive mapped = Archive.read(target).permitDeclaredNested(nested);
        audit.record("PREPARE", "mod-remap", input.path().toString(), Map.of("ecosystem", "FORGE", "sourceSha256", input.hash(), "outputSha256", mapped.hash(), "from", "srg", "to", "mojang")); return mapped;
    }
}
