package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes one generated message class as JSON through its typed getters: the Tier 1 writer.
 *
 * <p>{@link TypedWriters} compiles a subclass per generated class from the source
 * {@link TypedWriterSource} emits — {@code gen.writeString(msg.getName())} and friends, no
 * {@code getField} and no {@code DynamicMessage} — then wires each to the writers of the message
 * types its fields reach. A nested type without a writer of its own is written reflectively.
 *
 * <p>The output is the default layout of the dialect in force when the subclass was compiled: the
 * proto3 JSON mapping with the installed {@link ProtoJsonMapping} on top, the same JSON the Tier 0
 * transcoder and the Tier 2 serializers write for the dynamic form of the message.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public abstract class TypedMessageWriter {

    private final Map<String, TypedMessageWriter> nested = new HashMap<>();

    /** Writes the message as one JSON object. */
    public abstract void write(Message message, JsonGenerator gen) throws IOException;

    /** Wires the writer of a message type this writer's fields reach, by the type's full name. */
    public final void setNested(String typeFullName, TypedMessageWriter writer) {
        nested.put(typeFullName, writer);
        nestedBound(typeFullName, writer);
    }

    /** The writer wired for a type, or null. */
    public final TypedMessageWriter nestedWriter(String typeFullName) {
        return nested.get(typeFullName);
    }

    /**
     * Wiring hook: generated subclasses override it to store the writer in a per-field instance
     * field, so the hot path reads a field instead of a String-keyed map.
     */
    protected void nestedBound(String typeFullName, TypedMessageWriter writer) {}

    // ── Helpers the generated code calls ─────────────────────────────────────────────

    /** The enum value's name, or its number when the schema does not know it, as JsonFormat prints it. */
    protected static void writeEnum(JsonGenerator gen, FieldDescriptor fd, int number) throws IOException {
        EnumValueDescriptor value = fd.getEnumType().findValueByNumber(number);
        if (value != null) {
            gen.writeString(value.getName());
        } else {
            gen.writeNumber(number);
        }
    }

    /** A message field without a wired writer: through its dynamic form and the reflective writer. */
    protected static void writeReflectively(JsonGenerator gen, Message message) throws IOException {
        ProtoMapConverter.writeMessageJson(gen, ProtoTypes.dynamic(message, message.getDescriptorForType()));
    }

    /**
     * A map field as a JSON object, from the typed map the generated class exposes. Enum values
     * arrive as their numbers ({@code getXxxValueMap()}) where the class offers that, or as enum
     * constants; message values go to {@code valueWriter} when one is wired.
     */
    protected static void writeMap(
            JsonGenerator gen, FieldDescriptor mapField, Map<?, ?> entries, TypedMessageWriter valueWriter)
            throws IOException {
        FieldDescriptor keyFd = mapField.getMessageType().findFieldByNumber(1);
        FieldDescriptor valueFd = mapField.getMessageType().findFieldByNumber(2);
        gen.writeStartObject();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            gen.writeFieldName(ProtoMapConverter.mapKeyToString(keyFd, entry.getKey()));
            writeMapValue(gen, valueFd, entry.getValue(), valueWriter);
        }
        gen.writeEndObject();
    }

    private static void writeMapValue(
            JsonGenerator gen, FieldDescriptor valueFd, Object value, TypedMessageWriter valueWriter)
            throws IOException {
        switch (valueFd.getType()) {
            case STRING -> gen.writeString((String) value);
            case BYTES -> gen.writeString(Base64.getEncoder().encodeToString(((ByteString) value).toByteArray()));
            case INT32, SINT32, SFIXED32 -> gen.writeNumber((Integer) value);
            case UINT32, FIXED32 -> gen.writeNumber(Integer.toUnsignedLong((Integer) value));
            case INT64, SINT64, SFIXED64 -> ProtoMapConverter.writeInt64(gen, (Long) value);
            case UINT64, FIXED64 -> ProtoMapConverter.writeUInt64(gen, (Long) value);
            case FLOAT -> gen.writeNumber((Float) value);
            case DOUBLE -> gen.writeNumber((Double) value);
            case BOOL -> gen.writeBoolean((Boolean) value);
            case ENUM -> {
                int number = value instanceof Integer i ? i : ((ProtocolMessageEnum) value).getNumber();
                writeEnum(gen, valueFd, number);
            }
            case MESSAGE -> {
                Message message = (Message) value;
                if (valueWriter != null) {
                    valueWriter.write(message, gen);
                } else if (ProtoWkt.isWkt(message.getDescriptorForType())) {
                    ProtoWkt.writeJson(gen, message);
                } else {
                    writeReflectively(gen, message);
                }
            }
            default -> gen.writeString(String.valueOf(value));
        }
    }
}
