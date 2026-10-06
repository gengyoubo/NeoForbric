package org.neoforbric.loader;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveLimitTest {
    @TempDir Path temporary;

    private Path jar(String name, int count, byte[] content) throws IOException {
        Path path = temporary.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            output.putNextEntry(new ZipEntry("assets/chipped/"));
            output.closeEntry();
            for (int index = 0; index < count; index++) {
                output.putNextEntry(new ZipEntry("assets/chipped/" + index + ".json"));
                output.write(content);
                output.closeEntry();
            }
        }
        return path;
    }

    @Test void resourceHeavyModKeepsEveryFile() throws Exception {
        byte[] content = TestJars.text("{\"parent\":\"minecraft:block/cube_all\"}");
        Path path = jar("chipped-sized.jar", 36_881, content);
        Archive archive = Archive.read(path);
        assertEquals(36_881, archive.names().size());
        String last = "assets/chipped/36880.json";
        assertArrayEquals(content, archive.read(last));
        try (InputStream input = archive.resource(last).openStream()) {
            assertArrayEquals(content, input.readAllBytes());
        }
        assertEquals(archive.names(), Archive.readRuntimeGame(path).names());
    }

    @Test void fileCountIsBoundedAndDirectoriesDoNotCount() throws Exception {
        Path boundary = jar("boundary.jar", 100_000, new byte[0]);
        assertEquals(100_000, Archive.read(boundary).names().size());
        Path excessive = jar("too-many.jar", 100_001, new byte[0]);
        Failure failure = assertThrows(Failure.class, () -> Archive.read(excessive));
        assertEquals("ARCHIVE_LIMIT", failure.code());
        assertTrue(failure.getMessage().contains("100000 file entries"));
        assertTrue(failure.getMessage().contains("assets/chipped/100000.json"));
        assertEquals("ARCHIVE_LIMIT", assertThrows(Failure.class, () -> Archive.readRuntimeGame(excessive)).code());
    }

    @Test void oversizedEntryIsStillRejected() throws Exception {
        Path path = jar("oversized-entry.jar", 1, new byte[32 * 1024 * 1024 + 1]);
        Failure failure = assertThrows(Failure.class, () -> Archive.read(path));
        assertEquals("ARCHIVE_LIMIT", failure.code());
        assertTrue(failure.getMessage().contains("entry assets/chipped/0.json exceeds 32 MiB"));
    }

    @Test void expandedTotalIsStillBounded() throws Exception {
        Path path = jar("oversized-total.jar", 9, new byte[32 * 1024 * 1024]);
        Failure failure = assertThrows(Failure.class, () -> Archive.read(path));
        assertEquals("ARCHIVE_LIMIT", failure.code());
        assertTrue(failure.getMessage().contains("256 MiB expanded size"));
    }
}
