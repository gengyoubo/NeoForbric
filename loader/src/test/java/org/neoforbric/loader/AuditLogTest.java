package org.neoforbric.loader;

import com.google.gson.JsonParser;
import com.sun.nio.file.ExtendedOpenOption;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
    @TempDir Path directory;

    @Test void replacesNormalReportWithoutWarnings() throws Exception {
        Path target = directory.resolve("audit.json");
        Files.writeString(target, "old report");
        var output = new ByteArrayOutputStream();
        var audit = new AuditLog();
        audit.writeDiagnostic(target, "SUCCESS", new PrintStream(output));
        assertEquals("SUCCESS", JsonParser.parseString(Files.readString(target)).getAsJsonObject().get("outcome").getAsString());
        assertEquals("", output.toString());
        assertEquals(target, audit.savedPath().orElseThrow());
        assertTrue(alternates().isEmpty());
    }

    @Test void blockedTargetPreservesCompleteRunningAndFinalReports() throws Exception {
        Path target = Files.createDirectory(directory.resolve("audit.json"));
        Files.writeString(target.resolve("keep.txt"), "reader-owned target");
        var audit = new AuditLog();
        var output = new ByteArrayOutputStream();
        for (String outcome : List.of("RUNNING", "SUCCESS")) audit.writeDiagnostic(target, outcome, new PrintStream(output));
        assertEquals("reader-owned target", Files.readString(target.resolve("keep.txt")));
        var reports = alternates();
        assertEquals(2, reports.size());
        var outcomes = new java.util.HashSet<String>();
        for (Path report : reports) outcomes.add(JsonParser.parseString(Files.readString(report)).getAsJsonObject().get("outcome").getAsString());
        assertEquals(java.util.Set.of("RUNNING", "SUCCESS"), outcomes);
        for (Path report : reports) assertTrue(output.toString().contains(report.toString()));
        try (var paths = Files.list(directory)) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".audit-"))); }
    }

    @Test void unwritableDirectoryIsReportedWithoutAbortingLifecycle() throws Exception {
        Path parent = directory.resolve("blocked");
        Files.writeString(parent, "file instead of directory");
        var audit = new AuditLog();
        var output = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> audit.writeDiagnostic(parent.resolve("audit.json"), "SUCCESS", new PrintStream(output)));
        assertTrue(output.toString().contains("Could not save audit"));
        assertTrue(audit.savedPath().isEmpty());
        assertEquals("RESOURCE_WARNING", audit.events().getLast().details().get("severity"));
        assertThrows(IOException.class, () -> audit.write(parent.resolve("audit.json"), "SUCCESS"));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsReaderDenyingDeleteDoesNotLoseReportOrChangeOriginal() throws Exception {
        Path target = directory.resolve("audit.json");
        Files.writeString(target, "original reader contents");
        var output = new ByteArrayOutputStream();
        try (var reader = FileChannel.open(target, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            new AuditLog().writeDiagnostic(target, "SUCCESS", new PrintStream(output));
            assertEquals("original reader contents", Files.readString(target));
            var reports = alternates();
            assertEquals(1, reports.size());
            assertEquals("SUCCESS", JsonParser.parseString(Files.readString(reports.getFirst())).getAsJsonObject().get("outcome").getAsString());
            assertTrue(output.toString().contains(reports.getFirst().toString()));
        }
    }

    private List<Path> alternates() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> p.getFileName().toString().startsWith("audit.json.fallback-")).toList();
        }
    }
}
