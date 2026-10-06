package org.neoforbric.loader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Discovery {
    public record Candidate(Archive archive, Metadata metadata) {}
    private Discovery() {}

    public static List<Candidate> discover(Path directory, AuditLog audit) throws IOException {
        if (!Files.isDirectory(directory)) throw new Failure("MOD_DIRECTORY", "Missing mod directory: " + directory);
        List<Candidate> result = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                Archive archive = Archive.read(path);
                for (Metadata metadata : Metadata.read(archive)) {
                    audit.record("DISCOVER", "mod-discovered", metadata.id(), Map.of("source", archive.path().toString(), "sha256", archive.hash(),
                            "ecosystem", metadata.ecosystem().name(), "descriptor", Metadata.descriptor(archive), "version", metadata.version(), "executionSupported", Boolean.toString(metadata.ecosystem() == Metadata.Ecosystem.PROTOTYPE)));
                    result.add(new Candidate(archive, metadata));
                }
            }
        }
        return List.copyOf(result);
    }
}
