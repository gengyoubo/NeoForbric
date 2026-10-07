package org.neoforbric.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.api.LoadedMods;
import org.neoforbric.fabric.FabricRuntimePlan;
import static org.junit.jupiter.api.Assertions.*;

class LoaderVersionTest {
    @TempDir Path temporary;

    @Test void catalogReportsGradleBuildVersion() {
        String expected = System.getProperty("loader.expectedVersion");
        assertNotNull(expected);
        assertEquals(expected, LoaderVersion.VERSION);
        try (var catalog = new ModCatalog(List.of(), new AuditLog())) {
            assertEquals(expected, LoadedMods.snapshot().getFirst().version());
        }
    }

    @Test void prototypeDependenciesUseBuildVersion() throws Exception {
        var candidate = TestJars.candidate(temporary.resolve("mod.jar"), "sample",
                Map.of("neoforbric", System.getProperty("loader.expectedVersion")));
        assertEquals(List.of(candidate), Resolver.resolve(List.of(candidate), "client", new AuditLog()));
        var mismatch = TestJars.candidate(temporary.resolve("mismatch.jar"), "mismatch", Map.of("neoforbric", "999.0.0"));
        assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class,
                () -> Resolver.resolve(List.of(mismatch), "client", new AuditLog())).code());
    }

    @Test void fabricDependenciesUseBuildVersion() throws Exception {
        Archive archive = Archive.read(TestJars.jar(temporary.resolve("fabric.jar"), Map.of("fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"sample","version":"1.0.0","depends":{"neoforbric":"=%s"}}
                """.formatted(System.getProperty("loader.expectedVersion"))))));
        var candidate = new Discovery.Candidate(archive, Metadata.read(archive).getFirst());
        assertEquals(List.of(candidate), Resolver.resolve(List.of(candidate), "client", new AuditLog(), true));
        var plan = new FabricRuntimePlan(temporary.resolve("cache"), EnvType.CLIENT, new AuditLog());
        plan.discover(List.of(candidate));
        plan.builtin("neoforbric", LoaderVersion.VERSION, List.of(temporary));
        assertEquals(List.of("sample"), plan.resolve().stream().map(c -> c.metadata().id()).toList());
        assertEquals(LoaderVersion.VERSION, plan.selectedNative().stream()
                .filter(c -> c.getId().equals("neoforbric")).findFirst().orElseThrow().getVersion().getFriendlyString());
    }
}
