package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
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
                """), "demo/NestedHelper.class", TestJars.type("demo.NestedHelper"))));
    }
    @Test void declaredNestedDependencyIsResolvedWithoutASecondDiscoveryOrClassloader() throws Exception {
        AuditLog audit = new AuditLog(); var plan = new FabricRuntimePlan(temporary.resolve("cache"), EnvType.CLIENT, audit);
        var all = plan.discover(List.of(root("{\"helpermod\":[\">=1.0.0 <2.0.0\",\"3.x\"],\"java\":\">=21\"}", Map.of("META-INF/jars/helper.jar", helper()))));
        plan.builtin("java", "21", List.of(Path.of(System.getProperty("java.home"))));
        var selected = plan.resolve();
        assertEquals(Set.of("helpermod", "rootmod"), selected.stream().map(c -> c.metadata().id()).collect(java.util.stream.Collectors.toSet()));
        var index = ClassIndex.prepare(selected.stream().map(Discovery.Candidate::archive).toList(), getClass().getClassLoader(), audit);
        assertNotNull(index.entry("demo.NestedHelper"));
        var pipeline = new TransformPipeline();
        try (var loader = new GameClassLoader(index, pipeline, getClass().getClassLoader(), audit)) {
            pipeline.seal(audit); loader.open();
            assertSame(loader, loader.loadClass("demo.NestedHelper").getClassLoader());
        }
        assertEquals(2, all.size());
        assertTrue(audit.events().stream().anyMatch(event -> event.type().equals("nested-mod")));
        assertEquals(Set.of("META-INF/jars/helper.jar"), plan.node(all.stream().filter(c -> c.metadata().id().equals("rootmod")).findFirst().orElseThrow()).nestedPaths());
    }
    @Test void optionalNestedModReportsMissingDependenciesAndIsSelectedWhenTheyArePresent() throws Exception {
        byte[] library = Files.readAllBytes(TestJars.jar(temporary.resolve("library.jar"), Map.of(
                "fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"helpermod","version":"1.0.0","depends":{"calio_probe":"*"}}
                """), "demo/NestedHelper.class", TestJars.type("demo.NestedHelper"))));
        var root = root("{}", Map.of("META-INF/jars/helper.jar", library));
        AuditLog audit = new AuditLog();
        var absent = new FabricRuntimePlan(temporary.resolve("absent"), EnvType.CLIENT, audit);
        var all = absent.discover(List.of(root)); var selected = absent.resolve();
        assertEquals(List.of("rootmod"), selected.stream().map(c -> c.metadata().id()).toList());
        Path nested = all.stream().filter(c -> c.metadata().id().equals("helpermod")).findFirst().orElseThrow().archive().path();
        assertTrue(absent.exclusions().get(nested).contains("calio_probe"));
        assertTrue(audit.events().stream().anyMatch(e -> e.type().equals("fabric-runtime-excluded") && e.subject().equals("helpermod")));
        try (var catalog = new ModCatalog(all, audit)) {
            catalog.selectFabricRuntime(all, selected, absent.exclusions());
            var info = org.neoforbric.api.LoadedMods.snapshot().stream().filter(i -> i.id().equals("helpermod")).findFirst().orElseThrow();
            assertEquals(org.neoforbric.api.LoadStatus.DISABLED, info.status());
            assertTrue(info.reason().contains("calio_probe"));
        }
        Archive dependency = Archive.read(TestJars.jar(temporary.resolve("calio.jar"), Map.of("fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"calio_impl","provides":["calio_probe"],"version":"1.0.0"}
                """))));
        var present = new FabricRuntimePlan(temporary.resolve("present"), EnvType.CLIENT, new AuditLog());
        present.discover(List.of(root, new Discovery.Candidate(dependency, Metadata.read(dependency).getFirst())));
        assertEquals(Set.of("rootmod", "helpermod", "calio_impl"), present.resolve().stream().map(c -> c.metadata().id()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(present.exclusions().isEmpty());
    }

    @Test void passiveResolverAcceptsTheLoaderUpgradeAndRejectsUnsatisfiedFloors() throws Exception {
        var mod = root("{\"fabricloader\":\">=0.17\"}", Map.of("META-INF/jars/helper.jar", helper()));
        var old = new FabricRuntimePlan(temporary.resolve("old"), EnvType.CLIENT, new AuditLog());
        old.discover(List.of(mod)); old.builtin("fabricloader", "0.16.10", List.of(temporary));
        assertEquals("FABRIC_DEPENDENCY", assertThrows(Failure.class, old::resolve).code());

        var upgraded = new FabricRuntimePlan(temporary.resolve("upgraded"), EnvType.CLIENT, new AuditLog());
        upgraded.discover(List.of(mod)); upgraded.builtin("fabricloader", FabricLoaderImpl.VERSION, List.of(temporary));
        assertTrue(upgraded.resolve().stream().anyMatch(c -> c.metadata().id().equals("rootmod")));
        assertEquals("0.19.5", upgraded.selectedNative().stream().filter(c -> c.getId().equals("fabricloader"))
                .findFirst().orElseThrow().getVersion().getFriendlyString());

        var future = new FabricRuntimePlan(temporary.resolve("future"), EnvType.CLIENT, new AuditLog());
        future.discover(List.of(root("{\"fabricloader\":\">=0.20\"}", Map.of("META-INF/jars/helper.jar", helper()))));
        future.builtin("fabricloader", FabricLoaderImpl.VERSION, List.of(temporary));
        assertEquals("FABRIC_DEPENDENCY", assertThrows(Failure.class, future::resolve).code());
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
