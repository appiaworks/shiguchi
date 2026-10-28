package dev.appiaworks.shiguchi.cursus;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import dev.appiaworks.protobuf.extras.serde.JsonNumberFormat;
import dev.appiaworks.protobuf.extras.serde.JsonNumbers;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * JSON bytes to and from {@code google.protobuf.Value}, with Jackson's streaming parser and
 * generator: strict (one value, nothing trailing, no lenient literals), and integral numbers come
 * back as integers rather than {@code 1.0}, which is what a JSON client published and expects to
 * read. {@code Value} holds every number as a double, so integers beyond 2^53 lose precision on
 * the gRPC surface, as proto JSON's number mapping does.
 */
final class PushJson {

    private static final JsonFactory JSON = new JsonFactory();

    private PushJson() {}

    static Value toValue(byte[] json) throws IOException {
        try (JsonParser p = JSON.createParser(json)) {
            JsonToken first = p.nextToken();
            if (first == null) throw new IOException("empty");
            Value value = read(p, first);
            if (p.nextToken() != null) throw new IOException("more than one value");
            return value;
        }
    }

    private static Value read(JsonParser p, JsonToken token) throws IOException {
        switch (token) {
            case START_OBJECT -> {
                Struct.Builder s = Struct.newBuilder();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    s.putFields(name, read(p, p.nextToken()));
                }
                return Value.newBuilder().setStructValue(s).build();
            }
            case START_ARRAY -> {
                ListValue.Builder l = ListValue.newBuilder();
                JsonToken t;
                while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
                    if (t == null) throw new IOException("unterminated array");
                    l.addValues(read(p, t));
                }
                return Value.newBuilder().setListValue(l).build();
            }
            case VALUE_STRING -> {
                return Value.newBuilder().setStringValue(p.getText()).build();
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                return Value.newBuilder().setNumberValue(p.getDoubleValue()).build();
            }
            case VALUE_TRUE -> {
                return Value.newBuilder().setBoolValue(true).build();
            }
            case VALUE_FALSE -> {
                return Value.newBuilder().setBoolValue(false).build();
            }
            case VALUE_NULL -> {
                return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
            }
            default -> throw new IOException("unexpected " + token);
        }
    }

    /** The value as JSON, its numbers laid out as the global print options say. */
    static byte[] toJson(Value value) {
        return toJson(value, JsonPrintOptions.global().numberFormat());
    }

    static byte[] toJson(Value value, JsonNumberFormat numbers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        try (JsonGenerator g = JSON.createGenerator(out)) {
            write(g, value, numbers);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static void write(JsonGenerator g, Value v, JsonNumberFormat numbers) throws IOException {
        switch (v.getKindCase()) {
            case NUMBER_VALUE -> {
                double d = v.getNumberValue();
                if (d == Math.rint(d) && Math.abs(d) < 1e15) g.writeNumber((long) d);
                else JsonNumbers.writeDouble(g, d, numbers);
            }
            case STRING_VALUE -> g.writeString(v.getStringValue());
            case BOOL_VALUE -> g.writeBoolean(v.getBoolValue());
            case STRUCT_VALUE -> {
                g.writeStartObject();
                for (Map.Entry<String, Value> e :
                        v.getStructValue().getFieldsMap().entrySet()) {
                    g.writeFieldName(e.getKey());
                    write(g, e.getValue(), numbers);
                }
                g.writeEndObject();
            }
            case LIST_VALUE -> {
                g.writeStartArray();
                for (Value item : v.getListValue().getValuesList()) write(g, item, numbers);
                g.writeEndArray();
            }
            default -> g.writeNull();
        }
    }
}
