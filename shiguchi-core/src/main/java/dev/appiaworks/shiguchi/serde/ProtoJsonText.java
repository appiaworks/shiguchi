package dev.appiaworks.shiguchi.serde;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code JsonFormat} output re-written in the gateway's one JSON text form, so a message prints
 * the same whichever path renders it.
 *
 * <p>The serde tiers write through Jackson, which leaves {@code <>&='} and U+2028/U+2029 as they
 * are. {@code JsonFormat} escapes them through Gson's HTML-safe mode ({@code <}), and its
 * {@code Printer} has no option to stop that. Every path that still prints with {@code JsonFormat}
 * ({@code Any}-reaching types, {@code response_body}, push payloads) passes its text through here.
 *
 * <p>A streaming token copy: numbers are copied as the text {@code JsonFormat} wrote,
 * which is already the tiers' form ({@code Double.toString}: {@code 1.0}, {@code 1.0E20}); only
 * string escapes change. Text without a backslash has no escape to change and is returned as is.
 */
public final class ProtoJsonText {

    private static final JsonFactory FACTORY = new JsonFactory();

    private ProtoJsonText() {}

    /** {@code printer.print(message)}, in the gateway's text form. */
    public static String print(JsonFormat.Printer printer, MessageOrBuilder message)
            throws InvalidProtocolBufferException {
        return normalize(printer.print(message));
    }

    /** {@link #print} as UTF-8 bytes. */
    public static byte[] printUtf8(JsonFormat.Printer printer, MessageOrBuilder message)
            throws InvalidProtocolBufferException {
        return print(printer, message).getBytes(StandardCharsets.UTF_8);
    }

    /** JSON text with its string escapes re-written the way the serde tiers write them. */
    public static String normalize(String json) {
        if (json == null || json.indexOf('\\') < 0) return json;
        StringWriter out = new StringWriter(json.length());
        try (JsonParser parser = FACTORY.createParser(json);
                JsonGenerator generator = FACTORY.createGenerator(out)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                // Numbers as their text: copyCurrentEventExact goes through BigDecimal (1.0E+20).
                if (token.isNumeric()) generator.writeNumber(parser.getText());
                else generator.copyCurrentEvent(parser);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("JsonFormat output is not JSON", e);
        }
        return out.toString();
    }
}
