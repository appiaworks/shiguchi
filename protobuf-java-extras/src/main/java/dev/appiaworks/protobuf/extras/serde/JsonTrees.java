package dev.appiaworks.protobuf.extras.serde;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;

/**
 * Reads a JSON value into plain Java — a {@code LinkedHashMap}, an {@code ArrayList}, a
 * {@code String}, a {@code Number}, a {@code Boolean} or null — with Jackson's streaming parser
 * alone, so the serde needs no {@code jackson-databind}.
 *
 * <p>The shapes are the ones {@code ObjectMapper.readValue(parser, Object.class)} gives: integers
 * as {@code Integer}, {@code Long} or {@code BigInteger} by size, fractions as {@code Double}, a
 * repeated key keeping its last value. Like it, a read starts at the parser's current token and
 * leaves the parser on the value's last token.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public final class JsonTrees {

    private JsonTrees() {}

    /** Reads the value the parser is positioned at. */
    public static Object read(JsonParser parser) {
        JsonToken token = parser.currentToken();
        if (token == null) throw new StreamReadException(parser, "No JSON value to read");
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> object = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.currentName();
                    parser.nextToken();
                    object.put(name, read(parser));
                }
                yield object;
            }
            case START_ARRAY -> {
                List<Object> array = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) array.add(read(parser));
                yield array;
            }
            case VALUE_STRING -> parser.getString();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getNumberValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new StreamReadException(parser, "Unexpected token " + token);
        };
    }

    /**
     * Reads the JSON object the parser is positioned at; null for JSON null.
     *
     * @throws StreamReadException when the value is anything else
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(JsonParser parser) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) return null;
        if (token != JsonToken.START_OBJECT) {
            throw new StreamReadException(parser, "Expected a JSON object, got " + token);
        }
        return (Map<String, Object>) read(parser);
    }
}
