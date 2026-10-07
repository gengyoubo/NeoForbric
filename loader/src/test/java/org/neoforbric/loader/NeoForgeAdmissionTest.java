package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.neoforge.NeoForgeAdmission;
import org.neoforbric.api.*;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgeAdmissionTest {
    @TempDir Path root;
    private Discovery.Candidate candidate(String loader, String extra) throws Exception {
        Archive archive = Archive.read(TestJars.jar(root.resolve("mod.jar"), Map.of("META-INF/neoforge.mods.toml", TestJars.text("""
                modLoader="%s"
                loaderVersion="[1,)"
                license="Test"
                [[mods]]
                modId="sample"
                version="1.0.0"
                %s
                """.formatted(loader, extra)))));
        return new Discovery.Candidate(archive, Metadata.read(archive).getFirst());
    }
    @Test void supportedModNeedsExplicitNeoForgeRuntimeAndKeepsItsProvenance() throws Exception {
        var mod = candidate("javafml", "");
        try (var catalog = new ModCatalog(List.of(mod), new AuditLog(), true)) {
            assertEquals(List.of(mod), catalog.selectClient(List.of(mod), true));
            var info = LoadedMods.snapshot().getLast();
            assertEquals(ModEcosystem.NEOFORGE, info.ecosystem()); assertEquals(LoadStatus.DISABLED, info.status());
            assertEquals("NeoForbric NeoForge Adapter", info.runtimeAdapter()); assertEquals(mod.archive().path(), info.sourceJar());
        }
        assertEquals("NATIVE_RUNTIME_UNSUPPORTED", assertThrows(Failure.class, () -> Resolver.resolve(List.of(mod), "client", new AuditLog())).code());
    }
    @Test void unsupportedLanguageMixinsAndEnumExtensionsAreRejectedBeforeConstructors() throws Exception {
        for (var requirement : List.of(Map.entry("kotlinforforge", ""), Map.entry("javafml", "[[mixins]]\nconfig=\"sample.mixins.json\""), Map.entry("javafml", "enumExtensions=\"enums.json\""))) {
            var mod = candidate(requirement.getKey(), requirement.getValue());
            assertEquals("NEOFORGE_FEATURE_UNSUPPORTED", assertThrows(Failure.class, () -> NeoForgeAdmission.admit(mod)).code());
            try (var catalog = new ModCatalog(List.of(mod), new AuditLog(), true)) {
                assertTrue(catalog.selectClient(List.of(mod), true).isEmpty());
                assertEquals(LoadStatus.UNSUPPORTED, LoadedMods.snapshot().getLast().status());
            }
        }
    }
}
