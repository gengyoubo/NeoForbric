package org.neoforbric.minecraft;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.junit.jupiter.api.Test;
import org.neoforbric.loader.Failure;
import org.objectweb.asm.commons.Remapper;
import static org.junit.jupiter.api.Assertions.*;

class FabricAccessRulesTest {
    private MemoryMappingTree mappings() throws Exception {
        var tree = new MemoryMappingTree();
        MappingReader.read(new StringReader("tiny\t2\t0\tintermediary\tmojang\n"
                + "c\tnet/minecraft/class_1\tnet/minecraft/world/item/ItemStack\n"
                + "\tf\tI\tfield_1\tcount\n"
                + "\tm\t()V\tmethod_1\tuse\n"), tree);
        return tree;
    }
    private String remap(String rules, Remapper remapper) throws Exception {
        return new String(FabricAccessRules.remap(rules.getBytes(StandardCharsets.UTF_8), mappings(), remapper), StandardCharsets.UTF_8);
    }
    @Test void intermediaryRulesRemapClassesMembersAndNamespace() throws Exception {
        var remapper = new Remapper() {
            @Override public String map(String name) { return name.equals("net/minecraft/class_1") ? "net/minecraft/world/item/ItemStack" : name; }
            @Override public String mapFieldName(String owner, String name, String descriptor) { return "count"; }
            @Override public String mapMethodName(String owner, String name, String descriptor) { return "use"; }
        };
        String result = remap("accessWidener v2 intermediary\naccessible field net/minecraft/class_1 field_1 I\n"
                + "extendable method net/minecraft/class_1 method_1 ()V\n", remapper);
        assertTrue(result.startsWith("accessWidener\tv2\tmojang"), result);
        assertTrue(result.contains("net/minecraft/world/item/ItemStack\tcount\tI"), result);
        assertTrue(result.contains("net/minecraft/world/item/ItemStack\tuse\t()V"), result);
    }
    @Test void namedRulesRequireVerifiedMojangClassesAndMemberSignatures() throws Exception {
        var identity = new Remapper() {};
        String result = remap("accessWidener   v1  named\nmutable field net/minecraft/world/item/ItemStack count I\n", identity);
        assertTrue(result.startsWith("accessWidener\tv1\tmojang"), result);
        assertTrue(result.contains("net/minecraft/world/item/ItemStack\tcount\tI"), result);
        for (String rule : new String[]{"accessible class net/minecraft/item/ItemStack",
                "mutable field net/minecraft/world/item/ItemStack count J",
                "extendable method net/minecraft/world/item/ItemStack unknown ()V"}) {
            assertEquals("FABRIC_ACCESS_NAMESPACE", assertThrows(Failure.class,
                    () -> remap("accessWidener v2 named\n" + rule + "\n", identity)).code());
        }
    }
}
