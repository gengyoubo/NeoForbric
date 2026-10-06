package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ModCatalogTest {
    @TempDir Path directory;
    private void fabric(String file, String id, String side) throws Exception {
        TestJars.jar(directory.resolve(file), Map.of("fabric.mod.json", TestJars.text("""
                {"schemaVersion":1,"id":"%s","name":"Display Name","description":"Original description",
                 "version":"1.0.0","environment":"%s","icon":{"16":"icon.png"}}
                """.formatted(id, side)), "icon.png", new byte[]{1, 2, 3}));
    }
    @Test void finalDecisionsKeepOriginalProvenanceAndDistinguishSourceFromAdapter() throws Exception {
        fabric("fabric.jar", "fabric_sample", "*"); fabric("server.jar", "server_sample", "server");
        TestJars.jar(directory.resolve("forge.jar"), Map.of("META-INF/mods.toml", TestJars.text("""
                [[mods]]
                modId="forge_sample"
                version="1.0.0"
                displayName="Forge Sample"
                """)));
        TestJars.jar(directory.resolve("neo.jar"), Map.of("META-INF/neoforge.mods.toml", TestJars.text("""
                [[mods]]
                modId="neo_sample"
                version="1.0.0"
                displayName="NeoForge Sample"
                """)));
        var candidates = Discovery.discover(directory, new AuditLog());
        try (var catalog = new ModCatalog(candidates, new AuditLog())) {
            var active = catalog.selectClient(candidates); assertEquals(1, active.size());
            catalog.loaded(active);
            var infos = LoadedMods.snapshot(); assertEquals(5, infos.size());
            var fabric = infos.stream().filter(i -> i.id().equals("fabric_sample")).findFirst().orElseThrow();
            assertEquals(ModEcosystem.FABRIC, fabric.ecosystem()); assertEquals(LoadStatus.LOADED, fabric.status());
            assertEquals("NeoForbric Fabric Adapter", fabric.runtimeAdapter()); assertEquals("intermediary → Mojang", fabric.namespace());
            assertEquals("Display Name", fabric.name()); assertEquals("Original description", fabric.description());
            assertEquals(directory.resolve("fabric.jar").toRealPath(), fabric.sourceJar());
            assertEquals(Archive.read(fabric.sourceJar()).hash(), fabric.sourceSha256());
            byte[] icon = fabric.iconPng(); icon[0] = 99; assertEquals(1, fabric.iconPng()[0]);
            assertThrows(UnsupportedOperationException.class, () -> infos.clear());
            assertEquals(LoadStatus.DISABLED, infos.stream().filter(i -> i.id().equals("server_sample")).findFirst().orElseThrow().status());
            for (String id : List.of("forge_sample", "neo_sample")) {
                var unsupported = infos.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
                assertEquals(LoadStatus.UNSUPPORTED, unsupported.status()); assertTrue(unsupported.reason().contains("adapter not implemented"));
            }
            catalog.failed("Fatal initialization failure");
            assertEquals(LoadStatus.FAILED, LoadedMods.snapshot().stream().filter(i -> i.id().equals("fabric_sample")).findFirst().orElseThrow().status());
            assertEquals(LoadStatus.LOADED, fabric.status(), "Previously published snapshots remain immutable");
        }
        assertTrue(LoadedMods.snapshot().isEmpty());
    }
}
