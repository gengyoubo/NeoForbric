package org.neoforbric.loader;

import java.nio.file.*;
import java.net.JarURLConnection;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.net.URL;
import org.objectweb.asm.ClassReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GameResourcesTest {
    @TempDir Path temporary;
    @Test void moduleResourceLookupReadsOwnedSnapshotWithoutParentFallback() throws Exception {
        Path source = TestJars.jar(temporary.resolve("module.jar"), Map.of(
                "demo/ModuleOwner.class", TestJars.type("demo.ModuleOwner"),
                "demo/builtins.bin", TestJars.text("original")));
        Archive archive = Archive.read(source); AuditLog audit = new AuditLog();
        TransformPipeline pipeline = new TransformPipeline(); pipeline.seal(audit);
        try (var resources = new GameResources(audit);
             var loader = new GameClassLoader(ClassIndex.prepare(List.of(archive), getClass().getClassLoader(), audit), pipeline, getClass().getClassLoader(), audit, resources)) {
            loader.open(); Module module = loader.loadClass("demo.ModuleOwner").getModule();
            assertFalse(module.isNamed());
            TestJars.jar(source, Map.of("demo/builtins.bin", TestJars.text("changed")));
            try (var stream = module.getResourceAsStream("/demo/builtins.bin")) {
                assertNotNull(stream); assertEquals("original", new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            assertNotNull(getClass().getClassLoader().getResource("org/neoforbric/loader/GameResourcesTest.class"));
            assertNull(module.getResourceAsStream("org/neoforbric/loader/GameResourcesTest.class"));
            assertNull(loader.findResource("unowned.module", "demo/builtins.bin"));
            loader.close(); assertNull(module.getResourceAsStream("demo/builtins.bin"));
        }
    }
    @Test void standardJarUrlAndNioPathUseTheImmutableSnapshotAndCloseTheirFilesystem() throws Exception {
        Path source = TestJars.jar(temporary.resolve("game.jar"), Map.of("data/.mcassetsroot", TestJars.text("original")));
        Archive archive = Archive.read(source); Path snapshot;
        try (GameResources resources = new GameResources(new AuditLog())) {
            var url = resources.resource(archive, "data/.mcassetsroot");
            assertEquals("jar", url.getProtocol()); assertNull(resources.resource(archive, "absent"));
            snapshot = Path.of(((JarURLConnection) url.openConnection()).getJarFileURL().toURI());
            TestJars.jar(source, Map.of("data/.mcassetsroot", TestJars.text("changed")));
            try (var stream = url.openStream()) { assertEquals("original", new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
            assertEquals("original", Files.readString(Path.of(url.toURI())));
            // Reflection scanners lose our URL handler when constructing their
            // archive roots, leaving a standard JDK-cached JarFile open.
            URL reconstructed = new URL(url.toExternalForm());
            var cached = (JarURLConnection) reconstructed.openConnection();
            assertTrue(cached.getUseCaches());
            try (var stream = cached.getInputStream()) { assertEquals("original", new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
            // Mods can retain an uncached stream or JarFile until process exit;
            // the game resource owner must release them on domain shutdown.
            var retained = (JarURLConnection) url.openConnection(); retained.getJarFile(); retained.getInputStream();
            assertEquals(8, url.openConnection().getContentLength());
            // Connecting to an implicit directory opens the JAR even if reading
            // the absent physical ZIP directory entry subsequently fails.
            var directory = resources.resource(archive, "data/").openConnection();
            assertThrows(java.io.FileNotFoundException.class, directory::connect);
            assertThrows(java.io.FileNotFoundException.class, directory::getInputStream);
        }
        assertFalse(Files.exists(snapshot));
    }
    @Test void packageDiscoveryFindsEveryOwnedArchiveWithoutExplicitDirectoryEntries() throws Exception {
        Archive a = Archive.read(TestJars.jar(temporary.resolve("a.jar"), Map.of("demo/scan/One.class", TestJars.type("demo.scan.One"))));
        Archive b = Archive.read(TestJars.jar(temporary.resolve("b.jar"), Map.of("demo/scan/Two.class", TestJars.type("demo.scan.Two"))));
        assertFalse(a.names().contains("demo/scan/"));
        AuditLog audit = new AuditLog(); TransformPipeline pipeline = new TransformPipeline();
        try (var resources = new GameResources(audit);
             var loader = new GameClassLoader(ClassIndex.prepare(List.of(a, b), getClass().getClassLoader(), audit), pipeline, getClass().getClassLoader(), audit, resources)) {
            var packages = Collections.list(loader.getResources("demo/scan"));
            assertEquals(2, packages.size());
            assertEquals(2, Collections.list(loader.getResources("demo/scan/")).size());
            assertNull(loader.getResource("demo/scanner"));
            java.util.Set<String> scanned = new java.util.HashSet<>();
            for (var url : packages) {
                assertTrue(Files.isDirectory(Path.of(url.toURI())));
                String external = url.toExternalForm();
                URL root = new URL(external.substring(0, external.lastIndexOf("demo/scan")));
                var connection = (JarURLConnection) root.openConnection(); connection.setUseCaches(false);
                try (var jar = connection.getJarFile()) {
                    for (var entry : Collections.list(jar.entries())) if (entry.getName().endsWith(".class"))
                        try (var input = jar.getInputStream(entry)) { scanned.add(new ClassReader(input).getClassName()); }
                }
            }
            assertEquals(java.util.Set.of("demo/scan/One", "demo/scan/Two"), scanned);
            assertFalse(loader.hasDefined("demo.scan.One"));
        }
    }
}
