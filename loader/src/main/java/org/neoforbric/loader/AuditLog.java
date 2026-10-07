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
    public synchronized void mode(String mode) { this.mode = Objects.requireNonNull(mode); }

    public synchronized void record(String phase, String type, String subject, Map<String, String> details) {
        events.add(new Event(events.size() + 1L, Instant.now().toString(), phase, type, subject, Map.copyOf(details)));
    }

    public synchronized List<Event> events() { return List.copyOf(events); }

    public void write(Path path, String outcome) throws IOException {
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
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
