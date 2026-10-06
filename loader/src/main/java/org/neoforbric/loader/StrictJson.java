package org.neoforbric.loader;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;

/** Reject ambiguous duplicate keys as well as non-JSON syntax. */
final class StrictJson {
    private StrictJson() {}
    static JsonObject object(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
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
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new Failure("METADATA_INVALID", "Duplicate JSON key " + key);
                    object.add(key, value(reader, depth + 1));
                }
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
