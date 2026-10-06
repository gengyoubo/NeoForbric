package org.neoforbric.minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.mappingio.*;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.fabric.FabricRuntimePlan;
import org.neoforbric.loader.*;
import org.objectweb.asm.commons.Remapper;
import static org.junit.jupiter.api.Assertions.*;

class FabricResourceSelectionTest {
    @TempDir Path temporary;
    private static final String ORPHAN = "accessWidener v1 named\naccessible class net/minecraft/item/ItemStack\n";
    private Archive archive(String metadata, Map<String, String> resources) throws Exception {
        Path path = temporary.resolve("mod.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            Map<String, String> files = new HashMap<>(resources);
            files.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"testmod\",\"version\":\"1.0.0\"" + metadata + "}");
            for (var entry : files.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
            }
        }
        return Archive.read(path);
    }
    private String selected(Archive archive) throws Exception {
        var plan = new FabricRuntimePlan(temporary.resolve("nested"), EnvType.CLIENT, new AuditLog());
        var candidates = plan.discover(List.of(new Discovery.Candidate(archive, Metadata.read(archive).getFirst())));
        return plan.node(candidates.getFirst()).metadata().getClassTweaker();
    }
    private MemoryMappingTree mappings() throws Exception {
        var tree = new MemoryMappingTree(); tree.visitNamespaces("intermediary", List.of("mojang"));
        tree.visitClass("net/minecraft/class_1"); tree.visitDstName(MappedElementKind.CLASS, 0, "net/minecraft/world/item/ItemStack"); tree.visitElementContent(MappedElementKind.CLASS);
        tree.visitField("field_1", "I"); tree.visitDstName(MappedElementKind.FIELD, 0, "count"); tree.visitElementContent(MappedElementKind.FIELD); tree.visitEnd();
        return tree;
    }
    @Test void undeclaredNamedRulesAreOrdinaryResources() throws Exception {
        Archive source = archive("", Map.of("unused.accesswidener", ORPHAN));
        String path = selected(source); assertNull(path);
        assertTrue(GamePreparation.fabricResources(source, path, mappings(), new Remapper() {}).isEmpty());
        assertEquals(ORPHAN, new String(source.read("unused.accesswidener"), StandardCharsets.UTF_8));
    }
    @Test void onlyTheMetadataSelectedFileIsRemappedRegardlessOfItsExtension() throws Exception {
        for (String declared : List.of("active.rules", "dir/strange.blob")) {
            Archive source = archive(",\"accessWidener\":\"" + declared + "\"", Map.of("unused.accesswidener", ORPHAN,
                    declared, "accessWidener v2 intermediary\naccessible field net/minecraft/class_1 field_1 I\n"));
            String path = selected(source); assertEquals(declared, path);
            var remapper = new Remapper() {
                @Override public String map(String name) { return name.equals("net/minecraft/class_1") ? "net/minecraft/world/item/ItemStack" : name; }
                @Override public String mapFieldName(String owner, String name, String descriptor) { return "count"; }
            };
            Map<String, byte[]> overrides = GamePreparation.fabricResources(source, path, mappings(), remapper);
            assertEquals(Set.of(declared), overrides.keySet());
            assertTrue(new String(overrides.get(path), StandardCharsets.UTF_8).contains("net/minecraft/world/item/ItemStack\tcount\tI"));
        }
    }
    @Test void aMissingDeclaredAccessFileFailsWithItsPath() throws Exception {
        Archive source = archive(",\"accessWidener\":\"missing.rules\"", Map.of("unused.accesswidener", ORPHAN));
        String path = selected(source);
        Failure failure = assertThrows(Failure.class, () -> GamePreparation.fabricResources(source, path, mappings(), new Remapper() {}));
        assertEquals("FABRIC_ACCESS_RESOURCE", failure.code()); assertTrue(failure.getMessage().contains("missing.rules"));
    }
    @Test void declaredUnmappedNamedRulesStillFailWithResourceContext() throws Exception {
        Archive source = archive(",\"accessWidener\":\"active.rules\"", Map.of("active.rules", ORPHAN));
        String path = selected(source);
        Failure failure = assertThrows(Failure.class, () -> GamePreparation.fabricResources(source, path, mappings(), new Remapper() {}));
        assertEquals("FABRIC_ACCESS_NAMESPACE", failure.code());
        assertTrue(failure.getMessage().contains("active.rules")); assertTrue(failure.getMessage().contains("net/minecraft/item/ItemStack"));
    }
}
