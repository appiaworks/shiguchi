package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON values into proto fields under the strict input rules: a value that does not fit its
 * field is refused, never coerced. The compiled parser and the reflective converter both read
 * through here when the dialect in force has {@linkplain JsonDialect#strictInput() strict input},
 * so the two accept exactly the same documents and build exactly the same messages.
 *
 * <p>The rules are proto3 JSON's:
 *
 * <ul>
 *   <li>Integers: a JSON number or a string holding one, whole and in range for the field
 *       ({@code 1e2} is 100; {@code 1.5}, {@code "abc"} and {@code 2147483648} for an int32 are
 *       refused).
 *   <li>Floating point: a number, a numeric string, or {@code "NaN"}, {@code "Infinity"},
 *       {@code "-Infinity"}; a float out of float range is refused.
 *   <li>Booleans: {@code true}/{@code false}, or those words as strings.
 *   <li>Strings: JSON strings only. Bytes: base64, standard or URL-safe.
 *   <li>Enums: a value name or number. An unknown one is ignored, as unknown fields are; any
 *       other kind of value is refused.
 *   <li>Messages: a JSON object; repeated fields: an array; map fields: an object. A JSON null
 *       inside an array or as a map value is refused, except for {@code google.protobuf.Value}.
 *   <li>Well-known types and scalar-mapped messages: {@link ProtoWkt#fromJava}, as always.
 * </ul>
 *
 * <p>Refusals go through {@link MalformedValues}: a {@code ProtoJsonException} inside a
 * {@code ProtoJson} call, the host's own type (gRPC {@code INVALID_ARGUMENT} in the gateway)
 * otherwise.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public final class StrictScalars {

    private StrictScalars() {}

    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    // ── Streaming (compiled parser) ──────────────────────────────────────────────────

    /**
     * The value the parser is on, as the field's proto value; null for an unknown enum value or a
     * well-known type that reads as unset. The parser is left on the value's last token.
     */
    public static Object read(JsonParser p, FieldDescriptor fd) throws IOException {
        JsonToken t = p.currentToken();
        switch (fd.getType()) {
            case INT32, SINT32, SFIXED32 -> {
                if (t == JsonToken.VALUE_NUMBER_INT && p.getNumberType() == JsonParser.NumberType.INT) {
                    return p.getIntValue();
                }
            }
            case INT64, SINT64, SFIXED64 -> {
                if (t == JsonToken.VALUE_NUMBER_INT
                        && (p.getNumberType() == JsonParser.NumberType.INT
                                || p.getNumberType() == JsonParser.NumberType.LONG)) {
                    return p.getLongValue();
                }
            }
            case DOUBLE -> {
                if (t == JsonToken.VALUE_NUMBER_FLOAT || t == JsonToken.VALUE_NUMBER_INT) {
                    double d = p.getDoubleValue();
                    if (!Double.isInfinite(d)) return d;
                }
            }
            case BOOL -> {
                if (t == JsonToken.VALUE_TRUE) return Boolean.TRUE;
                if (t == JsonToken.VALUE_FALSE) return Boolean.FALSE;
            }
            case STRING -> {
                if (t == JsonToken.VALUE_STRING) return p.getText();
            }
            case ENUM -> {
                if (t == JsonToken.VALUE_STRING) return fd.getEnumType().findValueByName(p.getText());
            }
            default -> {}
        }
        return fieldValue(fd, JsonTrees.read(p));
    }

    /** Throws for the field: the parser is on {@code found}, which the field cannot take. */
    public static RuntimeException reject(FieldDescriptor fd, Object found, String expected) {
        return MalformedValues.malformed(typeName(fd), abbreviate(found), fd.getFullName() + " " + expected, null);
    }

    /** Throws for a message whose JSON is not an object: the parser was on {@code found}. */
    public static RuntimeException notAnObject(Descriptor d, Object found) {
        return MalformedValues.malformed(d, abbreviate(found), "expected a JSON object", null);
    }

    // ── Trees (reflective converter, compiled fromMap) ───────────────────────────────

    /** Builds the message from a JSON object read into a Map. */
    public static DynamicMessage messageFromMap(Map<?, ?> map, Descriptor descriptor) {
        return fill(map, DynamicMessage.newBuilder(descriptor));
    }

    /** Sets the builder's fields from a JSON object read into a Map; unknown keys are ignored. */
    public static DynamicMessage fill(Map<?, ?> map, DynamicMessage.Builder b) {
        for (FieldDescriptor fd : b.getDescriptorForType().getFields()) {
            String key = map.containsKey(fd.getJsonName()) ? fd.getJsonName() : fd.getName();
            if (!map.containsKey(key)) continue;
            Object value = map.get(key);
            if (value == null) {
                // JSON null leaves a field unset, except google.protobuf.Value, where null is a value.
                if (!fd.isRepeated() && isValue(fd)) b.setField(fd, ProtoWkt.fromJava(null, fd.getMessageType()));
                continue;
            }
            if (fd.isMapField()) {
                mapField(fd, value, b);
            } else if (fd.isRepeated()) {
                if (!(value instanceof List<?> list)) throw reject(fd, value, "takes a JSON array");
                for (Object element : list) {
                    Object v = fieldValue(fd, element);
                    if (v != null) b.addRepeatedField(fd, v);
                }
            } else {
                Object v = fieldValue(fd, value);
                if (v != null) b.setField(fd, v);
            }
        }
        return b.build();
    }

    /** Fills a map field from its JSON object. */
    public static void mapField(FieldDescriptor mapFd, Object value, DynamicMessage.Builder b) {
        if (!(value instanceof Map<?, ?> map)) throw reject(mapFd, value, "takes a JSON object");
        Descriptor entry = mapFd.getMessageType();
        FieldDescriptor keyFd = entry.findFieldByNumber(1);
        FieldDescriptor valFd = entry.findFieldByNumber(2);
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object key = mapKey(keyFd, String.valueOf(e.getKey()));
            Object val = fieldValue(valFd, e.getValue());
            if (val == null) continue; // an unknown enum value
            b.addRepeatedField(
                    mapFd,
                    DynamicMessage.newBuilder(entry)
                            .setField(keyFd, key)
                            .setField(valFd, val)
                            .build());
        }
    }

    /**
     * One JSON value (a Map, a List, a String, a Number, a Boolean or null) as the proto value of
     * the field, or of one element when the field is repeated; null for an unknown enum value or a
     * well-known type that reads as unset.
     */
    public static Object fieldValue(FieldDescriptor fd, Object v) {
        if (v == null) {
            if (isValue(fd)) return ProtoWkt.fromJava(null, fd.getMessageType());
            throw reject(fd, null, "does not take null here");
        }
        return switch (fd.getType()) {
            case INT32, SINT32, SFIXED32 ->
                integer(fd, v, -2147483648L, 2147483647L).intValue();
            case UINT32, FIXED32 -> (int) integer(fd, v, 0, UINT32_MAX).longValue();
            case INT64, SINT64, SFIXED64 ->
                integer(fd, v, Long.MIN_VALUE, Long.MAX_VALUE).longValue();
            case UINT64, FIXED64 -> uint64(fd, v);
            case FLOAT -> floating(fd, v, true);
            case DOUBLE -> floating(fd, v, false);
            case BOOL -> {
                if (v instanceof Boolean b) yield b;
                if ("true".equals(v)) yield Boolean.TRUE;
                if ("false".equals(v)) yield Boolean.FALSE;
                throw reject(fd, v, "takes true or false");
            }
            case STRING -> {
                if (v instanceof CharSequence s) yield s.toString();
                throw reject(fd, v, "takes a JSON string");
            }
            case BYTES -> bytes(fd, v);
            case ENUM -> enumValue(fd, v);
            case MESSAGE, GROUP -> {
                Descriptor md = fd.getMessageType();
                if (ProtoWkt.isWkt(md)) yield ProtoWkt.fromJava(v, md);
                if (v instanceof Map<?, ?> m) yield messageFromMap(m, md);
                throw reject(fd, v, "takes a JSON object");
            }
        };
    }

    // ── Rules per kind ───────────────────────────────────────────────────────────────

    private static BigInteger integer(FieldDescriptor fd, Object v, long min, long max) {
        BigInteger n = whole(v);
        if (n == null || n.compareTo(BigInteger.valueOf(min)) < 0 || n.compareTo(BigInteger.valueOf(max)) > 0) {
            throw reject(fd, v, "takes a whole number from " + min + " to " + max);
        }
        return n;
    }

    private static long uint64(FieldDescriptor fd, Object v) {
        BigInteger n = whole(v);
        if (n == null || n.signum() < 0 || n.compareTo(UINT64_MAX) > 0) {
            throw reject(fd, v, "takes a whole number from 0 to " + UINT64_MAX);
        }
        return n.longValue(); // the unsigned bit pattern
    }

    /** A whole number from a JSON number or a numeric string, or null; 1e2 and 100.0 count, 1.5 does not. */
    private static BigInteger whole(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return BigInteger.valueOf(((Number) v).longValue());
        }
        if (v instanceof BigInteger b) return b;
        BigDecimal d = decimal(v);
        if (d == null) return null;
        try {
            return d.toBigIntegerExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Object floating(FieldDescriptor fd, Object v, boolean isFloat) {
        double d;
        if (v instanceof Number n && !(v instanceof BigDecimal) && !(v instanceof BigInteger)) {
            d = n.doubleValue();
        } else if ("NaN".equals(v)) {
            return isFloat ? (Object) Float.NaN : (Object) Double.NaN;
        } else if ("Infinity".equals(v)) {
            return isFloat ? (Object) Float.POSITIVE_INFINITY : (Object) Double.POSITIVE_INFINITY;
        } else if ("-Infinity".equals(v)) {
            return isFloat ? (Object) Float.NEGATIVE_INFINITY : (Object) Double.NEGATIVE_INFINITY;
        } else {
            BigDecimal b = decimal(v);
            if (b == null) throw reject(fd, v, "takes a number, or \"NaN\", \"Infinity\" or \"-Infinity\"");
            d = b.doubleValue();
        }
        if (Double.isInfinite(d) || (isFloat && Math.abs(d) > Float.MAX_VALUE)) {
            throw reject(fd, v, "is out of range for a " + (isFloat ? "float" : "double"));
        }
        return isFloat ? (Object) (float) d : (Object) d;
    }

    /** A number or a numeric string as a BigDecimal; null when it is neither. */
    private static BigDecimal decimal(Object v) {
        try {
            if (v instanceof BigDecimal b) return b;
            if (v instanceof BigInteger b) return new BigDecimal(b);
            if (v instanceof Double || v instanceof Float) {
                double d = ((Number) v).doubleValue();
                return Double.isFinite(d) ? new BigDecimal(Double.toString(d)) : null;
            }
            if (v instanceof Number n) return BigDecimal.valueOf(n.longValue());
            if (v instanceof CharSequence s) return new BigDecimal(s.toString());
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static ByteString bytes(FieldDescriptor fd, Object v) {
        if (!(v instanceof CharSequence s)) throw reject(fd, v, "takes base64 text");
        String text = s.toString();
        try {
            return ByteString.copyFrom(Base64.getDecoder().decode(text));
        } catch (IllegalArgumentException standard) {
            try {
                return ByteString.copyFrom(Base64.getUrlDecoder().decode(text));
            } catch (IllegalArgumentException urlSafe) {
                throw reject(fd, v, "takes base64 text");
            }
        }
    }

    private static EnumValueDescriptor enumValue(FieldDescriptor fd, Object v) {
        if (v instanceof EnumValueDescriptor e) return e;
        if (v instanceof CharSequence s) return fd.getEnumType().findValueByName(s.toString());
        BigInteger n = v instanceof Number ? whole(v) : null;
        if (n == null || n.bitLength() >= 32) {
            throw reject(
                    fd, v, "takes a value name or number of " + fd.getEnumType().getFullName());
        }
        return fd.getEnumType().findValueByNumber(n.intValue());
    }

    /** A map key from its JSON object key: the key type's text form, nothing coerced. */
    public static Object mapKey(FieldDescriptor keyFd, String key) {
        return switch (keyFd.getType()) {
            case STRING -> key;
            case BOOL -> {
                if ("true".equals(key)) yield Boolean.TRUE;
                if ("false".equals(key)) yield Boolean.FALSE;
                throw reject(keyFd, key, "takes true or false");
            }
            default -> fieldValue(keyFd, key);
        };
    }

    private static boolean isValue(FieldDescriptor fd) {
        return fd.getType() == FieldDescriptor.Type.MESSAGE
                && "google.protobuf.Value".equals(fd.getMessageType().getFullName());
    }

    /** What the field is, for the first words of the error: int32, acme.Item, acme.Status. */
    private static String typeName(FieldDescriptor fd) {
        return switch (fd.getType()) {
            case MESSAGE, GROUP -> fd.getMessageType().getFullName();
            case ENUM -> fd.getEnumType().getFullName();
            default -> fd.getType().name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static Object abbreviate(Object value) {
        if (value == null) return "null";
        String text = String.valueOf(value);
        return text.length() <= 60 ? value : text.substring(0, 60) + "…";
    }
}
