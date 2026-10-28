package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.FieldMaskUtil;
import com.google.protobuf.util.Timestamps;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proto3 JSON mapping for well-known types (WKTs), used by Tier 2 generated serializers and
 * the Tier 3 {@link ProtoMapConverter} fallback so both tiers emit the same JSON as
 * {@code JsonFormat}.
 *
 * <p>All field access goes through the <em>instance</em> descriptor
 * ({@code msg.getDescriptorForType().findFieldByNumber(..)}), never the canonical compiled
 * descriptors: descriptors reconstructed from gRPC reflection are distinct object graphs, and
 * mixing them with canonical {@code FieldDescriptor}s throws at runtime.
 *
 * <p>{@code google.protobuf.Any} is intentionally absent — correct Any JSON needs a
 * TypeRegistry, so messages containing Any fields are excluded from Tier 2 compilation and
 * fall back to Tier 3 {@code JsonFormat}.
 */
public final class ProtoWkt {

    private ProtoWkt() {}

    /**
     * True when the descriptor is a WKT whose proto3 JSON form is not a plain object, or a
     * message the installed {@link ProtoJsonMapping} writes as one value: a {@code google.type}
     * message it switches on, or one an application registered a {@link ProtoScalarCodec} for.
     */
    public static boolean isWkt(Descriptor d) {
        return switch (d.getFullName()) {
            case "google.protobuf.Timestamp",
                    "google.protobuf.Duration",
                    "google.protobuf.FieldMask",
                    "google.protobuf.Struct",
                    "google.protobuf.Value",
                    "google.protobuf.ListValue",
                    "google.protobuf.DoubleValue",
                    "google.protobuf.FloatValue",
                    "google.protobuf.Int64Value",
                    "google.protobuf.UInt64Value",
                    "google.protobuf.Int32Value",
                    "google.protobuf.UInt32Value",
                    "google.protobuf.BoolValue",
                    "google.protobuf.StringValue",
                    "google.protobuf.BytesValue" -> true;
            default -> ProtoJsonMapping.isMapped(d);
        };
    }

    // ── DynamicMessage → JSON-safe Java value ─────────────────────────────────────────

    /**
     * Converts a WKT DynamicMessage to its proto3 JSON representation as a plain Java value:
     * String, Number, Boolean, Map, List or null. Matches JsonFormat output exactly.
     */
    public static Object toJsonValue(DynamicMessage wkt) {
        Descriptor d = wkt.getDescriptorForType();
        ProtoScalarCodec codec = ProtoJsonMapping.codecFor(d);
        if (codec != null) return codec.toJson(wkt);
        return switch (d.getFullName()) {
            case "google.protobuf.Timestamp" -> timestampText(secondsField(wkt), nanosField(wkt));
            case "google.protobuf.Duration" -> durationText(secondsField(wkt), nanosField(wkt));
            case "google.protobuf.FieldMask" -> FieldMaskUtil.toJsonString(toFieldMask(wkt));
            case "google.protobuf.Struct" -> structToJava(wkt);
            case "google.protobuf.Value" -> valueToJava(wkt);
            case "google.protobuf.ListValue" -> listValueToJava(wkt);
            case "google.protobuf.DoubleValue" -> (Double) valueField(wkt);
            case "google.protobuf.FloatValue" -> (Float) valueField(wkt);
            // int64/uint64 are JSON strings per the proto3 JSON spec
            case "google.protobuf.Int64Value" ->
                ProtoJsonMapping.current().int64AsNumber()
                        ? (Object) valueField(wkt)
                        : String.valueOf((Long) valueField(wkt));
            case "google.protobuf.UInt64Value" ->
                ProtoJsonMapping.current().int64AsNumber()
                        ? (Object) new java.math.BigInteger(Long.toUnsignedString((Long) valueField(wkt)))
                        : Long.toUnsignedString((Long) valueField(wkt));
            case "google.protobuf.Int32Value" -> (Integer) valueField(wkt);
            case "google.protobuf.UInt32Value" -> Integer.toUnsignedLong((Integer) valueField(wkt));
            case "google.protobuf.BoolValue" -> (Boolean) valueField(wkt);
            case "google.protobuf.StringValue" -> (String) valueField(wkt);
            case "google.protobuf.BytesValue" ->
                Base64.getEncoder().encodeToString(((ByteString) valueField(wkt)).toByteArray());
            default -> throw new IllegalArgumentException("Not a WKT: " + d.getFullName());
        };
    }

    /**
     * GraphQL flavour: identical to {@link #toJsonValue} except Timestamp becomes an
     * {@link OffsetDateTime} so the DateTime scalar receives the native type.
     */
    public static Object toJavaValue(DynamicMessage wkt) {
        if ("google.protobuf.Timestamp".equals(wkt.getDescriptorForType().getFullName())) {
            return toOffsetDateTime(wkt);
        }
        return toJsonValue(wkt);
    }

    public static OffsetDateTime toOffsetDateTime(DynamicMessage timestampMsg) {
        Descriptor d = timestampMsg.getDescriptorForType();
        long seconds = ((Number) timestampMsg.getField(d.findFieldByNumber(1))).longValue();
        int nanos = ((Number) timestampMsg.getField(d.findFieldByNumber(2))).intValue();
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneOffset.UTC);
    }

    // ── Java value → DynamicMessage ───────────────────────────────────────────────────

    /**
     * Builds a WKT DynamicMessage on the given descriptor from a JSON-shaped Java value
     * (or an OffsetDateTime for Timestamp). JSON null means unset and returns null (except for
     * {@code google.protobuf.Value}, where it is {@code null_value}).
     *
     * <p>A value that cannot become the type — {@code "yesterday"} for a Timestamp, {@code "abc"}
     * for an Int32Value — throws a {@link ProtoJsonException} naming the type and the value ({@code INVALID_ARGUMENT} in the gateway),
     * so the caller gets a 400 rather than a request that silently lost the field.
     */
    public static DynamicMessage fromJava(Object value, Descriptor d) {
        if (value == null) {
            // JSON null is a valid google.protobuf.Value (null_value); for every other WKT it means unset
            return "google.protobuf.Value".equals(d.getFullName()) ? valueFromJava(null, d) : null;
        }
        ProtoScalarCodec codec = ProtoJsonMapping.codecFor(d);
        if (codec != null) return fromCodec(codec, value, d);
        try {
            return switch (d.getFullName()) {
                case "google.protobuf.Timestamp" -> timestampFromJava(value, d);
                case "google.protobuf.Duration" -> {
                    com.google.protobuf.Duration dur = parseDuration(value.toString());
                    yield DynamicMessage.newBuilder(d)
                            .setField(d.findFieldByNumber(1), dur.getSeconds())
                            .setField(d.findFieldByNumber(2), dur.getNanos())
                            .build();
                }
                case "google.protobuf.FieldMask" -> {
                    FieldMask mask = FieldMaskUtil.fromJsonString(value.toString());
                    DynamicMessage.Builder b = DynamicMessage.newBuilder(d);
                    FieldDescriptor paths = d.findFieldByNumber(1);
                    for (String p : mask.getPathsList()) b.addRepeatedField(paths, p);
                    yield b.build();
                }
                case "google.protobuf.Struct" -> {
                    if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected a JSON object");
                    yield structFromJava(m, d);
                }
                case "google.protobuf.Value" -> valueFromJava(value, d);
                case "google.protobuf.ListValue" -> {
                    if (!(value instanceof List<?> l)) throw new IllegalArgumentException("expected a JSON array");
                    yield listValueFromJava(l, d);
                }
                case "google.protobuf.DoubleValue" -> wrapper(d, toNumber(value).doubleValue());
                case "google.protobuf.FloatValue" -> wrapper(d, toNumber(value).floatValue());
                case "google.protobuf.Int64Value" -> wrapper(d, toNumber(value).longValue());
                case "google.protobuf.UInt64Value" ->
                    wrapper(
                            d,
                            value instanceof String s
                                    ? Long.parseUnsignedLong(s)
                                    : toNumber(value).longValue());
                case "google.protobuf.Int32Value" -> wrapper(d, toNumber(value).intValue());
                case "google.protobuf.UInt32Value" ->
                    wrapper(d, (int) toNumber(value).longValue());
                case "google.protobuf.BoolValue" -> {
                    // "true"/"false" too: a query parameter always arrives as text.
                    Boolean bool = ProtoMapConverter.coerceBool(value);
                    if (bool == null) throw new IllegalArgumentException("expected true or false");
                    yield wrapper(d, bool);
                }
                case "google.protobuf.StringValue" -> wrapper(d, value.toString());
                case "google.protobuf.BytesValue" -> {
                    if (!(value instanceof String s)) throw new IllegalArgumentException("expected base64 text");
                    yield wrapper(d, ByteString.copyFrom(Base64.getDecoder().decode(s)));
                }
                default -> null;
            };
        } catch (Exception e) {
            throw malformed(d, value, e);
        }
    }

    /**
     * Reads a value through a scalar codec. An application's codec may answer with a generated
     * message, which is re-read onto {@code d}; whatever it throws becomes a {@link ProtoJsonException}.
     * Unless the codec's own JSON form is an object, an object is the message's plain proto3 form.
     */
    @SuppressWarnings("unchecked")
    private static DynamicMessage fromCodec(ProtoScalarCodec codec, Object value, Descriptor d) {
        if (codec instanceof BuiltInScalarCodecs.BuiltIn) return (DynamicMessage) codec.fromJson(value, d);
        try {
            if (value instanceof Map<?, ?> map && codec.schema().shape() != ProtoScalarCodec.JsonShape.ANY) {
                return ProtoMapConverter.dynamicFromMap((Map<String, Object>) map, d);
            }
            Message message = codec.fromJson(value, d);
            return message == null ? null : ProtoTypes.dynamic(message, d);
        } catch (RuntimeException e) {
            // Already reported, here or by a nested field, or by the codec in the host's own type.
            if (e instanceof ProtoJsonException || MalformedValues.isTranslated(e)) throw e;
            throw malformed(d, value, e);
        } catch (Exception e) {
            throw malformed(d, value, e);
        }
    }

    private static RuntimeException malformed(Descriptor d, Object value, Exception cause) {
        String reason = cause.getMessage() != null
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        return MalformedValues.malformed(d, value, reason, cause);
    }

    // ── Streaming JSON write (Tier 2 serialize path) ──────────────────────────────────

    /** Writes a WKT DynamicMessage as its proto3 JSON value directly to the generator. */
    public static void writeJson(JsonGenerator gen, DynamicMessage wkt) throws IOException {
        writeJsonTree(gen, toJsonValue(wkt));
    }

    /**
     * {@link #writeJson(JsonGenerator, DynamicMessage)} for any form of the message: a generated
     * class reads through its own getters, so a Tier 1 writer pays no conversion for a Timestamp,
     * a Duration, a FieldMask or a wrapper. A codec-mapped message goes to its codec as it is;
     * Struct, Value and ListValue take their dynamic form.
     */
    public static void writeJson(JsonGenerator gen, Message wkt) throws IOException {
        if (wkt instanceof DynamicMessage dynamic) {
            writeJson(gen, dynamic);
            return;
        }
        Descriptor d = wkt.getDescriptorForType();
        ProtoScalarCodec codec = ProtoJsonMapping.codecFor(d);
        if (codec != null) {
            writeJsonTree(gen, codec.toJson(wkt));
        } else if (wkt instanceof com.google.protobuf.Timestamp ts) {
            gen.writeString(timestampText(ts.getSeconds(), ts.getNanos()));
        } else if (wkt instanceof com.google.protobuf.Duration dur) {
            gen.writeString(durationText(dur.getSeconds(), dur.getNanos()));
        } else if (wkt instanceof FieldMask mask) {
            gen.writeString(FieldMaskUtil.toJsonString(mask));
        } else if (wkt instanceof com.google.protobuf.Int64Value v) {
            ProtoMapConverter.writeInt64(gen, v.getValue());
        } else if (wkt instanceof com.google.protobuf.UInt64Value v) {
            ProtoMapConverter.writeUInt64(gen, v.getValue());
        } else if (wkt instanceof com.google.protobuf.Int32Value v) {
            gen.writeNumber(v.getValue());
        } else if (wkt instanceof com.google.protobuf.UInt32Value v) {
            gen.writeNumber(Integer.toUnsignedLong(v.getValue()));
        } else if (wkt instanceof com.google.protobuf.DoubleValue v) {
            gen.writeNumber(v.getValue());
        } else if (wkt instanceof com.google.protobuf.FloatValue v) {
            gen.writeNumber(v.getValue());
        } else if (wkt instanceof com.google.protobuf.BoolValue v) {
            gen.writeBoolean(v.getValue());
        } else if (wkt instanceof com.google.protobuf.StringValue v) {
            gen.writeString(v.getValue());
        } else if (wkt instanceof com.google.protobuf.BytesValue v) {
            gen.writeString(Base64.getEncoder().encodeToString(v.getValue().toByteArray()));
        } else {
            writeJson(gen, ProtoTypes.dynamic(wkt, d));
        }
    }

    /** Writes a JSON-safe Java tree (String/Number/Boolean/Map/List/null) to the generator. */
    public static void writeJsonTree(JsonGenerator gen, Object value) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else if (value instanceof String s) {
            gen.writeString(s);
        } else if (value instanceof Integer i) {
            gen.writeNumber(i);
        } else if (value instanceof Long l) {
            gen.writeNumber(l);
        } else if (value instanceof java.math.BigInteger bi) {
            gen.writeNumber(bi);
        } else if (value instanceof Double dv) {
            gen.writeNumber(dv);
        } else if (value instanceof Float f) {
            gen.writeNumber(f);
        } else if (value instanceof Number n) {
            gen.writeNumber(n.doubleValue());
        } else if (value instanceof Boolean b) {
            gen.writeBoolean(b);
        } else if (value instanceof Map<?, ?> m) {
            gen.writeStartObject();
            for (var e : m.entrySet()) {
                gen.writeFieldName(String.valueOf(e.getKey()));
                writeJsonTree(gen, e.getValue());
            }
            gen.writeEndObject();
        } else if (value instanceof List<?> list) {
            gen.writeStartArray();
            for (Object o : list) writeJsonTree(gen, o);
            gen.writeEndArray();
        } else if (value instanceof OffsetDateTime odt) {
            gen.writeString(
                    Timestamps.toString(Timestamps.fromNanos(odt.toEpochSecond() * 1_000_000_000L + odt.getNano())));
        } else {
            gen.writeString(String.valueOf(value));
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────

    private static Object valueField(DynamicMessage wkt) {
        return wkt.getField(wkt.getDescriptorForType().findFieldByNumber(1));
    }

    private static DynamicMessage wrapper(Descriptor d, Object value) {
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByNumber(1), value)
                .build();
    }

    /** Field 1 of a Timestamp or Duration. */
    private static long secondsField(DynamicMessage msg) {
        return ((Number) msg.getField(msg.getDescriptorForType().findFieldByNumber(1))).longValue();
    }

    /** Field 2 of a Timestamp or Duration. */
    private static int nanosField(DynamicMessage msg) {
        return ((Number) msg.getField(msg.getDescriptorForType().findFieldByNumber(2))).intValue();
    }

    private static FieldMask toFieldMask(DynamicMessage msg) {
        Descriptor d = msg.getDescriptorForType();
        FieldMask.Builder b = FieldMask.newBuilder();
        for (Object p : (List<?>) msg.getField(d.findFieldByNumber(1))) b.addPaths((String) p);
        return b.build();
    }

    private static DynamicMessage timestampFromJava(Object value, Descriptor d) throws Exception {
        long seconds;
        int nanos;
        if (value instanceof OffsetDateTime odt) {
            seconds = odt.toEpochSecond();
            nanos = odt.getNano();
        } else if (value instanceof Number millis
                && !(value instanceof Double || value instanceof Float)
                && ProtoJsonMapping.current().acceptEpochMillis()) {
            com.google.protobuf.Timestamp ts = Timestamps.fromMillis(millis.longValue());
            seconds = ts.getSeconds();
            nanos = ts.getNanos();
        } else {
            // Strict RFC 3339: Timestamps.parse is lenient and rolls 2026-13-01 over into 2027.
            OffsetDateTime odt = OffsetDateTime.parse(
                    value.toString().trim(), java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            com.google.protobuf.Timestamp ts = Timestamps.checkValid(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(odt.toEpochSecond())
                    .setNanos(odt.getNano())
                    .build());
            seconds = ts.getSeconds();
            nanos = ts.getNanos();
        }
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByNumber(1), seconds)
                .setField(d.findFieldByNumber(2), nanos)
                .build();
    }

    /** Duration text in the configured output form. */
    private static String durationText(com.google.protobuf.Duration dur) {
        return durationText(dur.getSeconds(), dur.getNanos());
    }

    /**
     * Duration text in the configured output form, from the message's two fields: the proto
     * form ({@code "1.5s"}) exactly as {@code Durations.toString} prints it, without its
     * {@code Formatter}, or ISO-8601 when so configured. An invalid duration throws as
     * {@code Durations.toString} does.
     */
    public static String durationText(long seconds, int nanos) {
        if (ProtoJsonMapping.current().durationOutput() == ProtoJsonMapping.DurationFormat.ISO_8601) {
            return java.time.Duration.ofSeconds(seconds, nanos).toString();
        }
        boolean valid = seconds >= -DURATION_SECONDS_MAX
                && seconds <= DURATION_SECONDS_MAX
                && nanos > -NANOS_PER_SECOND
                && nanos < NANOS_PER_SECOND
                && (seconds == 0 || nanos == 0 || (seconds < 0) == (nanos < 0));
        if (!valid) {
            return Durations.toString(com.google.protobuf.Duration.newBuilder()
                    .setSeconds(seconds)
                    .setNanos(nanos)
                    .build());
        }
        StringBuilder out = new StringBuilder(24);
        if (seconds < 0 || nanos < 0) {
            out.append('-');
            seconds = -seconds;
            nanos = -nanos;
        }
        out.append(seconds);
        if (nanos != 0) appendNanos(out, nanos);
        return out.append('s').toString();
    }

    private static final long DURATION_SECONDS_MAX = 315_576_000_000L;
    private static final int NANOS_PER_SECOND = 1_000_000_000;
    /** 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z, the Timestamp range. */
    private static final long TIMESTAMP_SECONDS_MIN = -62_135_596_800L;

    private static final long TIMESTAMP_SECONDS_MAX = 253_402_300_799L;
    /**
     * 1582-10-15T00:00:00Z. {@code Timestamps.toString} formats through {@code SimpleDateFormat},
     * whose calendar is Julian before that day; earlier instants keep that path so the text
     * stays identical.
     */
    private static final long GREGORIAN_CUTOVER_SECONDS = -12_219_292_800L;

    /**
     * RFC 3339 text of a Timestamp from its two fields, byte for byte what
     * {@code Timestamps.toString} prints — {@code 2023-11-14T22:13:20.123456789Z}, the fraction
     * in 3, 6 or 9 digits or absent — computed arithmetically instead of through a
     * {@code SimpleDateFormat} and a {@code Formatter}. An invalid timestamp throws as
     * {@code Timestamps.toString} does.
     */
    public static String timestampText(long seconds, int nanos) {
        if (seconds < GREGORIAN_CUTOVER_SECONDS
                || seconds > TIMESTAMP_SECONDS_MAX
                || seconds < TIMESTAMP_SECONDS_MIN
                || nanos < 0
                || nanos >= NANOS_PER_SECOND) {
            return Timestamps.toString(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(seconds)
                    .setNanos(nanos)
                    .build());
        }
        long days = Math.floorDiv(seconds, 86_400L);
        int secondOfDay = (int) Math.floorMod(seconds, 86_400L);
        // Civil date from days since 1970-01-01, proleptic Gregorian (H. Hinnant's algorithm).
        long z = days + 719_468L;
        long era = Math.floorDiv(z, 146_097L);
        long doe = z - era * 146_097L;
        long yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L;
        long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
        long mp = (5L * doy + 2L) / 153L;
        int day = (int) (doy - (153L * mp + 2L) / 5L + 1L);
        int month = (int) (mp < 10L ? mp + 3L : mp - 9L);
        int year = (int) (yoe + era * 400L + (month <= 2 ? 1L : 0L));

        StringBuilder out = new StringBuilder(30);
        appendDigits(out, year, 4);
        out.append('-');
        appendDigits(out, month, 2);
        out.append('-');
        appendDigits(out, day, 2);
        out.append('T');
        appendDigits(out, secondOfDay / 3_600, 2);
        out.append(':');
        appendDigits(out, (secondOfDay / 60) % 60, 2);
        out.append(':');
        appendDigits(out, secondOfDay % 60, 2);
        if (nanos != 0) appendNanos(out, nanos);
        return out.append('Z').toString();
    }

    /** {@code .123}, {@code .123456} or {@code .123456789}: the shortest of the three that is exact. */
    private static void appendNanos(StringBuilder out, int nanos) {
        out.append('.');
        if (nanos % 1_000_000 == 0) {
            appendDigits(out, nanos / 1_000_000, 3);
        } else if (nanos % 1_000 == 0) {
            appendDigits(out, nanos / 1_000, 6);
        } else {
            appendDigits(out, nanos, 9);
        }
    }

    private static void appendDigits(StringBuilder out, int value, int width) {
        int start = out.length();
        out.append(value);
        for (int pad = width - (out.length() - start); pad > 0; pad--) out.insert(start, '0');
    }

    /** The proto form ({@code "5400s"}) always; ISO-8601 ({@code "PT1H30M"}) when accepted. */
    private static com.google.protobuf.Duration parseDuration(String text) throws Exception {
        String s = text.trim();
        boolean iso = s.startsWith("P") || s.startsWith("-P") || s.startsWith("+P");
        if (iso && ProtoJsonMapping.current().acceptIso8601Duration()) {
            return Durations.checkValid(toProtoDuration(java.time.Duration.parse(s)));
        }
        return Durations.parse(s);
    }

    /** java.time keeps nanos non-negative; proto wants seconds and nanos to share a sign. */
    private static com.google.protobuf.Duration toProtoDuration(java.time.Duration jd) {
        long seconds = jd.getSeconds();
        int nanos = jd.getNano();
        if (seconds < 0 && nanos > 0) {
            seconds += 1;
            nanos -= 1_000_000_000;
        }
        return com.google.protobuf.Duration.newBuilder()
                .setSeconds(seconds)
                .setNanos(nanos)
                .build();
    }

    private static Map<String, Object> structToJava(DynamicMessage struct) {
        FieldDescriptor fieldsFd = struct.getDescriptorForType().findFieldByNumber(1);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object entryObj : (List<?>) struct.getField(fieldsFd)) {
            DynamicMessage entry = (DynamicMessage) entryObj;
            Descriptor entryDesc = entry.getDescriptorForType();
            String key = (String) entry.getField(entryDesc.findFieldByNumber(1));
            DynamicMessage val = (DynamicMessage) entry.getField(entryDesc.findFieldByNumber(2));
            result.put(key, valueToJava(val));
        }
        return result;
    }

    private static Object valueToJava(DynamicMessage value) {
        Descriptor d = value.getDescriptorForType();
        OneofDescriptor kind = d.getOneofs().get(0);
        FieldDescriptor set = value.getOneofFieldDescriptor(kind);
        if (set == null) return null;
        return switch (set.getNumber()) {
            case 1 -> null; // null_value
            case 2 -> (Double) value.getField(set); // number_value
            case 3 -> (String) value.getField(set); // string_value
            case 4 -> (Boolean) value.getField(set); // bool_value
            case 5 -> structToJava((DynamicMessage) value.getField(set)); // struct_value
            case 6 -> listValueToJava((DynamicMessage) value.getField(set)); // list_value
            default -> null;
        };
    }

    private static List<Object> listValueToJava(DynamicMessage listValue) {
        FieldDescriptor valuesFd = listValue.getDescriptorForType().findFieldByNumber(1);
        List<?> raw = (List<?>) listValue.getField(valuesFd);
        List<Object> result = new ArrayList<>(raw.size());
        for (Object v : raw) result.add(valueToJava((DynamicMessage) v));
        return result;
    }

    private static DynamicMessage structFromJava(Map<?, ?> map, Descriptor structDesc) {
        FieldDescriptor fieldsFd = structDesc.findFieldByNumber(1);
        Descriptor entryDesc = fieldsFd.getMessageType();
        Descriptor valueDesc = entryDesc.findFieldByNumber(2).getMessageType();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(structDesc);
        for (var e : map.entrySet()) {
            b.addRepeatedField(
                    fieldsFd,
                    DynamicMessage.newBuilder(entryDesc)
                            .setField(entryDesc.findFieldByNumber(1), String.valueOf(e.getKey()))
                            .setField(entryDesc.findFieldByNumber(2), valueFromJava(e.getValue(), valueDesc))
                            .build());
        }
        return b.build();
    }

    private static DynamicMessage valueFromJava(Object value, Descriptor valueDesc) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(valueDesc);
        if (value == null) {
            FieldDescriptor nullFd = valueDesc.findFieldByNumber(1);
            b.setField(nullFd, nullFd.getEnumType().findValueByNumber(0));
        } else if (value instanceof Number n) {
            b.setField(valueDesc.findFieldByNumber(2), n.doubleValue());
        } else if (value instanceof String s) {
            b.setField(valueDesc.findFieldByNumber(3), s);
        } else if (value instanceof Boolean bool) {
            b.setField(valueDesc.findFieldByNumber(4), bool);
        } else if (value instanceof Map<?, ?> m) {
            b.setField(
                    valueDesc.findFieldByNumber(5),
                    structFromJava(m, valueDesc.findFieldByNumber(5).getMessageType()));
        } else if (value instanceof List<?> l) {
            b.setField(
                    valueDesc.findFieldByNumber(6),
                    listValueFromJava(l, valueDesc.findFieldByNumber(6).getMessageType()));
        } else {
            b.setField(valueDesc.findFieldByNumber(3), String.valueOf(value));
        }
        return b.build();
    }

    private static DynamicMessage listValueFromJava(List<?> list, Descriptor listValueDesc) {
        FieldDescriptor valuesFd = listValueDesc.findFieldByNumber(1);
        Descriptor valueDesc = valuesFd.getMessageType();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(listValueDesc);
        for (Object v : list) b.addRepeatedField(valuesFd, valueFromJava(v, valueDesc));
        return b.build();
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number n) return n;
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return Double.parseDouble(s);
            }
        }
        throw new IllegalArgumentException("Cannot convert to number: " + value.getClass());
    }

    /**
     * Resolves an enum value from a name String, a Number, or an EnumValueDescriptor.
     * Returns null when unresolvable. Public because Tier 2 generated code calls it.
     */
    public static EnumValueDescriptor enumValue(FieldDescriptor fd, Object value) {
        if (value instanceof EnumValueDescriptor evd) return evd;
        if (value instanceof String s) return fd.getEnumType().findValueByName(s);
        if (value instanceof Number n) return fd.getEnumType().findValueByNumber(n.intValue());
        return null;
    }
}
