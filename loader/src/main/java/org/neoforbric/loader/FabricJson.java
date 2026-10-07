package org.neoforbric.loader;

import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;

/** Fabric descriptors use Fabric's actual lexer, without enabling lenient mode. */
public final class FabricJson {
    private FabricJson() {}

    public static JsonObject metadata(Archive archive) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(archive.read("fabric.mod.json"))).toString();
            return object(text);
        } catch (Failure known) { throw known; }
        catch (IOException | RuntimeException invalid) {
            throw new Failure("METADATA_INVALID", archive.path() + ": " + invalid.getMessage(), invalid);
        }
    }

    static JsonObject object(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            JsonElement value = value(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new Failure("METADATA_INVALID", "Expected one JSON object");
            return value.getAsJsonObject();
        }
    }

    private static JsonElement value(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new Failure("METADATA_INVALID", "Metadata nesting exceeds 64 levels");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                // Fabric's metadata parser explicitly uses last-value-wins for duplicate keys.
                while (reader.hasNext()) object.add(reader.nextName(), value(reader, depth + 1));
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(value(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new Failure("METADATA_INVALID", "Unexpected JSON token " + reader.peek());
        };
    }
}
