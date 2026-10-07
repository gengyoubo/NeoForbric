package org.neoforbric.forge;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.neoforbric.loader.*;
import org.neoforbric.minecraft.*;

/** Independent Forge input / service adapter; no NeoForge runtime implementation is reused. */
public final class ForgeRuntime {
    private final Path root, bridge;
    private final JsonObject plan;
    private Class<?> nativeBridge;
    public ForgeRuntime(Path plan, Path bridge) throws Exception {
        this.plan = ForgePreparation.verify(plan); this.root = plan.toAbsolutePath().normalize().getParent(); this.bridge = bridge.toRealPath();
    }
    public Path bridge() { return bridge; }
    public List<Archive> inputs(Path vanillaPlan, AuditLog audit) throws Exception {
        RuntimeInputs vanilla = RuntimeInputs.read(vanillaPlan, audit);
        JsonObject vanillaJson = JsonParser.parseString(Files.readString(vanillaPlan)).getAsJsonObject();
        Map<String, Path> libraries = new LinkedHashMap<>();
        for (JsonElement value : vanillaJson.getAsJsonArray("files")) {
            var file = value.getAsJsonObject();
            if (file.get("role").getAsString().equals("library")) libraries.put(key(file.get("coordinate").getAsString()), vanillaPlan.toAbsolutePath().getParent().resolve(file.get("path").getAsString()));
        }
        Path game = null;
        for (JsonElement value : plan.getAsJsonArray("files")) {
            var file = value.getAsJsonObject(); String role = file.get("role").getAsString(), coordinate = file.get("coordinate").getAsString();
            Path path = root.resolve(file.get("path").getAsString());
            audit.record("PREPARE", "runtime-input", coordinate, Map.of("ecosystem", "FORGE", "role", role, "path", path.toString(), "sha256", file.get("sha256").getAsString()));
            if (role.equals("game")) game = path;
            if (Set.of("library", "forge").contains(role) && !coordinate.startsWith("org.ow2.asm:")) libraries.put(key(coordinate), path);
        }
        List<Archive> inputs = new ArrayList<>(); inputs.add(Archive.readRuntimeGame(Objects.requireNonNull(game)));
        for (Path path : libraries.values()) inputs.add(Archive.read(path));
        inputs.add(Archive.read(bridge)); return List.copyOf(inputs);
    }
    private static String key(String coordinate) { String[] parts = coordinate.split(":"); return parts[0] + ":" + parts[1]; }
    public void install(TransformPipeline pipeline) {
        pipeline.add(new ForgeHostHook());
        String previous = "forge-native-host";
        for (var stage : List.of(Map.entry("forge-native-plugins", "plugins"), Map.entry("forge-access-transformers", "access"), Map.entry("forge-coremods", "coremods"))) {
            String prerequisite = previous;
            pipeline.add(new TransformPipeline.Transformer() {
                public String id() { return stage.getKey(); }
                public Set<String> after() { return Set.of(prerequisite); }
                public byte[] transform(TransformPipeline.Context context, byte[] bytes) throws Exception {
                    return nativeBridge == null ? bytes : (byte[])invoke(nativeBridge.getMethod(stage.getValue(), String.class, byte[].class), context.name(), bytes);
                }
            }); previous = stage.getKey();
        }
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> prepare(GameClassLoader loader, Path directory, String side) throws Exception {
        var type = Class.forName("org.neoforbric.forge.runtime.ForgeBridge", true, loader);
        Path access = root.resolve("forge-access.cfg");
        try (var jar = new JarFile(root.resolve("libraries/forge-universal.jar").toFile()); var stream = jar.getInputStream(jar.getJarEntry("META-INF/accesstransformer.cfg"))) { Files.write(access, stream.readAllBytes()); }
        Map<String, String> mapping = new HashMap<>();
        Path names = root.resolve("maven/de/oceanlabs/mcp/mcp_config/1.21.1-20240808.132146/mcp_config-1.21.1-20240808.132146-srg2off.jar");
        try (var jar = new JarFile(names.toFile())) {
            for (String resource : List.of("fields.csv", "methods.csv")) try (var input = jar.getInputStream(jar.getJarEntry(resource))) {
                for (String row : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).toList()) {
                    String[] parts = row.split(",", -1); if (parts.length >= 2) mapping.put(parts[0], parts[1]);
                }
            }
        }
        var result = (Map<String, Object>)invoke(type.getMethod("prepare", Path.class, String.class, Path.class, Path.class, Map.class), directory, side, root.resolve("libraries/forge-universal.jar"), access, mapping);
        nativeBridge = type; return result;
    }
    public static Object invoke(Method method, Object... arguments) throws Exception {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception error) throw error;
            if (failed.getCause() instanceof Error error) throw error;
            throw failed;
        }
    }
}
