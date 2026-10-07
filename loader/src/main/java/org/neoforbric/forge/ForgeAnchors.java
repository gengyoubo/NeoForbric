package org.neoforbric.forge;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.neoforbric.loader.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reviewed upstream bytes, including complete method descriptors and instruction order. */
public final class ForgeAnchors {
    private ForgeAnchors() {}
    public static JsonObject lock() {
        try (var input = ForgeAnchors.class.getResourceAsStream("/META-INF/neoforbric/forge-1.21.1.anchors.json")) {
            if (input == null) throw new Failure("FORGE_ANCHOR", "Missing Forge anchor lock");
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException error) { throw new Failure("FORGE_ANCHOR", "Cannot read anchors", error); }
    }
    public static void verifyClass(String name, byte[] bytes) {
        var anchor = lock().getAsJsonObject("classes").getAsJsonObject(name);
        if (anchor == null || bytes == null || !Archive.sha256(bytes).equals(anchor.get("sha256").getAsString()))
            throw new Failure("FORGE_ANCHOR", "Pinned Forge class differs: " + name);
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!node.methods.stream().map(method -> method.name + method.desc).toList().equals(
                anchor.getAsJsonArray("methods").asList().stream().map(JsonElement::getAsString).toList()))
            throw new Failure("FORGE_ANCHOR", "Pinned Forge method descriptors differ: " + name);
    }
    public static Map<String, Object> verify(ClassIndex index, AuditLog audit) {
        Map<String, Object> structure = new TreeMap<>();
        for (String name : lock().getAsJsonObject("classes").keySet()) {
            var entry = index.entry(name); verifyClass(name, entry == null ? null : entry.bytes());
            ClassNode node = new ClassNode(); new ClassReader(entry.bytes()).accept(node, 0);
            Map<String, Object> methods = new LinkedHashMap<>();
            for (var method : node.methods) {
                List<String> calls = new ArrayList<>(); List<String> constants = new ArrayList<>();
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
                    if (instruction instanceof LdcInsnNode constant && constant.cst instanceof String text) constants.add(text);
                }
                methods.put(method.name + method.desc, Map.of("calls", calls, "strings", constants));
            }
            structure.put(name, methods);
            audit.record("PREPARE", "forge-anchor", name, Map.of("sha256", Archive.sha256(entry.bytes()), "loader", "G"));
        }
        return structure;
    }
}
