package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The request-side counterpart of {@link WireJsonTranscoder}: proto3 JSON text straight to
 * protobuf wire bytes, one streaming pass, no {@code Map} and no {@code DynamicMessage}.
 *
 * <p>A {@link Program} is precomputed per descriptor. {@link #transcode} walks the JSON tokens
 * with a Jackson parser and writes each field into a {@link WireBuffer} as it is read; nested
 * messages are written in place, their length filled in when they close.
 *
 * <p><b>Bail-out contract</b>: the transcoder only takes documents it can convert exactly as the
 * reflective {@code fromMap} path would, in both the lenient and the strict dialect — a JSON
 * number or a decimal string for an integer field, in range; a number, a numeric string or
 * {@code NaN}/{@code Infinity} for a floating field; {@code true}/{@code false} (or those words
 * as strings) for a bool; a string for a string; base64 for bytes; a value name or number for an
 * enum; an object for a message or a map; an array for a repeated field. Anything else — a
 * coercion the lenient dialect would perform, a refusal the strict one would raise, a duplicate
 * key, a null where the dialects differ, an unpaired surrogate — makes {@code transcode} return
 * -1 and write nothing the caller should keep; the caller then takes the {@code Map} path, which
 * applies the coercion or raises the error. Unknown keys are skipped, as that path ignores them.
 *
 * <p>The well-known types go through {@link ProtoWkt#fromJava} and a {@code DynamicMessage}, as
 * on every other path; messages reaching {@code google.protobuf.Any} or a group, and messages
 * with more than 64 fields, get no program.
 */
public final class JsonWireTranscoder {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final int MAX_DEPTH = 100;
    private static final int MAX_TOP_LEVEL_FIELDS = 64;
    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final Base64.Decoder BASE64 = Base64.getDecoder();

    private final ConcurrentHashMap<Descriptor, ProgramHolder> programs = new ConcurrentHashMap<>();

    /** An input the transcoder does not take; caught in {@link #transcode}. */
    private static final class Bail extends RuntimeException {
        static final Bail INSTANCE = new Bail();

        private Bail() {
            super(null, null, false, false);
        }
    }

    private record ProgramHolder(Program program) {}

    /** Returns the transcoding program for a descriptor, or null when unsupported. */
    public Program programFor(Descriptor descriptor) {
        return programs.computeIfAbsent(descriptor, d -> {
                    try {
                        return new ProgramHolder(build(d, new HashMap<>()));
                    } catch (UnsupportedOperationException e) {
                        return new ProgramHolder(null);
                    }
                })
                .program();
    }

    /**
     * Transcodes a JSON object to wire bytes appended to {@code out}.
     *
     * @param caseInsensitiveEnums whether an enum value name matches ignoring case, the gateway's
     *     {@code case-insensitive-enums} option
     * @return the top-level fields the document set, one bit per field index, or -1 when the
     *     document is not one the transcoder takes; {@code out} is then rewound to where it was
     */
    public long transcode(Program program, String json, WireBuffer out, boolean caseInsensitiveEnums) {
        int start = out.size();
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_OBJECT) throw Bail.INSTANCE;
            long seen = writeObject(program, parser, out, 0, caseInsensitiveEnums);
            if (parser.nextToken() != null) throw Bail.INSTANCE; // trailing content
            return seen;
        } catch (Exception bailOrMalformed) {
            out.truncate(start);
            return -1L;
        }
    }

    // ── Program construction ─────────────────────────────────────────────────────────

    enum Kind {
        SCALAR,
        ENUM,
        MESSAGE,
        WKT,
        MAP
    }

    /** Precompiled per-descriptor table: entries by JSON and proto name. */
    public static final class Program {
        final Descriptor descriptor;
        final Entry[] entries;
        final Map<String, Entry> byName;

        Program(Descriptor descriptor, Entry[] entries) {
            this.descriptor = descriptor;
            this.entries = entries;
            this.byName = new HashMap<>(entries.length * 3);
        }

        void index() {
            for (Entry e : entries) {
                byName.put(e.fd.getJsonName(), e);
                byName.putIfAbsent(e.fd.getName(), e);
            }
        }

        /** The field at a top-level bit of a {@link #transcode} result. */
        public FieldDescriptor fieldAt(int index) {
            return entries[index].fd;
        }
    }

    static final class Entry {
        final FieldDescriptor fd;
        final int number;
        final int index;
        final Kind kind;
        final boolean repeated;
        final boolean packed;
        Program nested; // MESSAGE, or MAP with message values

        // MAP only
        final FieldDescriptor keyFd;
        final FieldDescriptor valueFd;
        final Kind valueKind;

        Entry(FieldDescriptor fd, int index, Kind kind) {
            this.fd = fd;
            this.number = fd.getNumber();
            this.index = index;
            this.kind = kind;
            this.repeated = fd.isRepeated() && !fd.isMapField();
            this.packed = this.repeated && fd.isPacked();
            if (kind == Kind.MAP) {
                Descriptor entry = fd.getMessageType();
                this.keyFd = entry.findFieldByNumber(1);
                this.valueFd = entry.findFieldByNumber(2);
                this.valueKind = kindOf(valueFd);
            } else {
                this.keyFd = null;
                this.valueFd = null;
                this.valueKind = null;
            }
        }
    }

    private static Kind kindOf(FieldDescriptor fd) {
        if (fd.getType() == FieldDescriptor.Type.GROUP) {
            throw new UnsupportedOperationException("group field: " + fd.getFullName());
        }
        if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
            Descriptor md = fd.getMessageType();
            if ("google.protobuf.Any".equals(md.getFullName())) {
                throw new UnsupportedOperationException("Any field: " + fd.getFullName());
            }
            return ProtoWkt.isWkt(md) ? Kind.WKT : Kind.MESSAGE;
        }
        return fd.getType() == FieldDescriptor.Type.ENUM ? Kind.ENUM : Kind.SCALAR;
    }

    private Program build(Descriptor descriptor, Map<Descriptor, Program> building) {
        Program inProgress = building.get(descriptor);
        if (inProgress != null) return inProgress;
        ProgramHolder cached = programs.get(descriptor);
        if (cached != null) {
            if (cached.program() == null) throw new UnsupportedOperationException(descriptor.getFullName());
            return cached.program();
        }
        List<FieldDescriptor> fields = descriptor.getFields();
        if (fields.size() > MAX_TOP_LEVEL_FIELDS) {
            throw new UnsupportedOperationException("more than 64 fields: " + descriptor.getFullName());
        }
        Entry[] entries = new Entry[fields.size()];
        Program program = new Program(descriptor, entries);
        building.put(descriptor, program);
        for (int i = 0; i < fields.size(); i++) {
            FieldDescriptor fd = fields.get(i);
            Kind kind = fd.isMapField() ? Kind.MAP : kindOf(fd);
            entries[i] = new Entry(fd, i, kind);
            if (kind == Kind.MESSAGE) {
                entries[i].nested = build(fd.getMessageType(), building);
            } else if (kind == Kind.MAP && entries[i].valueKind == Kind.MESSAGE) {
                entries[i].nested = build(entries[i].valueFd.getMessageType(), building);
            }
        }
        program.index();
        return program;
    }

    // ── JSON walking ─────────────────────────────────────────────────────────────────

    /** The parser is on START_OBJECT; returns with it on the matching END_OBJECT. */
    private static long writeObject(Program p, JsonParser parser, WireBuffer out, int depth, boolean ciEnums)
            throws IOException {
        if (depth > MAX_DEPTH) throw Bail.INSTANCE;
        long seen = 0L;
        JsonToken t;
        while ((t = parser.nextToken()) == JsonToken.FIELD_NAME) {
            Entry e = p.byName.get(parser.currentName());
            JsonToken value = parser.nextToken();
            if (e == null) {
                parser.skipChildren();
                continue;
            }
            long bit = 1L << e.index;
            if ((seen & bit) != 0) throw Bail.INSTANCE; // a second occurrence replaces on the Map path
            if (value == JsonToken.VALUE_NULL) {
                // Lenient: an explicit null leaves the field unset. Strict: refused, except on
                // google.protobuf.Value — let that path decide.
                if (ProtoJsonMapping.strictInput()) throw Bail.INSTANCE;
                continue;
            }
            seen |= bit;
            if (e.kind == Kind.MAP) {
                writeMap(e, parser, value, out, depth, ciEnums);
            } else if (e.repeated) {
                writeRepeated(e, parser, value, out, depth, ciEnums);
            } else {
                writeSingular(e.kind, e.fd, e.number, e.nested, parser, value, out, depth, ciEnums);
            }
        }
        if (t != JsonToken.END_OBJECT) throw Bail.INSTANCE;
        return seen;
    }

    private static void writeRepeated(
            Entry e, JsonParser parser, JsonToken value, WireBuffer out, int depth, boolean ciEnums)
            throws IOException {
        if (value != JsonToken.START_ARRAY) throw Bail.INSTANCE;
        if (e.packed) {
            out.writeTag(e.number, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            int mark = out.beginLengthDelimited();
            JsonToken t;
            while ((t = parser.nextToken()) != JsonToken.END_ARRAY) {
                if (t == JsonToken.VALUE_NULL) {
                    if (ProtoJsonMapping.strictInput()) throw Bail.INSTANCE;
                    continue;
                }
                writeScalarNoTag(e.fd, parser, t, out, ciEnums);
            }
            out.endLengthDelimited(mark);
            return;
        }
        JsonToken t;
        while ((t = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (t == JsonToken.VALUE_NULL) {
                if (ProtoJsonMapping.strictInput()) throw Bail.INSTANCE;
                continue;
            }
            writeSingular(e.kind, e.fd, e.number, e.nested, parser, t, out, depth, ciEnums);
        }
    }

    private static void writeMap(
            Entry e, JsonParser parser, JsonToken value, WireBuffer out, int depth, boolean ciEnums)
            throws IOException {
        if (value != JsonToken.START_OBJECT) throw Bail.INSTANCE;
        JsonToken t;
        while ((t = parser.nextToken()) == JsonToken.FIELD_NAME) {
            String key = parser.currentName();
            JsonToken v = parser.nextToken();
            if (v == JsonToken.VALUE_NULL) {
                if (ProtoJsonMapping.strictInput()) throw Bail.INSTANCE;
                continue;
            }
            out.writeTag(e.number, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            int mark = out.beginLengthDelimited();
            writeMapKey(e.keyFd, key, out);
            writeSingular(e.valueKind, e.valueFd, 2, e.nested, parser, v, out, depth, ciEnums);
            out.endLengthDelimited(mark);
        }
        if (t != JsonToken.END_OBJECT) throw Bail.INSTANCE;
    }

    /** The entry key as field 1, from its object-key text — the parse {@code mapKeyFromString} does. */
    private static void writeMapKey(FieldDescriptor keyFd, String key, WireBuffer out) {
        try {
            switch (keyFd.getType()) {
                case STRING -> {
                    out.writeTag(1, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                    if (!out.writeString(key)) throw Bail.INSTANCE;
                }
                case BOOL -> {
                    out.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    out.writeBool(Boolean.parseBoolean(key));
                }
                case INT32 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    out.writeVarint64(Integer.parseInt(key));
                }
                case SINT32 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    out.writeSInt32(Integer.parseInt(key));
                }
                case SFIXED32 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_FIXED32);
                    out.writeFixed32(Integer.parseInt(key));
                }
                case UINT32 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    out.writeVarint32(Integer.parseUnsignedInt(key));
                }
                case FIXED32 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_FIXED32);
                    out.writeFixed32(Integer.parseUnsignedInt(key));
                }
                case INT64 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    out.writeVarint64(Long.parseLong(key));
                }
                case SINT64 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    out.writeSInt64(Long.parseLong(key));
                }
                case SFIXED64 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_FIXED64);
                    out.writeFixed64(Long.parseLong(key));
                }
                case UINT64 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_VARINT);
                    out.writeVarint64(Long.parseUnsignedLong(key));
                }
                case FIXED64 -> {
                    out.writeTag(1, WireFormat.WIRETYPE_FIXED64);
                    out.writeFixed64(Long.parseUnsignedLong(key));
                }
                default -> throw Bail.INSTANCE;
            }
        } catch (NumberFormatException e) {
            throw Bail.INSTANCE;
        }
    }

    private static void writeSingular(
            Kind kind,
            FieldDescriptor fd,
            int number,
            Program nested,
            JsonParser parser,
            JsonToken value,
            WireBuffer out,
            int depth,
            boolean ciEnums)
            throws IOException {
        switch (kind) {
            case MESSAGE -> {
                if (value != JsonToken.START_OBJECT) throw Bail.INSTANCE;
                out.writeTag(number, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                int mark = out.beginLengthDelimited();
                writeObject(nested, parser, out, depth + 1, ciEnums);
                out.endLengthDelimited(mark);
            }
            case WKT -> {
                Object tree = readTree(parser, value, depth);
                DynamicMessage wkt;
                try {
                    wkt = ProtoWkt.fromJava(tree, fd.getMessageType());
                } catch (RuntimeException malformed) {
                    throw Bail.INSTANCE; // the Map path raises the proper error
                }
                if (wkt == null) return;
                out.writeTag(number, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                out.writeBytes(wkt.toByteArray());
            }
            default -> {
                writeScalarTag(fd, number, out);
                writeScalarNoTag(fd, parser, value, out, ciEnums);
            }
        }
    }

    private static void writeScalarTag(FieldDescriptor fd, int number, WireBuffer out) {
        out.writeTag(number, fd.getLiteType().getWireType());
    }

    /** One scalar or enum value, written without its tag (packed runs share one). */
    private static void writeScalarNoTag(
            FieldDescriptor fd, JsonParser parser, JsonToken t, WireBuffer out, boolean ciEnums) throws IOException {
        switch (fd.getType()) {
            case INT32 -> out.writeVarint64(int32(parser, t));
            case SINT32 -> out.writeSInt32(int32(parser, t));
            case SFIXED32 -> out.writeFixed32(int32(parser, t));
            case UINT32 -> out.writeVarint32(uint32(parser, t));
            case FIXED32 -> out.writeFixed32(uint32(parser, t));
            case INT64 -> out.writeVarint64(int64(parser, t));
            case SINT64 -> out.writeSInt64(int64(parser, t));
            case SFIXED64 -> out.writeFixed64(int64(parser, t));
            case UINT64 -> out.writeVarint64(uint64(parser, t));
            case FIXED64 -> out.writeFixed64(uint64(parser, t));
            case BOOL -> out.writeBool(bool(parser, t));
            case FLOAT -> out.writeFixed32(Float.floatToRawIntBits((float) floating(parser, t)));
            case DOUBLE -> out.writeFixed64(Double.doubleToRawLongBits(floating(parser, t)));
            case STRING -> {
                if (t != JsonToken.VALUE_STRING) throw Bail.INSTANCE;
                if (!out.writeString(parser.getTextCharacters(), parser.getTextOffset(), parser.getTextLength())) {
                    throw Bail.INSTANCE;
                }
            }
            case BYTES -> {
                if (t != JsonToken.VALUE_STRING) throw Bail.INSTANCE;
                byte[] bytes;
                try {
                    bytes = BASE64.decode(parser.getText());
                } catch (IllegalArgumentException e) {
                    throw Bail.INSTANCE;
                }
                out.writeBytes(bytes);
            }
            case ENUM -> out.writeVarint64(enumNumber(fd, parser, t, ciEnums));
            default -> throw Bail.INSTANCE;
        }
    }

    // ── Scalar readers: the shapes both dialects take, else bail ──────────────────────

    private static long wholeNumber(JsonParser parser, JsonToken t) throws IOException {
        if (t == JsonToken.VALUE_NUMBER_INT) {
            if (parser.getNumberType() == JsonParser.NumberType.BIG_INTEGER) throw Bail.INSTANCE;
            return parser.getLongValue();
        }
        if (t == JsonToken.VALUE_STRING) {
            try {
                return Long.parseLong(parser.getText());
            } catch (NumberFormatException e) {
                throw Bail.INSTANCE;
            }
        }
        throw Bail.INSTANCE;
    }

    private static int int32(JsonParser parser, JsonToken t) throws IOException {
        long v = wholeNumber(parser, t);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw Bail.INSTANCE;
        return (int) v;
    }

    private static int uint32(JsonParser parser, JsonToken t) throws IOException {
        long v = wholeNumber(parser, t);
        if (v < 0 || v > 0xFFFFFFFFL) throw Bail.INSTANCE;
        return (int) v;
    }

    private static long int64(JsonParser parser, JsonToken t) throws IOException {
        return wholeNumber(parser, t);
    }

    private static long uint64(JsonParser parser, JsonToken t) throws IOException {
        if (t == JsonToken.VALUE_NUMBER_INT) {
            if (parser.getNumberType() == JsonParser.NumberType.BIG_INTEGER) {
                BigInteger big = parser.getBigIntegerValue();
                if (big.signum() < 0 || big.compareTo(UINT64_MAX) > 0) throw Bail.INSTANCE;
                return big.longValue();
            }
            long v = parser.getLongValue();
            if (v < 0) throw Bail.INSTANCE;
            return v;
        }
        if (t == JsonToken.VALUE_STRING) {
            try {
                return Long.parseUnsignedLong(parser.getText());
            } catch (NumberFormatException e) {
                throw Bail.INSTANCE;
            }
        }
        throw Bail.INSTANCE;
    }

    private static boolean bool(JsonParser parser, JsonToken t) throws IOException {
        if (t == JsonToken.VALUE_TRUE) return true;
        if (t == JsonToken.VALUE_FALSE) return false;
        if (t == JsonToken.VALUE_STRING) {
            String s = parser.getText();
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
        }
        throw Bail.INSTANCE;
    }

    private static double floating(JsonParser parser, JsonToken t) throws IOException {
        if (t == JsonToken.VALUE_NUMBER_FLOAT || t == JsonToken.VALUE_NUMBER_INT) {
            return parser.getDoubleValue();
        }
        if (t == JsonToken.VALUE_STRING) {
            String s = parser.getText();
            switch (s) {
                case "NaN" -> {
                    return Double.NaN;
                }
                case "Infinity" -> {
                    return Double.POSITIVE_INFINITY;
                }
                case "-Infinity" -> {
                    return Double.NEGATIVE_INFINITY;
                }
                default -> {
                    try {
                        return Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        throw Bail.INSTANCE;
                    }
                }
            }
        }
        throw Bail.INSTANCE;
    }

    private static int enumNumber(FieldDescriptor fd, JsonParser parser, JsonToken t, boolean ciEnums)
            throws IOException {
        EnumValueDescriptor ev = null;
        if (t == JsonToken.VALUE_STRING) {
            String name = parser.getText();
            ev = fd.getEnumType().findValueByName(name);
            if (ev == null && ciEnums) {
                for (EnumValueDescriptor candidate : fd.getEnumType().getValues()) {
                    if (candidate.getName().equalsIgnoreCase(name)) {
                        ev = candidate;
                        break;
                    }
                }
            }
        } else if (t == JsonToken.VALUE_NUMBER_INT && parser.getNumberType() != JsonParser.NumberType.BIG_INTEGER) {
            long v = parser.getLongValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE)
                ev = fd.getEnumType().findValueByNumber((int) v);
        }
        if (ev == null) throw Bail.INSTANCE; // unknown: the Map path ignores or refuses it
        return ev.getNumber();
    }

    /** A JSON subtree as the plain Java value {@link ProtoWkt#fromJava} takes. */
    private static Object readTree(JsonParser parser, JsonToken t, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw Bail.INSTANCE;
        switch (t) {
            case VALUE_STRING -> {
                return parser.getText();
            }
            case VALUE_NUMBER_INT -> {
                return parser.getNumberType() == JsonParser.NumberType.BIG_INTEGER
                        ? parser.getBigIntegerValue()
                        : (Object) parser.getLongValue();
            }
            case VALUE_NUMBER_FLOAT -> {
                return parser.getDoubleValue();
            }
            case VALUE_TRUE -> {
                return Boolean.TRUE;
            }
            case VALUE_FALSE -> {
                return Boolean.FALSE;
            }
            case VALUE_NULL -> {
                return null;
            }
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                JsonToken n;
                while ((n = parser.nextToken()) == JsonToken.FIELD_NAME) {
                    String key = parser.currentName();
                    map.put(key, readTree(parser, parser.nextToken(), depth + 1));
                }
                if (n != JsonToken.END_OBJECT) throw Bail.INSTANCE;
                return map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                JsonToken n;
                while ((n = parser.nextToken()) != JsonToken.END_ARRAY) {
                    if (n == null) throw Bail.INSTANCE;
                    list.add(readTree(parser, n, depth + 1));
                }
                return list;
            }
            default -> throw Bail.INSTANCE;
        }
    }
}
