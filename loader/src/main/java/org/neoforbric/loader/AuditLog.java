package org.neoforbric.loader;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class AuditLog {
    public record Event(long sequence, String time, String phase, String type, String subject,
                        Map<String, String> details) {}
    private final List<Event> events = new ArrayList<>();
    private String mode = "java-fixture";
    private volatile Path savedPath;
    public synchronized void mode(String mode) { this.mode = Objects.requireNonNull(mode); }

    public synchronized void record(String phase, String type, String subject, Map<String, String> details) {
        events.add(new Event(events.size() + 1L, Instant.now().toString(), phase, type, subject, Map.copyOf(details)));
    }

    public synchronized List<Event> events() { return List.copyOf(events); }
    public Optional<Path> savedPath() { return Optional.ofNullable(savedPath); }

    public void write(Path path, String outcome) throws IOException {
        savedPath = null;
        savedPath = write(path, outcome, false);
    }

    /** Runtime diagnostics must not take down a game when a reader locks the report on Windows. */
    public void writeDiagnostic(Path path, String outcome, java.io.PrintStream diagnostics) {
        savedPath = null;
        try {
            Path saved = write(path, outcome, true);
            savedPath = saved;
            if (!saved.equals(path.toAbsolutePath().normalize())) {
                record("AUDIT", "audit-write-warning", path.toString(), Map.of("severity", "RESOURCE_WARNING", "savedAs", saved.toString()));
                diagnostics.println("[NeoForbric] Audit target could not be replaced: " + path + "; saved report to " + saved);
            }
        } catch (IOException error) {
            record("AUDIT", "audit-write-warning", path.toString(), Map.of("severity", "RESOURCE_WARNING", "message", error.toString()));
            diagnostics.println("[NeoForbric] Could not save audit " + path + ": " + error + "; game lifecycle continues");
        }
    }

    private Path write(Path path, String outcome, boolean fallback) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("targetMinecraft", "1.21.1");
        report.put("mode", mode);
        report.put("outcome", outcome);
        report.put("events", events());
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".audit-", ".json");
        try {
            // Stream the report: a large launch audit can exceed the heap if serialized to one String first.
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(report, writer);
                writer.write("\n");
            }
            try {
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
                return absolute;
            } catch (FileSystemException error) {
                if (!fallback) throw error;
                // Keep the complete report and the original target; never truncate a file a reader owns.
                Path alternate = absolute.resolveSibling(absolute.getFileName() + ".fallback-" + UUID.randomUUID() + ".json");
                Files.move(temporary, alternate);
                return alternate;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
