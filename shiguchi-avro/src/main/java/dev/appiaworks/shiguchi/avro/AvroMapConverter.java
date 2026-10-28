package dev.appiaworks.shiguchi.avro;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericEnumSymbol;
import org.apache.avro.generic.GenericFixed;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;

/**
 * Avro generic datums ⇄ the plain Java shape the gateway's Map path uses, the counterpart of
 * {@code ProtoMapConverter} for Avro. Decoded records become {@code LinkedHashMap}s in field
 * order, arrays {@code List}s, maps {@code Map}s with String keys; strings and enum symbols become
 * {@code String}, bytes and fixed base64 strings, and temporal logical types ISO-8601 strings, the
 * same text the proto JSON mapping produces for the equivalent field. Null-valued fields and null
 * union branches are omitted, matching an unset proto field.
 *
 * <p>Encoding is lenient the way the REST surface is: numbers arrive as any {@code Number} or a
 * numeric string (GraphQL hands 64-bit integers as strings), enums as their symbol, bytes as
 * base64, temporal logical types as ISO-8601 text or the raw epoch number, decimals as text or a
 * number. A field absent from the input takes the schema default when there is one, null when the
 * field is nullable, and fails otherwise.
 */
public final class AvroMapConverter {

    private AvroMapConverter() {}

    // ---------------------------------------------------------------- decode

    /** Converts a decoded datum of {@code schema} to plain Java. */
    public static Object toJava(Object datum, Schema schema) {
        if (datum == null) return null;
        switch (schema.getType()) {
            case RECORD: {
                GenericRecord record = (GenericRecord) datum;
                Map<String, Object> out = new LinkedHashMap<>();
                for (Schema.Field f : schema.getFields()) {
                    Object v = toJava(record.get(f.pos()), f.schema());
                    if (v != null) out.put(f.name(), v);
                }
                return out;
            }
            case ARRAY: {
                List<Object> out = new ArrayList<>();
                for (Object item : (Collection<?>) datum) {
                    Object v = toJava(item, schema.getElementType());
                    if (v != null) out.add(v);
                }
                return out;
            }
            case MAP: {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) datum).entrySet()) {
                    Object v = toJava(e.getValue(), schema.getValueType());
                    if (v != null) out.put(String.valueOf(e.getKey()), v);
                }
                return out;
            }
            case UNION: {
                int index = GenericData.get().resolveUnion(schema, datum);
                return toJava(datum, schema.getTypes().get(index));
            }
            case ENUM:
                return datum instanceof GenericEnumSymbol<?> s ? s.toString() : String.valueOf(datum);
            case STRING:
                return datum instanceof Utf8 u ? u.toString() : String.valueOf(datum);
            case FIXED: {
                byte[] bytes = ((GenericFixed) datum).bytes();
                LogicalType lt = schema.getLogicalType();
                if (lt instanceof LogicalTypes.Decimal d)
                    return new BigDecimal(new BigInteger(bytes), d.getScale()).toPlainString();
                return Base64.getEncoder().encodeToString(bytes);
            }
            case BYTES: {
                ByteBuffer buf = ((ByteBuffer) datum).duplicate();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                LogicalType lt = schema.getLogicalType();
                if (lt instanceof LogicalTypes.Decimal d)
                    return new BigDecimal(new BigInteger(bytes), d.getScale()).toPlainString();
                return Base64.getEncoder().encodeToString(bytes);
            }
            case INT: {
                int i = ((Number) datum).intValue();
                LogicalType lt = schema.getLogicalType();
                if (lt == null) return i;
                return switch (lt.getName()) {
                    case "date" -> LocalDate.ofEpochDay(i).toString();
                    case "time-millis" ->
                        LocalTime.ofNanoOfDay(TimeUnit.MILLISECONDS.toNanos(i)).toString();
                    default -> i;
                };
            }
            case LONG: {
                long l = ((Number) datum).longValue();
                LogicalType lt = schema.getLogicalType();
                if (lt == null) return l;
                return switch (lt.getName()) {
                    case "timestamp-millis" -> Instant.ofEpochMilli(l).toString();
                    case "timestamp-micros" ->
                        Instant.ofEpochSecond(Math.floorDiv(l, 1_000_000L), Math.floorMod(l, 1_000_000L) * 1_000L)
                                .toString();
                    case "timestamp-nanos" ->
                        Instant.ofEpochSecond(Math.floorDiv(l, 1_000_000_000L), Math.floorMod(l, 1_000_000_000L))
                                .toString();
                    case "local-timestamp-millis" ->
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(l), ZoneOffset.UTC)
                                .toString();
                    case "local-timestamp-micros" ->
                        LocalDateTime.ofInstant(
                                        Instant.ofEpochSecond(
                                                Math.floorDiv(l, 1_000_000L), Math.floorMod(l, 1_000_000L) * 1_000L),
                                        ZoneOffset.UTC)
                                .toString();
                    case "time-micros" ->
                        LocalTime.ofNanoOfDay(TimeUnit.MICROSECONDS.toNanos(l)).toString();
                    default -> l;
                };
            }
            case FLOAT:
                return ((Number) datum).floatValue();
            case DOUBLE:
                return ((Number) datum).doubleValue();
            case BOOLEAN:
                return datum;
            case NULL:
                return null;
            default:
                throw new IllegalArgumentException("Unsupported Avro type " + schema.getType());
        }
    }

    // ---------------------------------------------------------------- encode

    /** Converts plain Java to a generic datum of {@code schema}. */
    public static Object fromJava(Object value, Schema schema) {
        return fromJava(value, schema, "$");
    }

    private static Object fromJava(Object value, Schema schema, String path) {
        switch (schema.getType()) {
            case NULL:
                if (value != null) throw bad(path, "null", value);
                return null;
            case UNION:
                return unionFromJava(value, schema, path);
            case RECORD: {
                if (!(value instanceof Map<?, ?> map)) throw bad(path, "record " + schema.getFullName(), value);
                GenericData.Record record = new GenericData.Record(schema);
                for (Schema.Field f : schema.getFields()) {
                    Object raw = map.get(f.name());
                    if (raw == null) {
                        if (f.hasDefaultValue()) {
                            record.put(f.pos(), GenericData.get().getDefaultValue(f));
                        } else if (isNullable(f.schema())) {
                            record.put(f.pos(), null);
                        } else {
                            throw new IllegalArgumentException(
                                    path + "." + f.name() + " is required by " + schema.getFullName() + " and missing");
                        }
                        continue;
                    }
                    record.put(f.pos(), fromJava(raw, f.schema(), path + "." + f.name()));
                }
                return record;
            }
            case ARRAY: {
                Collection<?> items = value instanceof Collection<?> c ? c : List.of(value);
                List<Object> out = new ArrayList<>(items.size());
                int i = 0;
                for (Object item : items) out.add(fromJava(item, schema.getElementType(), path + "[" + i++ + "]"));
                return out;
            }
            case MAP: {
                if (!(value instanceof Map<?, ?> map)) throw bad(path, "map", value);
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    out.put(key, fromJava(e.getValue(), schema.getValueType(), path + "." + key));
                }
                return out;
            }
            case ENUM: {
                String symbol = String.valueOf(value);
                if (!schema.hasEnumSymbol(symbol)) {
                    throw new IllegalArgumentException(path + ": '" + symbol + "' is not a symbol of "
                            + schema.getFullName() + " " + schema.getEnumSymbols());
                }
                return new GenericData.EnumSymbol(schema, symbol);
            }
            case STRING:
                if (value instanceof Map<?, ?> || value instanceof Collection<?>) return jsonText(value, path);
                if (value instanceof OffsetDateTime odt) return odt.toInstant().toString();
                return value instanceof CharSequence ? value.toString() : String.valueOf(value);
            case FIXED: {
                byte[] bytes = bytesFromJava(value, schema, path);
                if (bytes.length != schema.getFixedSize()) {
                    throw new IllegalArgumentException(path + ": " + schema.getFullName() + " needs "
                            + schema.getFixedSize() + " bytes, got " + bytes.length);
                }
                return new GenericData.Fixed(schema, bytes);
            }
            case BYTES:
                return ByteBuffer.wrap(bytesFromJava(value, schema, path));
            case INT:
                return intFromJava(value, schema, path);
            case LONG:
                return longFromJava(value, schema, path);
            case FLOAT:
                return value instanceof Number n
                        ? n.floatValue()
                        : parseNumber(value, path).floatValue();
            case DOUBLE:
                return value instanceof Number n
                        ? n.doubleValue()
                        : parseNumber(value, path).doubleValue();
            case BOOLEAN:
                if (value instanceof Boolean b) return b;
                String s = String.valueOf(value).trim();
                if (s.equalsIgnoreCase("true")) return true;
                if (s.equalsIgnoreCase("false")) return false;
                throw bad(path, "boolean", value);
            default:
                throw new IllegalArgumentException("Unsupported Avro type " + schema.getType());
        }
    }

    private static Object unionFromJava(Object value, Schema union, String path) {
        List<Schema> branches = union.getTypes();
        if (value == null) {
            for (Schema b : branches) if (b.getType() == Schema.Type.NULL) return null;
            throw new IllegalArgumentException(path + " is null but " + union + " has no null branch");
        }
        // First branch whose type matches the Java value; nested unions are not legal Avro.
        IllegalArgumentException last = null;
        for (Schema b : branches) {
            if (b.getType() == Schema.Type.NULL) continue;
            if (!plausible(value, b)) continue;
            try {
                return fromJava(value, b, path);
            } catch (IllegalArgumentException e) {
                last = e;
            }
        }
        throw last != null ? last : bad(path, "one of " + branches, value);
    }

    /** Cheap type-shape check so a union picks the intended branch instead of the first that parses. */
    private static boolean plausible(Object value, Schema branch) {
        return switch (branch.getType()) {
            case RECORD, MAP -> value instanceof Map;
            case ARRAY -> value instanceof Collection;
            case BOOLEAN -> value instanceof Boolean || "true".equals(value) || "false".equals(value);
            case INT, LONG, FLOAT, DOUBLE ->
                value instanceof Number
                        || value instanceof TemporalAccessor
                        || (value instanceof String s && (branch.getLogicalType() != null || isNumeric(s)));
            case STRING, ENUM, BYTES, FIXED -> value instanceof CharSequence || value instanceof Number;
            default -> true;
        };
    }

    private static boolean isNumeric(String s) {
        try {
            new BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isNullable(Schema schema) {
        if (schema.getType() == Schema.Type.NULL) return true;
        if (schema.getType() != Schema.Type.UNION) return false;
        for (Schema b : schema.getTypes()) if (b.getType() == Schema.Type.NULL) return true;
        return false;
    }

    private static byte[] bytesFromJava(Object value, Schema schema, String path) {
        LogicalType lt = schema.getLogicalType();
        if (lt instanceof LogicalTypes.Decimal d) {
            BigDecimal decimal = value instanceof BigDecimal bd
                    ? bd
                    : value instanceof Number n
                            ? new BigDecimal(n.toString())
                            : new BigDecimal(String.valueOf(value).trim());
            BigDecimal scaled = decimal.setScale(d.getScale(), java.math.RoundingMode.UNNECESSARY);
            return scaled.unscaledValue().toByteArray();
        }
        if (value instanceof byte[] b) return b;
        if (value instanceof ByteBuffer buf) {
            byte[] out = new byte[buf.remaining()];
            buf.duplicate().get(out);
            return out;
        }
        try {
            return Base64.getDecoder().decode(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw bad(path, "base64 bytes", value);
        }
    }

    private static Object intFromJava(Object value, Schema schema, String path) {
        LogicalType lt = schema.getLogicalType();
        // The gateway's Map path hands temporal values as java.time objects (Timestamp → OffsetDateTime).
        if (value instanceof LocalDate d) return (int) d.toEpochDay();
        if (value instanceof LocalTime t) return (int) TimeUnit.NANOSECONDS.toMillis(t.toNanoOfDay());
        if (lt != null && value instanceof CharSequence text) {
            String s = text.toString().trim();
            try {
                switch (lt.getName()) {
                    case "date":
                        return (int) LocalDate.parse(s).toEpochDay();
                    case "time-millis":
                        return (int)
                                TimeUnit.NANOSECONDS.toMillis(LocalTime.parse(s).toNanoOfDay());
                    default:
                        break;
                }
            } catch (DateTimeParseException e) {
                if (!isNumeric(s)) throw bad(path, lt.getName(), value);
            }
        }
        if (value instanceof Number n) return n.intValue();
        return parseNumber(value, path).intValue();
    }

    private static Object longFromJava(Object value, Schema schema, String path) {
        LogicalType lt = schema.getLogicalType();
        if (value instanceof OffsetDateTime odt) value = odt.toInstant();
        if (value instanceof LocalDateTime ldt && lt != null && lt.getName().startsWith("timestamp")) {
            value = ldt.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Instant i) {
            String name = lt != null ? lt.getName() : "timestamp-millis";
            return switch (name) {
                case "timestamp-micros", "local-timestamp-micros" ->
                    Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1_000L);
                case "timestamp-nanos" ->
                    Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
                default -> i.toEpochMilli();
            };
        }
        if (value instanceof LocalDateTime ldt) {
            Instant i = ldt.toInstant(ZoneOffset.UTC);
            return lt != null && lt.getName().endsWith("micros")
                    ? Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1_000L)
                    : i.toEpochMilli();
        }
        if (value instanceof LocalTime t) return TimeUnit.NANOSECONDS.toMicros(t.toNanoOfDay());
        if (lt != null && value instanceof CharSequence text) {
            String s = text.toString().trim();
            try {
                switch (lt.getName()) {
                    case "timestamp-millis":
                        return Instant.parse(s).toEpochMilli();
                    case "timestamp-micros": {
                        Instant i = Instant.parse(s);
                        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1_000L);
                    }
                    case "timestamp-nanos": {
                        Instant i = Instant.parse(s);
                        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
                    }
                    case "local-timestamp-millis":
                        return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
                    case "local-timestamp-micros": {
                        Instant i = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
                        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1_000L);
                    }
                    case "time-micros":
                        return TimeUnit.NANOSECONDS.toMicros(LocalTime.parse(s).toNanoOfDay());
                    default:
                        break;
                }
            } catch (DateTimeParseException e) {
                if (!isNumeric(s)) throw bad(path, lt.getName(), value);
            }
        }
        if (value instanceof Number n) return n.longValue();
        return parseNumber(value, path).longValue();
    }

    private static BigDecimal parseNumber(Object value, String path) {
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw bad(path, "number", value);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** A Struct / Value / ListValue / Any lands on an Avro string as its JSON text. */
    private static String jsonText(Object value, String path) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw bad(path, "JSON-serialisable value", value);
        }
    }

    private static IllegalArgumentException bad(String path, String expected, Object value) {
        return new IllegalArgumentException(path + ": expected " + expected + ", got "
                + (value == null ? "null" : value.getClass().getSimpleName() + " " + value));
    }
}
