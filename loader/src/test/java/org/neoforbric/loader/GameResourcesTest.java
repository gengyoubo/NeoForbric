package org.neoforbric.loader;

import java.nio.file.*;
import java.net.JarURLConnection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GameResourcesTest {
    @TempDir Path temporary;
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
        }
        assertFalse(Files.exists(snapshot));
    }
}
