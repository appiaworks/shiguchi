package dev.appiaworks.protobuf.extras;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.protobuf.extras.serde.JsonTrees;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.StrictScalars;
import java.io.IOException;

/**
 * What the {@link JsonWritable#writeJson} and {@link JsonReadable#mergeJson} methods
 * {@code protoc-gen-java-extras} generates call for what they cannot do inline: a message held
 * in a field, an enum value, a map key, the descriptors they read through.
 *
 * <p>The read side follows the strict rules of {@link dev.appiaworks.protobuf.extras.serde.StrictScalars},
 * which the compiled and the reflective parsers follow too, so a document means the same
 * whichever path reads it.
 *
 * <p>Generated code calls these; application code has no reason to.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class GeneratedJson {

    private GeneratedJson() {}

    /**
     * A message-typed field's value: as the single value its type maps to when the dialect in
     * force has a codec for it (or it is a well-known type), through its own
     * {@link JsonWritable#writeJson} when it has one, else reflectively.
     */
    public static void message(JsonGenerator gen, Message value) throws IOException {
        Descriptor type = value.getDescriptorForType();
        if (ProtoWkt.isWkt(type)) {
            ProtoWkt.writeJson(gen, value);
        } else if (value instanceof JsonWritable writable) {
            writable.writeJson(gen);
        } else {
            ProtoMapConverter.writeMessageJson(gen, ProtoTypes.dynamic(value, type));
        }
    }

    /**
     * An open enum's value: the constant's name, or the number when the schema the class was
     * built from does not know it ({@code forNumber} returned null), as {@code JsonFormat} does.
     */
    public static void enumValue(JsonGenerator gen, Enum<?> constant, int number) throws IOException {
        if (constant == null) {
            gen.writeNumber(number);
        } else {
            gen.writeString(constant.name());
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────────────────

    /** The message's fields in declaration order, as the generated reader indexes them. */
    public static FieldDescriptor[] fields(Descriptor type) {
        return type.getFields().toArray(new FieldDescriptor[0]);
    }

    /** For each field, the value field of its map entry type; null where the field is not a map. */
    public static FieldDescriptor[] mapValueFields(FieldDescriptor[] fields) {
        FieldDescriptor[] values = new FieldDescriptor[fields.length];
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isMapField()) values[i] = fields[i].getMessageType().findFieldByNumber(2);
        }
        return values;
    }

    /** Refuses a parser that is not on the start of an object, naming the message type. */
    public static void expectObject(JsonParser p, Descriptor type) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) throw StrictScalars.notAnObject(type, JsonTrees.read(p));
    }

    /** Refuses a parser that is not on the start of an array, for a repeated field. */
    public static void expectArray(JsonParser p, FieldDescriptor field) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY)
            throw StrictScalars.reject(field, JsonTrees.read(p), "takes a JSON array");
    }

    /** Refuses a parser that is not on the start of an object, for a map field. */
    public static void expectMap(JsonParser p, FieldDescriptor field) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT)
            throw StrictScalars.reject(field, JsonTrees.read(p), "takes a JSON object");
    }

    /** A map entry's key from the JSON object key, as the map's key type. */
    public static Object mapKey(FieldDescriptor mapField, String key) {
        return StrictScalars.mapKey(mapField.getMessageType().findFieldByNumber(1), key);
    }

    /**
     * A message-typed field's value, read from the parser into {@code target}: as the single value
     * its type maps to when the dialect in force has a codec for it (or it is a well-known type),
     * through the builder's own {@link JsonReadable#mergeJson} when it has one, else reflectively.
     * The parser is on the value's first token and is left on its last. JSON null is refused:
     * the generated code leaves a singular field unset before coming here, so a null here is an
     * element or a map value.
     */
    public static void message(JsonParser p, FieldDescriptor field, Message.Builder target) throws IOException {
        Descriptor type = field.getMessageType();
        if (ProtoWkt.isWkt(type)) {
            Object value = StrictScalars.read(p, field);
            if (value instanceof Message message) target.mergeFrom(message);
            return;
        }
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            throw StrictScalars.reject(field, null, "does not take null here");
        }
        if (p.currentToken() != JsonToken.START_OBJECT) {
            throw StrictScalars.reject(field, JsonTrees.read(p), "takes a JSON object");
        }
        if (target instanceof JsonReadable readable) {
            readable.mergeJson(p);
        } else {
            target.mergeFrom(StrictScalars.fill(JsonTrees.readObject(p), DynamicMessage.newBuilder(type)));
        }
    }
}
