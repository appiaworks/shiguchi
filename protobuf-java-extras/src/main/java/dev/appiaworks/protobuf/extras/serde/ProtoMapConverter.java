package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 3 fallback: DynamicMessage ↔ Map&lt;String, Object&gt; without any JSON intermediary.
 *
 * Used by the GraphQL dispatch path when no Tier 2 serializer has been compiled
 * for the descriptor, and as a helper for Tier 2 generated code to handle nested MESSAGE
 * fields, proto map fields, and repeated lists without falling back to JsonFormat.
 *
 * Field name convention: uses fd.getJsonName() (camelCase) as the map key to match
 * the GraphQL schema field names produced by GraphQlSchemaBuilder.
 *
 * Default value behaviour mirrors JsonFormat.includingDefaultValueFields():
 *  - scalars without explicit presence: always present, even if zero/false/empty
 *  - fields with presence (message, proto3 optional, oneof members): omitted when unset
 *  - repeated and map fields: always present, even when empty
 *
 * Well-known types (Timestamp, Duration, wrappers, Struct, ...) are delegated to
 * {@link ProtoWkt} so the output matches proto3 JSON mapping rules.
 */
public final class ProtoMapConverter {

    private ProtoMapConverter() {}

    /**
     * Converts a DynamicMessage to its direct Java representation for the GraphQL (toMap) path.
     * Timestamp → OffsetDateTime so the DateTime scalar receives the native type, avoiding a
     * String format + re-parse round-trip.
     */
    public static Object convertMessageToJava(DynamicMessage msg) {
        if (ProtoWkt.isWkt(msg.getDescriptorForType())) {
            return ProtoWkt.toJavaValue(msg);
        }
        return dynamicToMap(msg);
    }

    /** Converts a proto Timestamp DynamicMessage to OffsetDateTime (UTC). */
    public static OffsetDateTime timestampToOffsetDateTime(DynamicMessage msg) {
        return ProtoWkt.toOffsetDateTime(msg);
    }

    /** For the JSON (serialize) path only: returns an RFC 3339 String matching JsonFormat output. */
    public static String timestampToIso8601(DynamicMessage msg) {
        return (String) ProtoWkt.toJsonValue(msg);
    }

    /**
     * Converts a DynamicMessage to a plain Map for the JSON-serialize and GraphQL Tier 3 paths.
     * Timestamp fields become RFC 3339 Strings so Jackson can serialise the result without
     * requiring JavaTimeModule.
     */
    public static Map<String, Object> dynamicToMap(DynamicMessage msg) {
        Descriptor descriptor = msg.getDescriptorForType();
        Map<String, Object> result = new LinkedHashMap<>(descriptor.getFields().size() * 2);
        for (FieldDescriptor fd : descriptor.getFields()) {
            if (fd.isMapField()) {
                result.put(fd.getJsonName(), mapFieldToJavaMap(fd, (List<?>) msg.getField(fd)));
            } else if (fd.isRepeated()) {
                result.put(fd.getJsonName(), repeatedToJava(fd, (List<?>) msg.getField(fd)));
            } else if (fd.hasPresence()) {
                if (msg.hasField(fd)) {
                    result.put(fd.getJsonName(), convertScalarToJava(fd, msg.getField(fd)));
                }
            } else {
                result.put(fd.getJsonName(), convertScalarToJava(fd, msg.getField(fd)));
            }
        }
        return result;
    }

    /**
     * Selection-aware variant of {@link #dynamicToMap} for the GraphQL response path.
     *
     * Scalar-typed fields (incl. enums, bytes, repeated scalars and WKT-scalar messages) are
     * always converted — they are cheap, and type resolvers or federation batch keys may read
     * them even when not selected. Composite fields — nested messages, repeated messages, and
     * map fields — are converted only when the projection selects them, pruning the expensive
     * parts of large response trees that the GraphQL query never asked for.
     */
    public static Map<String, Object> projectToMap(DynamicMessage msg, ProjectionNode projection) {
        if (projection == null || projection.isIncludeAll()) {
            return dynamicToMap(msg);
        }
        Descriptor descriptor = msg.getDescriptorForType();
        Map<String, Object> result = new LinkedHashMap<>(descriptor.getFields().size() * 2);
        for (FieldDescriptor fd : descriptor.getFields()) {
            String key = fd.getJsonName();
            if (fd.isMapField()) {
                if (projection.lookup(key) != null) {
                    result.put(key, mapFieldToJavaMap(fd, (List<?>) msg.getField(fd)));
                }
            } else if (fd.isRepeated()) {
                if (fd.getType() == FieldDescriptor.Type.MESSAGE && !ProtoWkt.isWkt(fd.getMessageType())) {
                    ProjectionNode child = projection.lookup(key);
                    if (child != null) {
                        List<?> list = (List<?>) msg.getField(fd);
                        List<Object> projected = new ArrayList<>(list.size());
                        for (Object item : list) {
                            projected.add(projectToMap((DynamicMessage) item, child));
                        }
                        result.put(key, projected);
                    }
                } else {
                    result.put(key, repeatedToJava(fd, (List<?>) msg.getField(fd)));
                }
            } else if (fd.getType() == FieldDescriptor.Type.MESSAGE && !ProtoWkt.isWkt(fd.getMessageType())) {
                ProjectionNode child = projection.lookup(key);
                if (child != null && msg.hasField(fd)) {
                    result.put(key, projectToMap((DynamicMessage) msg.getField(fd), child));
                }
            } else if (fd.hasPresence()) {
                if (msg.hasField(fd)) {
                    result.put(key, convertScalarToJava(fd, msg.getField(fd)));
                }
            } else {
                result.put(key, convertScalarToJava(fd, msg.getField(fd)));
            }
        }
        return result;
    }

    /** String/Number for WKTs (JSON-safe), Map for all other messages. */
    private static Object toJsonSafeValue(DynamicMessage msg) {
        if (ProtoWkt.isWkt(msg.getDescriptorForType())) {
            return ProtoWkt.toJsonValue(msg);
        }
        return dynamicToMap(msg);
    }

    @SuppressWarnings("unchecked")
    public static DynamicMessage dynamicFromMap(Map<String, Object> map, Descriptor descriptor) {
        if (ProtoJsonMapping.strictInput()) return StrictScalars.messageFromMap(map, descriptor);
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        for (FieldDescriptor fd : descriptor.getFields()) {
            Object value = map.getOrDefault(fd.getJsonName(), map.get(fd.getName()));
            if (value == null) continue;
            if (fd.isMapField()) {
                if (value instanceof Map<?, ?> m) {
                    mapFieldFromMap(fd, m, builder);
                }
            } else if (fd.isRepeated()) {
                if (value instanceof List<?> list) {
                    for (Object elem : list) {
                        if (elem == null) continue;
                        Object protoVal = convertSingleToProto(fd, elem);
                        if (protoVal != null) builder.addRepeatedField(fd, protoVal);
                    }
                }
            } else {
                Object protoVal = convertSingleToProto(fd, value);
                if (protoVal != null) builder.setField(fd, protoVal);
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Object convertSingleToProto(FieldDescriptor fd, Object value) {
        return switch (fd.getType()) {
            case MESSAGE -> {
                Descriptor md = fd.getMessageType();
                if (ProtoWkt.isWkt(md)) yield ProtoWkt.fromJava(value, md);
                if (value instanceof Map<?, ?> m) yield dynamicFromMap((Map<String, Object>) m, md);
                yield null;
            }
            case ENUM -> ProtoWkt.enumValue(fd, value);
            case INT32, SINT32, SFIXED32 -> toNumber(value).intValue();
            case UINT32, FIXED32 ->
                value instanceof String s
                        ? (int) Long.parseLong(s)
                        : toNumber(value).intValue();
            case INT64, SINT64, SFIXED64 -> toNumber(value).longValue();
            case UINT64, FIXED64 ->
                value instanceof String s
                        ? Long.parseUnsignedLong(s)
                        : toNumber(value).longValue();
            case FLOAT -> toNumber(value).floatValue();
            case DOUBLE -> toNumber(value).doubleValue();
            case BOOL -> coerceBool(value);
            case BYTES ->
                value instanceof String s
                        ? ByteString.copyFrom(Base64.getDecoder().decode(s))
                        : value;
            default -> value;
        };
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number n) return n;
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
            return Double.parseDouble(s);
        }
        throw new IllegalArgumentException("Cannot convert to number: " + value.getClass());
    }

    /** Coerces Number or numeric String to long; returns 0 if null or unparseable. Used by Tier 2 generated code. */
    public static long coerceLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    /** Coerces Boolean or "true"/"false" String to Boolean; returns null otherwise. Used by Tier 2 generated code. */
    public static Boolean coerceBool(Object v) {
        if (v instanceof Boolean b) return b;
        if ("true".equals(v)) return Boolean.TRUE;
        if ("false".equals(v)) return Boolean.FALSE;
        return null;
    }

    /** Coerces Number or numeric String to Float; returns null otherwise. Used by Tier 2 generated code. */
    public static Float coerceFloat(Object v) {
        if (v instanceof Number n) return n.floatValue();
        if (v instanceof String s) {
            try {
                return Float.parseFloat(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** Coerces Number or numeric String to Double; returns null otherwise. Used by Tier 2 generated code. */
    public static Double coerceDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** Coerces Number or unsigned-decimal String to the uint64 bit pattern; returns null otherwise. */
    public static Long coerceULong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseUnsignedLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** Coerces Number or unsigned-decimal String to the uint32 bit pattern; returns null otherwise. */
    public static Integer coerceUInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseUnsignedInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** Coerces Number or numeric String to int; returns 0 if null or unparseable. Used by Tier 2 generated code. */
    public static int coerceInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Parses an RFC 3339 / ISO-8601 string into a Timestamp DynamicMessage.
     * Used by Tier 2 generated code for Timestamp input fields that arrive as DateTime strings.
     */
    public static DynamicMessage timestampFromIso8601(String iso, Descriptor tsDescriptor) {
        DynamicMessage ts = ProtoWkt.fromJava(iso, tsDescriptor);
        if (ts == null) throw new IllegalArgumentException("Invalid RFC 3339 timestamp: " + iso);
        return ts;
    }

    // ── Proto map<K,V> field support ─────────────────────────────────────────────────
    //
    // Proto map fields are stored as repeated MapEntry messages (key = field 1, value =
    // field 2). Their JSON form is a plain object with stringified keys, matching JsonFormat.

    /** Converts a map field's entry list to a LinkedHashMap with JSON string keys. */
    public static Map<String, Object> mapFieldToJavaMap(FieldDescriptor mapFd, List<?> entries) {
        Descriptor entryDesc = mapFd.getMessageType();
        FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
        FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
        Map<String, Object> result = new LinkedHashMap<>(entries.size() * 2);
        for (Object entryObj : entries) {
            DynamicMessage entry = (DynamicMessage) entryObj;
            result.put(mapKeyToString(keyFd, entry.getField(keyFd)), convertScalarToJava(valFd, entry.getField(valFd)));
        }
        return result;
    }

    /** Streams a map field as a JSON object. Used by Tier 2 generated serialize(). */
    public static void writeMapField(FieldDescriptor mapFd, List<?> entries, JsonGenerator gen) throws IOException {
        writeMapField(mapFd, entries, gen, JsonPrintOptions.DEFAULT);
    }

    /** {@link #writeMapField(FieldDescriptor, List, JsonGenerator)} with a service's print options. */
    public static void writeMapField(
            FieldDescriptor mapFd, List<?> entries, JsonGenerator gen, JsonPrintOptions options) throws IOException {
        Descriptor entryDesc = mapFd.getMessageType();
        FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
        FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
        gen.writeStartObject();
        for (Object entryObj : entries) {
            DynamicMessage entry = (DynamicMessage) entryObj;
            gen.writeFieldName(mapKeyToString(keyFd, entry.getField(keyFd)));
            writeScalarJson(gen, valFd, entry.getField(valFd), options);
        }
        gen.writeEndObject();
    }

    /** Fills a map field on the builder from a Java Map. Used by Tier 2 generated code. */
    public static void mapFieldFromMap(FieldDescriptor mapFd, Map<?, ?> map, DynamicMessage.Builder b) {
        Descriptor entryDesc = mapFd.getMessageType();
        FieldDescriptor keyFd = entryDesc.findFieldByNumber(1);
        FieldDescriptor valFd = entryDesc.findFieldByNumber(2);
        for (var e : map.entrySet()) {
            Object key = mapKeyFromString(keyFd, String.valueOf(e.getKey()));
            Object val = e.getValue() == null ? null : convertSingleToProto(valFd, e.getValue());
            if (key == null || val == null) continue;
            b.addRepeatedField(
                    mapFd,
                    DynamicMessage.newBuilder(entryDesc)
                            .setField(keyFd, key)
                            .setField(valFd, val)
                            .build());
        }
    }

    /** A map key as JSON object-key text: unsigned kinds print unsigned, the rest as {@code String.valueOf}. */
    static String mapKeyToString(FieldDescriptor keyFd, Object key) {
        return switch (keyFd.getType()) {
            case UINT32, FIXED32 -> Long.toString(Integer.toUnsignedLong((Integer) key));
            case UINT64, FIXED64 -> Long.toUnsignedString((Long) key);
            default -> String.valueOf(key);
        };
    }

    private static Object mapKeyFromString(FieldDescriptor keyFd, String key) {
        try {
            return switch (keyFd.getType()) {
                case STRING -> key;
                case BOOL -> Boolean.parseBoolean(key);
                case INT32, SINT32, SFIXED32 -> Integer.parseInt(key);
                case UINT32, FIXED32 -> Integer.parseUnsignedInt(key);
                case INT64, SINT64, SFIXED64 -> Long.parseLong(key);
                case UINT64, FIXED64 -> Long.parseUnsignedLong(key);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Repeated + scalar conversion helpers ─────────────────────────────────────────

    /** Converts a repeated field's list to JSON-safe Java values. Used by Tier 2 generated toMap(). */
    public static List<Object> repeatedToJava(FieldDescriptor fd, List<?> list) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) result.add(convertScalarToJava(fd, item));
        return result;
    }

    private static Object convertScalarToJava(FieldDescriptor fd, Object value) {
        return switch (fd.getType()) {
            case MESSAGE -> toJsonSafeValue((DynamicMessage) value);
            case ENUM -> enumToJava((EnumValueDescriptor) value);
            case BYTES -> Base64.getEncoder().encodeToString(((ByteString) value).toByteArray());
            default -> value;
        };
    }

    /**
     * Writes a signed 64-bit integer as {@code shiguchi.json.int64-as} says: a string (the
     * proto3 form) or a number. Public because Tier 0 and Tier 2 generated code call it.
     */
    /**
     * An enum value as proto JSON writes it: its name, or its number when the schema does not know
     * it (an open enum's unknown value, which protobuf represents with a synthetic descriptor), as
     * {@code JsonFormat} and the typed writers do.
     */
    public static void writeEnum(JsonGenerator gen, EnumValueDescriptor value) throws IOException {
        if (value.getIndex() < 0) {
            gen.writeNumber(value.getNumber());
        } else {
            gen.writeString(value.getName());
        }
    }

    /** {@link #writeEnum} as a tree value: the name as a String, or the unknown number as an Integer. */
    public static Object enumToJava(EnumValueDescriptor value) {
        return value.getIndex() < 0 ? (Object) value.getNumber() : value.getName();
    }

    public static void writeInt64(JsonGenerator gen, long value) throws IOException {
        if (ProtoJsonMapping.current().int64AsNumber()) {
            gen.writeNumber(value);
        } else {
            gen.writeString(Long.toString(value));
        }
    }

    /** {@link #writeInt64} for an unsigned 64-bit integer held in a long's bit pattern. */
    public static void writeUInt64(JsonGenerator gen, long value) throws IOException {
        if (ProtoJsonMapping.current().int64AsNumber()) {
            gen.writeNumber(
                    value >= 0
                            ? java.math.BigInteger.valueOf(value)
                            : new java.math.BigInteger(Long.toUnsignedString(value)));
        } else {
            gen.writeString(Long.toUnsignedString(value));
        }
    }

    /** Writes one scalar/message value with proto3 JSON rules (int64 → string, etc.). */
    private static void writeScalarJson(JsonGenerator gen, FieldDescriptor fd, Object value, JsonPrintOptions options)
            throws IOException {
        switch (fd.getType()) {
            case MESSAGE -> {
                DynamicMessage m = (DynamicMessage) value;
                if (ProtoWkt.isWkt(m.getDescriptorForType())) {
                    ProtoWkt.writeJson(gen, m);
                } else {
                    writeMessageJson(gen, m, options);
                }
            }
            case ENUM -> {
                EnumValueDescriptor e = (EnumValueDescriptor) value;
                if (options.enumsAsInts()) {
                    gen.writeNumber(e.getNumber());
                } else {
                    writeEnum(gen, e);
                }
            }
            case BYTES -> gen.writeString(Base64.getEncoder().encodeToString(((ByteString) value).toByteArray()));
            case STRING -> gen.writeString((String) value);
            case INT32, SINT32, SFIXED32 -> gen.writeNumber((Integer) value);
            case UINT32, FIXED32 -> gen.writeNumber(Integer.toUnsignedLong((Integer) value));
            case INT64, SINT64, SFIXED64 -> writeInt64(gen, (Long) value);
            case UINT64, FIXED64 -> writeUInt64(gen, (Long) value);
            case FLOAT -> gen.writeNumber((Float) value);
            case DOUBLE -> gen.writeNumber((Double) value);
            case BOOL -> gen.writeBoolean((Boolean) value);
            default -> gen.writeString(String.valueOf(value));
        }
    }

    /**
     * Streams an arbitrary DynamicMessage as a proto3 JSON object (int64 → string, unsigned
     * handling, WKT rules, map fields as objects). Used as the fallback when no Tier 2
     * serializer exists for a nested type, and by map fields with message values. Mirrors
     * JsonFormat.printer().includingDefaultValueFields() output.
     */
    public static void writeMessageJson(JsonGenerator gen, DynamicMessage msg) throws IOException {
        writeMessageJson(gen, msg, JsonPrintOptions.DEFAULT);
    }

    /**
     * {@link #writeMessageJson(JsonGenerator, DynamicMessage)} with a service's print options —
     * the writer for every service that changes them, so it must match {@code JsonFormat} with
     * the same options wherever {@code JsonFormat} applies (see JsonPrintOptionsParityTest).
     */
    public static void writeMessageJson(JsonGenerator gen, DynamicMessage msg, JsonPrintOptions options)
            throws IOException {
        Descriptor descriptor = msg.getDescriptorForType();
        boolean omitDefaults = options.omitDefaultValues();
        gen.writeStartObject();
        for (FieldDescriptor fd : descriptor.getFields()) {
            if (fd.isMapField()) {
                List<?> entries = (List<?>) msg.getField(fd);
                if (omitDefaults && entries.isEmpty()) continue;
                gen.writeFieldName(options.fieldName(fd));
                writeMapField(fd, entries, gen, options);
            } else if (fd.isRepeated()) {
                List<?> items = (List<?>) msg.getField(fd);
                if (omitDefaults && items.isEmpty()) continue;
                gen.writeFieldName(options.fieldName(fd));
                gen.writeStartArray();
                for (Object item : items) {
                    writeScalarJson(gen, fd, item, options);
                }
                gen.writeEndArray();
            } else if (fd.hasPresence()) {
                if (msg.hasField(fd)) {
                    gen.writeFieldName(options.fieldName(fd));
                    writeScalarJson(gen, fd, msg.getField(fd), options);
                }
            } else {
                // Without presence, hasField is false exactly when the field holds its default.
                if (omitDefaults && !msg.hasField(fd)) continue;
                gen.writeFieldName(options.fieldName(fd));
                writeScalarJson(gen, fd, msg.getField(fd), options);
            }
        }
        gen.writeEndObject();
    }
}
