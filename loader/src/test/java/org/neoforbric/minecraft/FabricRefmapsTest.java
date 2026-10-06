package org.neoforbric.minecraft;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FabricRefmapsTest {
    @Test void annotationKeysRemainNamedWhileOwnerlessAndQualifiedTargetsUseMojang() throws Exception {
        MemoryMappingTree tree = new MemoryMappingTree();
        tree.visitNamespaces("intermediary", List.of("mojang"));
        tree.visitClass("net/minecraft/class_3497"); tree.visitDstName(net.fabricmc.mappingio.MappedElementKind.CLASS, 0, "net/minecraft/tags/TagEntry"); tree.visitElementContent(net.fabricmc.mappingio.MappedElementKind.CLASS);
        tree.visitField("field_15584", "Lnet/minecraft/class_2960;"); tree.visitDstName(net.fabricmc.mappingio.MappedElementKind.FIELD, 0, "id"); tree.visitElementContent(net.fabricmc.mappingio.MappedElementKind.FIELD);
        tree.visitMethod("method_26790", "()Z"); tree.visitDstName(net.fabricmc.mappingio.MappedElementKind.METHOD, 0, "build"); tree.visitElementContent(net.fabricmc.mappingio.MappedElementKind.METHOD);
        tree.visitClass("net/minecraft/class_2960"); tree.visitDstName(net.fabricmc.mappingio.MappedElementKind.CLASS, 0, "net/minecraft/resources/ResourceLocation"); tree.visitElementContent(net.fabricmc.mappingio.MappedElementKind.CLASS); tree.visitEnd();
        String input = """
                {"mappings":{"demo/Mixin":{"resolve":"Lnet/minecraft/class_3497;method_26790()Z","id":"field_15584:Lnet/minecraft/class_2960;"}},"data":{"named:intermediary":{"demo/Mixin":{"resolve":"Lnet/minecraft/class_3497;method_26790()Z"}}}}
                """;
        JsonObject output = JsonParser.parseString(new String(FabricRefmaps.remap(input.getBytes(StandardCharsets.UTF_8), tree), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject mappings = output.getAsJsonObject("mappings").getAsJsonObject("demo/Mixin");
        assertEquals("Lnet/minecraft/tags/TagEntry;build()Z", mappings.get("resolve").getAsString());
        assertEquals("id:Lnet/minecraft/resources/ResourceLocation;", mappings.get("id").getAsString());
        assertEquals(mappings.get("resolve"), output.getAsJsonObject("data").getAsJsonObject("named:intermediary").getAsJsonObject("demo/Mixin").get("resolve"));
    }
}
