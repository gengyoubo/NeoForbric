package org.neoforbric.loader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.loader.impl.metadata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.minecraft.FabricAdmission;
import static org.junit.jupiter.api.Assertions.*;

class FabricJsonTest {
    @TempDir Path temporary;

    private Archive archive(String descriptor, String json) throws Exception {
        return Archive.read(TestJars.jar(temporary.resolve("metadata.jar"), Map.of(descriptor, TestJars.text(json))));
    }

    private LoaderModMetadata nativeMetadata(String json) throws Exception {
        return ModMetadataParser.parseMetadata(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                "test.jar", List.of(), new VersionOverrides(), new DependencyOverrides(temporary), false);
    }

    @Test void rawQuotedControlsMatchFabricAcrossBufferBoundariesWithoutChangingArchive() throws Exception {
        StringBuilder description = new StringBuilder("x".repeat(1030)).append("quoted \"text\" and \\path");
        for (char control = 0; control < 32; control++) description.append(control).append('x');
        String expected = description.toString();
        String json = "{\"schemaVersion\":1,\"id\":\"metadata_probe\",\"version\":\"1.0.0\",\"description\":\""
                + expected.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"custom\":{\"nested\":[true,null,2]},\"entrypoints\":{\"client\":[\"demo.Client\"]}}";
        Archive archive = archive("fabric.mod.json", json);
        String hash = archive.hash();
        assertEquals(expected, nativeMetadata(json).getDescription());
        Metadata metadata = Metadata.read(archive).getFirst();
        assertEquals(expected, metadata.description());
        assertEquals(expected, FabricJson.metadata(archive).get("description").getAsString());
        assertEquals(List.of("demo.Client"), FabricAdmission.entries(
                FabricAdmission.admit(new Discovery.Candidate(archive, metadata)), "client")
                .stream().map(FabricAdmission.Entry::className).toList());
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), archive.read("fabric.mod.json"));
        assertEquals(hash, Archive.read(archive.path()).hash());
    }

    @Test void duplicateFabricValuesUseTheSameLastValueAsNativeMetadataParser() throws Exception {
        String json = "{\"schemaVersion\":1,\"id\":\"metadata_probe\",\"version\":\"1.0.0\","
                + "\"description\":\"first\",\"description\":\"second\"}";
        assertEquals("second", nativeMetadata(json).getDescription());
        assertEquals("second", Metadata.read(archive("fabric.mod.json", json)).getFirst().description());
    }

    @Test void fabricLexerStillRejectsMalformedSyntaxWithoutLenientMode() throws Exception {
        for (String invalid : List.of(
                "{\"schemaVersion\":1,/* comment */\"id\":\"metadata_probe\",\"version\":\"1.0.0\"}",
                "{\"schemaVersion\":1,\"id\":\"metadata_probe\",\"version\":\"1.0.0\",}",
                "{\"schemaVersion\":1,'id':'metadata_probe',\"version\":\"1.0.0\"}",
                "{\"schemaVersion\":1,id:\"metadata_probe\",\"version\":\"1.0.0\"}",
                "{\"schemaVersion\":1,\"id\":\"metadata_probe\",\"version\":\"1.0.0\",\"description\":\"\\q\"}",
                "{\"schemaVersion\":1,\"id\":\"metadata_probe\",\"version\":\"1.0.0\",\"description\":\"unterminated}")) {
            assertThrows(ParseMetadataException.class, () -> nativeMetadata(invalid));
            Archive archive = archive("fabric.mod.json", invalid);
            assertEquals("METADATA_INVALID", assertThrows(Failure.class, () -> Metadata.read(archive)).code());
        }
    }

    @Test void prototypeDescriptorsKeepStrictJsonRules() throws Exception {
        for (String fields : List.of("\"description\":\"first\nsecond\"",
                "\"description\":\"first\",\"description\":\"second\"")) {
            Archive archive = archive("neoforbric.mod.json", "{\"schemaVersion\":1,\"id\":\"metadata_probe\","
                    + "\"version\":\"1.0.0\",\"entrypoint\":\"demo.Main\"," + fields + "}");
            assertEquals("METADATA_INVALID", assertThrows(Failure.class, () -> Metadata.read(archive)).code());
        }
    }
}
