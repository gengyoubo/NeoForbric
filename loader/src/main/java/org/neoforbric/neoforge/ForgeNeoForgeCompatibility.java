package org.neoforbric.neoforge;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Pattern;
import org.neoforbric.forge.*;
import org.neoforbric.loader.*;
import org.neoforbric.minecraft.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.tomlj.*;

/** Forge inputs use the selected NeoForge game and services, never a second native launcher. */
public final class ForgeNeoForgeCompatibility {
    public static final String VERSION = "52.1.0";
    private static final String HOOKS = "org/neoforbric/neoforge/runtime/ForgeCompatibilityHooks";
    private static final String CONTEXT = "net/neoforged/fml/ModLoadingContext";
    private final Map<String, byte[]> classes = new HashMap<>();
    private final Map<String, ClassNode> symbols = new HashMap<>();
    private final Map<String, String> names;
    private final AuditLog audit;

    public ForgeNeoForgeCompatibility(List<Archive> target, Map<String, String> names, AuditLog audit) {
        this.names = Map.copyOf(names); this.audit = audit;
        for (Archive archive : target) for (String resource : archive.names()) if (resource.endsWith(".class"))
            classes.putIfAbsent(resource.substring(0, resource.length() - 6), archive.read(resource));
    }
    public static void admit(Discovery.Candidate candidate) {
        if (candidate.metadata().ecosystem() != Metadata.Ecosystem.FORGE) return;
        ForgeAdmission.admit(candidate);
        if (candidate.archive().read("META-INF/coremods.json") != null)
            throw unsupported("Forge coremod scripts require adaptation to the selected NeoForge patches: " + candidate.metadata().id());
    }
    public static boolean nativeMod(Discovery.Candidate candidate) {
        return Set.of(Metadata.Ecosystem.NEOFORGE, Metadata.Ecosystem.FORGE).contains(candidate.metadata().ecosystem());
    }
    public static Map<String, String> names(List<Discovery.Candidate> mods, Path vanilla, Path forgeRoot) throws Exception {
        Pattern srg = Pattern.compile("[mf]_[0-9]+_");
        boolean needed = mods.stream().filter(mod -> mod.metadata().ecosystem() == Metadata.Ecosystem.FORGE).anyMatch(mod ->
                mod.archive().names().stream().filter(resource -> resource.endsWith(".class") || resource.endsWith(".json") || resource.endsWith(".cfg"))
                        .anyMatch(resource -> srg.matcher(new String(mod.archive().read(resource), StandardCharsets.ISO_8859_1)).find()));
        if (!needed) return Map.of();
        Path mapping = forgeRoot.resolve("maven/de/oceanlabs/mcp/mcp_config/1.21.1-20240808.132146/mcp_config-1.21.1-20240808.132146-srg2off.jar");
        if (!Files.isRegularFile(mapping)) ForgePreparation.prepare(vanilla, forgeRoot);
        if (!Archive.sha256(Files.readAllBytes(mapping)).equals(ForgePreparation.lock().get("srg2offSha256").getAsString()))
            throw new Failure("INPUT_CHECKSUM", "Forge compatibility naming input differs: " + mapping);
        return ForgeRemapper.names(forgeRoot);
    }
    public static String apiClass(String name) {
        if (name.equals("net/minecraftforge/common/MinecraftForge")) return "net/neoforged/neoforge/common/NeoForge";
        if (name.startsWith("net/minecraftforge/common/ForgeConfigSpec")) return name.replace("net/minecraftforge/common/ForgeConfigSpec", "net/neoforged/neoforge/common/ModConfigSpec");
        if (name.startsWith("net/minecraftforge/fml/common/Mod$EventBusSubscriber")) return name.replace("net/minecraftforge/fml/common/Mod$EventBusSubscriber", "net/neoforged/fml/common/EventBusSubscriber");
        if (name.equals("net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory")) return HOOKS + "$ConfigScreenFactory";
        for (var prefix : List.of(Map.entry("net/minecraftforge/eventbus/", "net/neoforged/bus/"),
                Map.entry("net/minecraftforge/api/", "net/neoforged/api/"), Map.entry("net/minecraftforge/fml/", "net/neoforged/fml/"),
                Map.entry("net/minecraftforge/forgespi/", "net/neoforged/neoforgespi/"), Map.entry("net/minecraftforge/", "net/neoforged/neoforge/")))
            if (name.startsWith(prefix.getKey())) return prefix.getValue() + name.substring(prefix.getKey().length());
        return name;
    }
    private static Failure unsupported(String message) { return new Failure("FORGE_FEATURE_UNSUPPORTED", "Forge → NeoForge compatibility: " + message); }
    private String member(String value) { return ForgeRemapper.selectors(value, names); }
    private ClassNode symbol(String owner) {
        if (symbols.containsKey(owner)) return symbols.get(owner);
        byte[] bytes = classes.get(owner); if (bytes == null) return null;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        symbols.put(owner, node); return node;
    }
    private boolean method(String owner, String name, String desc, Set<String> visited) {
        if (!visited.add(owner)) return false; ClassNode type = symbol(owner); if (type == null) return false;
        if (type.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc))) return true;
        if (name.equals("<init>")) return false;
        return type.superName != null && method(type.superName, name, desc, visited)
                || type.interfaces.stream().anyMatch(parent -> method(parent, name, desc, visited));
    }
    private boolean field(String owner, String name, String desc, Set<String> visited) {
        if (!visited.add(owner)) return false; ClassNode type = symbol(owner); if (type == null) return false;
        return type.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(desc))
                || type.superName != null && field(type.superName, name, desc, visited)
                || type.interfaces.stream().anyMatch(parent -> field(parent, name, desc, visited));
    }
    private Remapper remapper() {
        return new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) {
                String mapped = apiClass(name);
                if (!mapped.equals(name) && !classes.containsKey(mapped)) throw unsupported("missing API class " + name.replace('/', '.') + " → " + mapped.replace('/', '.'));
                return mapped;
            }
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                if (owner.startsWith("net/minecraftforge/client/event/ScreenEvent$MouseScrolled") && descriptor.equals("()D")) {
                    if (name.equals("getDeltaX")) return "getScrollDeltaX";
                    if (name.equals("getDeltaY")) return "getScrollDeltaY";
                }
                return member(name);
            }
            @Override public String mapFieldName(String owner, String name, String descriptor) { return member(name); }
            @Override public Object mapValue(Object value) {
                if (value instanceof String text) {
                    String mapped = member(text);
                    if (mapped.startsWith("net.minecraftforge.")) return apiClass(mapped.replace('.', '/')).replace('/', '.');
                    if (mapped.startsWith("net/minecraftforge/")) return apiClass(mapped);
                    return mapped;
                }
                Object mapped = super.mapValue(value);
                if (mapped instanceof Handle handle) return hook(handle);
                return mapped;
            }
        };
    }
    private static boolean hook(String owner, String name) {
        return owner.equals("net/neoforged/neoforge/common/NeoForge") && name.equals("registerConfigScreen")
                || owner.equals(CONTEXT) && Set.of("registerConfig", "registerExtensionPoint").contains(name);
    }
    private static String hookDescriptor(String owner, String descriptor) {
        return owner.equals(CONTEXT) ? "(L" + CONTEXT + ";" + descriptor.substring(1) : descriptor;
    }
    private static Handle hook(Handle handle) {
        return hook(handle.getOwner(), handle.getName()) ? new Handle(Opcodes.H_INVOKESTATIC, HOOKS, handle.getName(), hookDescriptor(handle.getOwner(), handle.getDesc()), false) : handle;
    }
    private byte[] bytecode(byte[] original) {
        ClassNode input = new ClassNode(); new ClassReader(original).accept(input, 0);
        for (List<AnnotationNode> annotations : List.of(input.visibleAnnotations == null ? List.<AnnotationNode>of() : input.visibleAnnotations,
                input.invisibleAnnotations == null ? List.<AnnotationNode>of() : input.invisibleAnnotations)) {
            if (annotations.stream().anyMatch(annotation -> annotation.desc.equals("Lnet/minecraftforge/eventbus/api/Cancelable;"))) {
                if (!input.interfaces.contains("net/neoforged/bus/api/ICancellableEvent")) input.interfaces.add("net/neoforged/bus/api/ICancellableEvent");
                annotations.removeIf(annotation -> annotation.desc.equals("Lnet/minecraftforge/eventbus/api/Cancelable;"));
            }
        }
        ClassNode mapped = new ClassNode(); input.accept(new ClassRemapper(mapped, remapper()));
        for (List<AnnotationNode> annotations : List.of(mapped.visibleAnnotations == null ? List.<AnnotationNode>of() : mapped.visibleAnnotations,
                mapped.invisibleAnnotations == null ? List.<AnnotationNode>of() : mapped.invisibleAnnotations))
            for (AnnotationNode annotation : annotations) if (annotation.desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber;") && annotation.values != null)
                for (Object value : annotation.values) if (value instanceof String[] enumValue && enumValue[0].equals("Lnet/neoforged/fml/common/EventBusSubscriber$Bus;") && enumValue[1].equals("FORGE")) enumValue[1] = "GAME";
        for (MethodNode method : mapped.methods) for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (hook(call.owner, call.name)) { call.desc = hookDescriptor(call.owner, call.desc); call.owner = HOOKS; call.setOpcode(Opcodes.INVOKESTATIC); call.itf = false; }
                if (call.owner.startsWith("net/neoforged/") || call.owner.equals(HOOKS))
                    if (!method(call.owner, call.name, call.desc, new HashSet<>())) throw unsupported("missing API method " + call.owner.replace('/', '.') + "." + call.name + call.desc + " (used by " + mapped.name + ")");
            } else if (instruction instanceof FieldInsnNode access && access.owner.startsWith("net/neoforged/")) {
                if (!field(access.owner, access.name, access.desc, new HashSet<>())) throw unsupported("missing API field " + access.owner.replace('/', '.') + "." + access.name + ":" + access.desc);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                for (Object arg : dynamic.bsmArgs) if (arg instanceof Handle handle && (handle.getOwner().startsWith("net/neoforged/") || handle.getOwner().equals(HOOKS))) {
                    boolean exists = handle.getTag() <= Opcodes.H_PUTSTATIC ? field(handle.getOwner(), handle.getName(), handle.getDesc(), new HashSet<>()) : method(handle.getOwner(), handle.getName(), handle.getDesc(), new HashSet<>());
                    if (!exists) throw unsupported("missing API handle " + handle.getOwner() + "." + handle.getName() + handle.getDesc());
                }
            }
        }
        ClassWriter writer = new ClassWriter(0); mapped.accept(writer); return writer.toByteArray();
    }
    public Archive remap(Archive source, Path output) throws Exception {
        var file = ForgeMetadata.read(source);
        for (Metadata mod : file.mods()) admit(new Discovery.Candidate(source, mod));
        Map<String, byte[]> resources = new TreeMap<>();
        for (String resource : source.names()) {
            byte[] bytes = source.read(resource);
            if (resource.endsWith(".class")) bytes = bytecode(bytes);
            else if (resource.endsWith(".json") && (resource.contains("mixin") || resource.contains("refmap")) || resource.equals("META-INF/accesstransformer.cfg"))
                bytes = member(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            if (resource.equals("META-INF/MANIFEST.MF")) {
                Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
                for (String attribute : List.of("Class-Path", "Multi-Release", "TweakClass", "TweakOrder")) manifest.getMainAttributes().remove(new Attributes.Name(attribute));
                var buffer = new ByteArrayOutputStream(); manifest.write(buffer); bytes = buffer.toByteArray();
            }
            resources.put(resource, bytes);
        }
        resources.put("META-INF/neoforge.mods.toml", descriptor(source, file));
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var jar = new JarOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW))) {
            for (var resource : resources.entrySet()) { JarEntry entry = new JarEntry(resource.getKey()); entry.setTime(0); jar.putNextEntry(entry); jar.write(resource.getValue()); jar.closeEntry(); }
        }
        Archive mapped = Archive.read(output).java21View(audit);
        Set<String> nested = new HashSet<>(); source.names().stream().filter(resource -> resource.endsWith(".jar")).forEach(nested::add);
        mapped = mapped.permitDeclaredNested(nested);
        audit.record("PREPARE", "forge-neoforge-bridge", source.path().toString(), Map.of("sourceSha256", source.hash(), "outputSha256", mapped.hash(),
                "forgeContract", VERSION, "runtime", "NeoForge 21.1.248", "gameNamespace", "Mojang", "nativeLauncher", "false"));
        return mapped;
    }
    private static Map<String, Object> table(TomlTable source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : source.keySet()) result.put(key, value(source.get(List.of(key)))); return result;
    }
    private static Object value(Object source) {
        if (source instanceof TomlTable sourceTable) return table(sourceTable);
        if (source instanceof TomlArray array) { List<Object> values = new ArrayList<>(); for (int i = 0; i < array.size(); i++) values.add(value(array.get(i))); return values; }
        return source;
    }
    private static byte[] descriptor(Archive source, ForgeMetadata file) {
        Map<String, Object> data = table(Toml.parse(new String(source.read("META-INF/mods.toml"), StandardCharsets.UTF_8)));
        data.put("loaderVersion", "[4.0.43]");
        @SuppressWarnings("unchecked") List<Map<String, Object>> mods = (List<Map<String, Object>>)data.get("mods");
        for (Map<String, Object> mod : mods) mod.put("version", file.mods().stream().filter(metadata -> metadata.id().equals(mod.get("modId"))).findFirst().orElseThrow().version());
        if (data.get("dependencies") instanceof Map<?, ?> dependencies) for (Object group : dependencies.values())
            if (group instanceof List<?> entries) for (Object entry : entries) {
                @SuppressWarnings("unchecked") Map<String, Object> dependency = (Map<String, Object>)entry;
                dependency.put("type", Boolean.TRUE.equals(dependency.remove("mandatory")) ? "required" : "optional");
            }
        StringBuilder text = new StringBuilder(); writeTable(text, data, ""); return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void writeTable(StringBuilder text, Map<?, ?> data, String path) {
        Gson json = new Gson();
        for (var entry : data.entrySet()) if (!(entry.getValue() instanceof Map<?, ?>) && !(entry.getValue() instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?>))
            text.append(json.toJson(entry.getKey().toString())).append("=").append(json.toJson(entry.getValue())).append('\n');
        for (var entry : data.entrySet()) {
            String next = path.isEmpty() ? json.toJson(entry.getKey().toString()) : path + "." + json.toJson(entry.getKey().toString());
            if (entry.getValue() instanceof Map<?, ?> child) { text.append("\n[").append(next).append("]\n"); writeTable(text, child, next); }
            else if (entry.getValue() instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?>)
                for (Object child : list) { text.append("\n[[").append(next).append("]]\n"); writeTable(text, (Map<?, ?>)child, next); }
        }
    }
}
