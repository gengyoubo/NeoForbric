package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.fabric.FabricRuntimePlan;
import static org.junit.jupiter.api.Assertions.*;

class FabricRuntimePlanTest {
    @TempDir Path temporary;
    private Discovery.Candidate root(String dependencies, Map<String, byte[]> entries) throws Exception {
        Map<String, byte[]> contents = new HashMap<>(entries);
        contents.put("fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"rootmod","version":"1.0.0","depends":%s,"jars":[{"file":"META-INF/jars/helper.jar"}]}
                """.formatted(dependencies)));
        Archive archive = Archive.read(TestJars.jar(temporary.resolve("root.jar"), contents));
        return new Discovery.Candidate(archive, Metadata.read(archive).getFirst());
    }
    private byte[] helper() throws Exception {
        return Files.readAllBytes(TestJars.jar(temporary.resolve("helper.jar"), Map.of("fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"helpermod","version":"1.2.3"}
                """))));
    }
    @Test void declaredNestedDependencyIsResolvedWithoutASecondDiscoveryOrClassloader() throws Exception {
        AuditLog audit = new AuditLog(); var plan = new FabricRuntimePlan(temporary.resolve("cache"), EnvType.CLIENT, audit);
        var all = plan.discover(List.of(root("{\"helpermod\":[\">=1.0.0 <2.0.0\",\"3.x\"],\"java\":\">=21\"}", Map.of("META-INF/jars/helper.jar", helper()))));
        plan.builtin("java", "21", List.of(Path.of(System.getProperty("java.home"))));
        assertEquals(Set.of("helpermod", "rootmod"), plan.resolve().stream().map(c -> c.metadata().id()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, all.size());
        assertTrue(audit.events().stream().anyMatch(event -> event.type().equals("nested-mod")));
        assertEquals(Set.of("META-INF/jars/helper.jar"), plan.node(all.stream().filter(c -> c.metadata().id().equals("rootmod")).findFirst().orElseThrow()).nestedPaths());
    }
    @Test void undeclaredNestedArchiveAndUnresolvedVersionRangeRemainFatal() throws Exception {
        var invalid = root("{}", Map.of("META-INF/jars/helper.jar", helper(), "unlisted.jar", helper()));
        var plan = new FabricRuntimePlan(temporary.resolve("cache"), EnvType.CLIENT, new AuditLog());
        assertEquals("UNSUPPORTED_LAYOUT", assertThrows(Failure.class, () -> plan.discover(List.of(invalid))).code());
        var incompatible = new FabricRuntimePlan(temporary.resolve("other-cache"), EnvType.CLIENT, new AuditLog());
        incompatible.discover(List.of(root("{\"helpermod\":\">=2 <3\"}", Map.of("META-INF/jars/helper.jar", helper()))));
        assertEquals("FABRIC_DEPENDENCY", assertThrows(Failure.class, incompatible::resolve).code());
    }
}
