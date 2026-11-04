package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.protobuf.extras.serde.JsonFactories;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.PrettyPrinter;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.util.DefaultPrettyPrinter;

/**
 * Re-writes a JSON body indented ({@code json.print.pretty-print}), token by token.
 *
 * <p>A streaming copy rather than a parse into a tree: numbers are copied exactly as written
 * ({@code copyCurrentEventExact}), so a large {@code int64} printed as a number or a precise
 * decimal is not rounded through a {@code double}, and nothing is reordered.
 */
final class PrettyJson {

    private static final JsonFactory FACTORY = JsonFactories.create();

    /** Jackson 3 takes the pretty printer from the write context; this one asks for the default layout. */
    private static final ObjectWriteContext PRETTY = new ObjectWriteContext.Base() {
        @Override
        public PrettyPrinter getPrettyPrinter() {
            return new DefaultPrettyPrinter();
        }

        @Override
        public boolean hasPrettyPrinter() {
            return true;
        }
    };

    private PrettyJson() {}

    static byte[] indent(byte[] json) {
        if (json == null || json.length == 0) return json;
        ByteArrayOutputStream out = new ByteArrayOutputStream(json.length + json.length / 2);
        try (JsonParser parser = FACTORY.createParser(json);
                JsonGenerator generator = FACTORY.createGenerator(PRETTY, out)) {
            while (parser.nextToken() != null) {
                generator.copyCurrentEventExact(parser);
            }
        } catch (JacksonException e) {
            throw new UncheckedIOException("Response body is not JSON", new IOException(e));
        }
        return out.toByteArray();
    }
}
