package dev.appiaworks.shiguchi.gateway;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Re-writes a JSON body indented ({@code json.print.pretty-print}), token by token.
 *
 * <p>A streaming copy rather than a parse into a tree: numbers are copied exactly as written
 * ({@code copyCurrentEventExact}), so a large {@code int64} printed as a number or a precise
 * decimal is not rounded through a {@code double}, and nothing is reordered.
 */
final class PrettyJson {

    private static final JsonFactory FACTORY = new JsonFactory();

    private PrettyJson() {}

    static byte[] indent(byte[] json) {
        if (json == null || json.length == 0) return json;
        ByteArrayOutputStream out = new ByteArrayOutputStream(json.length + json.length / 2);
        try (JsonParser parser = FACTORY.createParser(json);
                JsonGenerator generator = FACTORY.createGenerator(out).useDefaultPrettyPrinter()) {
            while (parser.nextToken() != null) {
                generator.copyCurrentEventExact(parser);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Response body is not JSON", e);
        }
        return out.toByteArray();
    }
}
