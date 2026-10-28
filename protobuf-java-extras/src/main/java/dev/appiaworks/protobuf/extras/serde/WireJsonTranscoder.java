package dev.appiaworks.protobuf.extras.serde;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.WireFormat;
import com.google.protobuf.util.FieldMaskUtil;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 0: descriptor-driven streaming transcoder from protobuf wire bytes straight to
 * proto3 JSON — no DynamicMessage (or any message object) materialised on the happy path.
 *
 * <p>A {@link Program} is precomputed per Descriptor: a field-number-indexed table of
 * writer entries (JSON name, type kind, nested program). {@link #transcode} walks the wire
 * tags with {@link CodedInputStream} and emits JSON tokens directly, then appends the
 * default values JsonFormat's includingDefaultValueFields would print for absent fields.
 *
 * <p><b>Bail-out contract</b>: streaming output cannot express wire encodings that require
 * merging — a singular field appearing twice (split-message merges), a repeated/map field
 * resuming after another field, two members of one oneof, or a map entry whose key and value
 * are not laid out once each in that order. Well-formed serializers never emit these, but they
 * are legal, so they are <em>detected</em> and {@code transcode} returns null; the caller falls
 * back to the DynamicMessage path. The JSON is buffered in memory, so nothing partial ever
 * reaches the client.
 *
 * <p>Print options ({@link JsonPrintOptions}) are applied per call, not per program: the field
 * name, the enum form and whether defaults are written are each one branch at write time, so one
 * program serves every service whatever its options. With {@code omitDefaultValues} a singular
 * field without presence is read before its name is written, so an explicit zero on the wire is
 * dropped just as the reflective writer drops it.
 *
 * <p>Types reachable from {@code google.protobuf.Any} (needs a TypeRegistry) or legacy
 * groups get no program at all ({@link #programFor} returns null) and are never eligible.
 * Map entries and every well-known type — {@code Timestamp}, {@code Duration}, the wrappers,
 * {@code Struct}, {@code Value}, {@code ListValue}, {@code FieldMask} — are read straight off
 * the wire too; a codec-mapped message is the one place a (small) {@link DynamicMessage} is
 * materialised, through the shared {@link ProtoWkt} logic.
 */
public class WireJsonTranscoder {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final int MAX_DEPTH = 100;
    private static final int DIRECT_INDEX_LIMIT = 1024;
    /** Fields (or oneofs) a message may have before presence tracking leaves a {@code long}. */
    private static final int BITMASK_LIMIT = 64;

    private final ConcurrentHashMap<Descriptor, ProgramHolder> programs = new ConcurrentHashMap<>();

    /** Signals an encoding the streaming writer cannot express; caught in {@link #transcode}. */
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
                    Map<Descriptor, Program> building = new HashMap<>();
                    try {
                        return new ProgramHolder(build(d, building));
                    } catch (UnsupportedOperationException e) {
                        return new ProgramHolder(null);
                    }
                })
                .program();
    }

    /**
     * Transcodes wire bytes to proto3 JSON (UTF-8). Returns null when the encoding needs the
     * merge semantics streaming cannot provide, or on any read error — the caller must then
     * fall back to parsing a DynamicMessage.
     */
    public byte[] transcode(Program program, byte[] wire) {
        return transcode(program, wire, JsonPrintOptions.DEFAULT);
    }

    /**
     * {@link #transcode(Program, byte[], JsonPrintOptions)} written straight to a stream — the
     * response buffer, so no intermediate byte array is built or copied.
     *
     * @return false when the encoding needs the merge semantics streaming cannot provide, or on
     *     a read error. Part of the JSON may already have reached the stream by then: the caller
     *     rewinds its buffer to where it was and takes the DynamicMessage path.
     */
    public boolean transcode(Program program, byte[] wire, JsonPrintOptions options, java.io.OutputStream out) {
        JsonPrintOptions opts = options == null ? JsonPrintOptions.DEFAULT : options;
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            CodedInputStream cis = CodedInputStream.newInstance(wire);
            writeMessage(program, cis, gen, 0, opts);
            gen.flush();
            return true;
        } catch (Exception bailOrMalformed) {
            return false;
        }
    }

    /** {@link #transcode(Program, byte[])} with a service's print options. */
    public byte[] transcode(Program program, byte[] wire, JsonPrintOptions options) {
        JsonPrintOptions opts = options == null ? JsonPrintOptions.DEFAULT : options;
        ByteArrayBuilder out = new ByteArrayBuilder();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            CodedInputStream cis = CodedInputStream.newInstance(wire);
            writeMessage(program, cis, gen, 0, opts);
            gen.flush();
            byte[] result = out.toByteArray();
            out.release();
            return result;
        } catch (Exception bailOrMalformed) {
            out.release();
            return null;
        }
    }

    // ── Program construction ─────────────────────────────────────────────────────────

    /** Precompiled per-descriptor transcoding table. */
    public static final class Program {
        final Descriptor descriptor;
        final Entry[] entries; // descriptor field order — filled after construction (recursive types)
        final int oneofCount;
        /** Presence tracked in {@code boolean[]}s instead of two {@code long}s. */
        final boolean wide;

        private Entry[] direct; // field-number direct index (null when numbers too sparse)
        private Map<Integer, Entry> byNumber; // fallback lookup

        Program(Descriptor descriptor, Entry[] entries, int oneofCount) {
            this.descriptor = descriptor;
            this.entries = entries;
            this.oneofCount = oneofCount;
            this.wide = entries.length > BITMASK_LIMIT || oneofCount > BITMASK_LIMIT;
        }

        /** Called once all entries are populated; safe because lookup only runs post-build. */
        void buildIndex() {
            int max = 0;
            for (Entry e : entries) max = Math.max(max, e.number);
            if (max <= DIRECT_INDEX_LIMIT) {
                direct = new Entry[max + 1];
                for (Entry e : entries) direct[e.number] = e;
            } else {
                byNumber = new HashMap<>();
                for (Entry e : entries) byNumber.put(e.number, e);
            }
        }

        Entry lookup(int number) {
            if (direct != null) {
                return number >= 0 && number < direct.length ? direct[number] : null;
            }
            return byNumber.get(number);
        }
    }

    enum Kind {
        SCALAR,
        ENUM,
        MESSAGE,
        /** A well-known type read off the wire directly: Timestamp, Duration, the wrappers. */
        WIRE_WKT,
        /** A well-known type that goes through a {@code DynamicMessage} and {@link ProtoWkt}. */
        WKT,
        MAP
    }

    /** The well-known types whose JSON form is one value computable from their wire fields alone. */
    enum WireWkt {
        TIMESTAMP,
        DURATION,
        DOUBLE_VALUE,
        FLOAT_VALUE,
        INT64_VALUE,
        UINT64_VALUE,
        INT32_VALUE,
        UINT32_VALUE,
        BOOL_VALUE,
        STRING_VALUE,
        BYTES_VALUE,
        STRUCT,
        VALUE,
        LIST_VALUE,
        FIELD_MASK;

        /** The direct form of a WKT, or null when it needs the dynamic path (a codec-mapped type does). */
        static WireWkt of(Descriptor d) {
            if (ProtoJsonMapping.codecFor(d) != null) return null;
            return switch (d.getFullName()) {
                case "google.protobuf.Timestamp" -> TIMESTAMP;
                case "google.protobuf.Duration" -> DURATION;
                case "google.protobuf.DoubleValue" -> DOUBLE_VALUE;
                case "google.protobuf.FloatValue" -> FLOAT_VALUE;
                case "google.protobuf.Int64Value" -> INT64_VALUE;
                case "google.protobuf.UInt64Value" -> UINT64_VALUE;
                case "google.protobuf.Int32Value" -> INT32_VALUE;
                case "google.protobuf.UInt32Value" -> UINT32_VALUE;
                case "google.protobuf.BoolValue" -> BOOL_VALUE;
                case "google.protobuf.StringValue" -> STRING_VALUE;
                case "google.protobuf.BytesValue" -> BYTES_VALUE;
                case "google.protobuf.Struct" -> STRUCT;
                case "google.protobuf.Value" -> VALUE;
                case "google.protobuf.ListValue" -> LIST_VALUE;
                case "google.protobuf.FieldMask" -> FIELD_MASK;
                default -> null;
            };
        }
    }

    static final class Entry {
        final FieldDescriptor fd;
        final int number;
        final int index;
        final SerializableString jsonName;
        final SerializableString protoName;
        final Kind kind;
        final boolean repeated;
        final boolean hasPresence;
        final int oneofIndex; // -1 when not a oneof member (synthetic proto3-optional oneofs excluded)
        final WireWkt wkt; // WIRE_WKT only
        Program nested; // MESSAGE, or a MAP whose values are messages — filled after construction

        // MAP only: the entry's key and value fields, their canonical tags, and the value's kind.
        final FieldDescriptor keyFd;
        final FieldDescriptor valueFd;
        final int keyTag;
        final int valueTag;
        final Kind valueKind;
        final WireWkt valueWkt;

        Entry(FieldDescriptor fd, int index, Kind kind) {
            this.fd = fd;
            this.number = fd.getNumber();
            this.index = index;
            this.jsonName = new SerializedString(fd.getJsonName());
            this.protoName = new SerializedString(fd.getName());
            this.kind = kind;
            this.repeated = fd.isRepeated();
            this.hasPresence = !fd.isRepeated() && fd.hasPresence();
            this.oneofIndex = fd.getRealContainingOneof() != null
                    ? fd.getRealContainingOneof().getIndex()
                    : -1;
            this.wkt = kind == Kind.WIRE_WKT ? WireWkt.of(fd.getMessageType()) : null;
            if (kind == Kind.MAP) {
                Descriptor entry = fd.getMessageType();
                this.keyFd = entry.findFieldByNumber(1);
                this.valueFd = entry.findFieldByNumber(2);
                this.keyTag = tag(1, keyFd.getLiteType().getWireType());
                this.valueTag = tag(2, valueFd.getLiteType().getWireType());
                this.valueKind = kindOf(valueFd);
                this.valueWkt = valueKind == Kind.WIRE_WKT ? WireWkt.of(valueFd.getMessageType()) : null;
            } else {
                this.keyFd = null;
                this.valueFd = null;
                this.keyTag = 0;
                this.valueTag = 0;
                this.valueKind = null;
                this.valueWkt = null;
            }
        }
    }

    /** The wire tag of a field number with a wire type, as {@code WireFormat.makeTag} builds it. */
    private static int tag(int fieldNumber, int wireType) {
        return (fieldNumber << 3) | wireType;
    }

    /** The kind of one non-map field; throws for the fields no program can carry. */
    private static Kind kindOf(FieldDescriptor fd) {
        if (fd.getType() == FieldDescriptor.Type.GROUP) {
            throw new UnsupportedOperationException("group field: " + fd.getFullName());
        }
        if (fd.getType() == FieldDescriptor.Type.MESSAGE) {
            Descriptor md = fd.getMessageType();
            if ("google.protobuf.Any".equals(md.getFullName())) {
                throw new UnsupportedOperationException("Any field: " + fd.getFullName());
            }
            if (!ProtoWkt.isWkt(md)) return Kind.MESSAGE;
            return WireWkt.of(md) != null ? Kind.WIRE_WKT : Kind.WKT;
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
        Entry[] entries = new Entry[fields.size()];
        Program program =
                new Program(descriptor, entries, descriptor.getRealOneofs().size());
        // Register before recursing so self-referential message types resolve.
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
        program.buildIndex();
        return program;
    }

    // ── Wire walking ─────────────────────────────────────────────────────────────────

    private void writeMessage(Program p, CodedInputStream cis, JsonGenerator gen, int depth, JsonPrintOptions opts)
            throws IOException {
        if (depth > MAX_DEPTH) throw Bail.INSTANCE;
        gen.writeStartObject();

        // Presence per field and per oneof: two longs for the common message, arrays past 64.
        long seenBits = 0L;
        long oneofBits = 0L;
        boolean[] seen = p.wide ? new boolean[p.entries.length] : null;
        boolean[] oneofSeen = p.wide && p.oneofCount > 0 ? new boolean[p.oneofCount] : null;

        int tag = cis.readTag();
        while (tag != 0) {
            int number = WireFormat.getTagFieldNumber(tag);
            Entry e = p.lookup(number);
            if (e == null) {
                if (!cis.skipField(tag)) break;
                tag = cis.readTag();
                continue;
            }
            // A field (or a second member of its oneof) reappearing needs merge semantics.
            if (p.wide) {
                if (seen[e.index]) throw Bail.INSTANCE;
                seen[e.index] = true;
                if (e.oneofIndex >= 0) {
                    if (oneofSeen[e.oneofIndex]) throw Bail.INSTANCE;
                    oneofSeen[e.oneofIndex] = true;
                }
            } else {
                long bit = 1L << e.index;
                if ((seenBits & bit) != 0) throw Bail.INSTANCE;
                seenBits |= bit;
                if (e.oneofIndex >= 0) {
                    long oneofBit = 1L << e.oneofIndex;
                    if ((oneofBits & oneofBit) != 0) throw Bail.INSTANCE;
                    oneofBits |= oneofBit;
                }
            }

            if (opts.omitDefaultValues() && !e.repeated && !e.hasPresence && e.kind != Kind.MAP) {
                // Read first: a value equal to the default is left out, name and all.
                Object value = readScalar(e.fd, cis);
                if (!isDefault(value)) {
                    gen.writeFieldName(name(e, opts));
                    writeScalarValue(e.fd, value, gen, opts);
                }
                tag = cis.readTag();
                continue;
            }
            gen.writeFieldName(name(e, opts));
            tag = writeField(e, cis, gen, tag, depth, opts);
        }

        if (!opts.omitDefaultValues()) {
            for (Entry e : p.entries) {
                boolean present = p.wide ? seen[e.index] : (seenBits & (1L << e.index)) != 0;
                if (!present) writeAbsentDefault(e, gen, opts);
            }
        }
        gen.writeEndObject();
    }

    private static SerializableString name(Entry e, JsonPrintOptions opts) {
        return opts.preserveProtoFieldNames() ? e.protoName : e.jsonName;
    }

    /** Writes one field's value(s), consuming the contiguous run for repeated/map fields. Returns the next tag. */
    private int writeField(Entry e, CodedInputStream cis, JsonGenerator gen, int tag, int depth, JsonPrintOptions opts)
            throws IOException {
        if (e.kind == Kind.MAP) {
            return writeMapRun(e, cis, gen, tag, depth, opts);
        }
        if (e.repeated) {
            return writeRepeatedRun(e, cis, gen, tag, depth, opts);
        }
        writeSingleValue(e, cis, gen, depth, opts);
        return cis.readTag();
    }

    private int writeRepeatedRun(
            Entry e, CodedInputStream cis, JsonGenerator gen, int tag, int depth, JsonPrintOptions opts)
            throws IOException {
        gen.writeStartArray();
        while (true) {
            if (e.fd.isPackable() && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                int length = cis.readRawVarint32();
                // An empty packed run is an empty list, which omitDefaultValues leaves out — but the
                // name is already written. Legal yet never emitted by real encoders: fall back.
                if (length == 0 && opts.omitDefaultValues()) throw Bail.INSTANCE;
                int oldLimit = cis.pushLimit(length);
                while (cis.getBytesUntilLimit() > 0) {
                    writeScalarElement(e.fd, cis, gen, opts);
                }
                cis.popLimit(oldLimit);
            } else {
                writeSingleValue(e, cis, gen, depth, opts);
            }
            tag = cis.readTag();
            if (tag == 0 || WireFormat.getTagFieldNumber(tag) != e.number) break;
        }
        gen.writeEndArray();
        return tag;
    }

    private void writeSingleValue(Entry e, CodedInputStream cis, JsonGenerator gen, int depth, JsonPrintOptions opts)
            throws IOException {
        switch (e.kind) {
            case MESSAGE -> writeNested(e.nested, cis, gen, depth, opts);
            case WIRE_WKT -> writeWireWkt(e.wkt, cis, gen, opts.numberFormat());
            case WKT ->
                ProtoWkt.writeJson(
                        gen, DynamicMessage.parseFrom(e.fd.getMessageType(), cis.readBytes()), opts.numberFormat());
            default -> writeScalarElement(e.fd, cis, gen, opts);
        }
    }

    /** A length-delimited submessage through its program. */
    private void writeNested(Program nested, CodedInputStream cis, JsonGenerator gen, int depth, JsonPrintOptions opts)
            throws IOException {
        int length = cis.readRawVarint32();
        int oldLimit = cis.pushLimit(length);
        writeMessage(nested, cis, gen, depth + 1, opts);
        cis.popLimit(oldLimit);
    }

    // ── Maps ──────────────────────────────────────────────────────────────────────────

    private int writeMapRun(Entry e, CodedInputStream cis, JsonGenerator gen, int tag, int depth, JsonPrintOptions opts)
            throws IOException {
        gen.writeStartObject();
        while (true) {
            if (WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_LENGTH_DELIMITED) throw Bail.INSTANCE;
            int length = cis.readRawVarint32();
            int oldLimit = cis.pushLimit(length);
            writeMapEntry(e, cis, gen, depth, opts);
            cis.popLimit(oldLimit);
            tag = cis.readTag();
            if (tag == 0 || WireFormat.getTagFieldNumber(tag) != e.number) break;
        }
        gen.writeEndObject();
        return tag;
    }

    /**
     * One map entry in its canonical layout — key then value, each at most once, as every
     * protobuf serializer writes it. A missing key or value is its default; any other layout
     * would need the entry merged first, so it bails.
     */
    private void writeMapEntry(Entry e, CodedInputStream cis, JsonGenerator gen, int depth, JsonPrintOptions opts)
            throws IOException {
        int tag = cis.readTag();
        if (tag == e.keyTag) {
            gen.writeFieldName(readMapKey(e.keyFd, cis));
            tag = cis.readTag();
        } else {
            gen.writeFieldName(defaultMapKey(e.keyFd));
        }
        if (tag == e.valueTag) {
            writeMapValue(e, cis, gen, depth, opts);
            tag = cis.readTag();
        } else {
            writeDefaultMapValue(e, gen, opts);
        }
        while (tag != 0) {
            int number = WireFormat.getTagFieldNumber(tag);
            if (number == 1 || number == 2) throw Bail.INSTANCE;
            if (!cis.skipField(tag)) break;
            tag = cis.readTag();
        }
    }

    /** The key as JSON object-key text, as {@code ProtoMapConverter.mapKeyToString} prints it. */
    private static String readMapKey(FieldDescriptor keyFd, CodedInputStream cis) throws IOException {
        return switch (keyFd.getType()) {
            case INT32 -> Integer.toString(cis.readInt32());
            case SINT32 -> Integer.toString(cis.readSInt32());
            case SFIXED32 -> Integer.toString(cis.readSFixed32());
            case UINT32 -> Long.toString(Integer.toUnsignedLong(cis.readUInt32()));
            case FIXED32 -> Long.toString(Integer.toUnsignedLong(cis.readFixed32()));
            case INT64 -> Long.toString(cis.readInt64());
            case SINT64 -> Long.toString(cis.readSInt64());
            case SFIXED64 -> Long.toString(cis.readSFixed64());
            case UINT64 -> Long.toUnsignedString(cis.readUInt64());
            case FIXED64 -> Long.toUnsignedString(cis.readFixed64());
            case BOOL -> Boolean.toString(cis.readBool());
            case STRING -> cis.readStringRequireUtf8();
            default -> throw Bail.INSTANCE;
        };
    }

    private static String defaultMapKey(FieldDescriptor keyFd) {
        return switch (keyFd.getType()) {
            case STRING -> "";
            case BOOL -> "false";
            default -> "0";
        };
    }

    private void writeMapValue(Entry e, CodedInputStream cis, JsonGenerator gen, int depth, JsonPrintOptions opts)
            throws IOException {
        switch (e.valueKind) {
            case MESSAGE -> writeNested(e.nested, cis, gen, depth, opts);
            case WIRE_WKT -> writeWireWkt(e.valueWkt, cis, gen, opts.numberFormat());
            case WKT ->
                ProtoWkt.writeJson(
                        gen,
                        DynamicMessage.parseFrom(e.valueFd.getMessageType(), cis.readBytes()),
                        opts.numberFormat());
            default -> writeScalarElement(e.valueFd, cis, gen, opts);
        }
    }

    private void writeDefaultMapValue(Entry e, JsonGenerator gen, JsonPrintOptions opts) throws IOException {
        switch (e.valueKind) {
            case MESSAGE -> {
                gen.writeStartObject();
                if (!opts.omitDefaultValues()) {
                    for (Entry nested : e.nested.entries) writeAbsentDefault(nested, gen, opts);
                }
                gen.writeEndObject();
            }
            case WIRE_WKT -> writeWireWktDefault(e.valueWkt, gen, opts.numberFormat());
            case WKT ->
                ProtoWkt.writeJson(
                        gen, DynamicMessage.getDefaultInstance(e.valueFd.getMessageType()), opts.numberFormat());
            default -> writeDefaultScalar(e.valueFd, gen, opts);
        }
    }

    // ── Well-known types off the wire ─────────────────────────────────────────────────

    private static final int TAG_1_VARINT = tag(1, WireFormat.WIRETYPE_VARINT);
    private static final int TAG_2_VARINT = tag(2, WireFormat.WIRETYPE_VARINT);
    private static final int TAG_1_FIXED64 = tag(1, WireFormat.WIRETYPE_FIXED64);
    private static final int TAG_1_FIXED32 = tag(1, WireFormat.WIRETYPE_FIXED32);
    private static final int TAG_1_BYTES = tag(1, WireFormat.WIRETYPE_LENGTH_DELIMITED);

    /**
     * A length-delimited Timestamp, Duration or wrapper, read field by field: a repeated
     * occurrence of its field is last-wins, as a parse would merge it, and a field with any
     * other tag is unknown and skipped.
     */
    private static void writeWireWkt(WireWkt kind, CodedInputStream cis, JsonGenerator gen, JsonNumberFormat nf)
            throws IOException {
        int length = cis.readRawVarint32();
        int oldLimit = cis.pushLimit(length);
        switch (kind) {
            case TIMESTAMP, DURATION -> {
                long seconds = 0L;
                int nanos = 0;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_VARINT) {
                        seconds = cis.readInt64();
                    } else if (tag == TAG_2_VARINT) {
                        nanos = cis.readInt32();
                    } else if (!cis.skipField(tag)) {
                        break;
                    }
                }
                gen.writeString(
                        kind == WireWkt.TIMESTAMP
                                ? ProtoWkt.timestampText(seconds, nanos)
                                : ProtoWkt.durationText(seconds, nanos));
            }
            case DOUBLE_VALUE -> {
                double v = 0d;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_FIXED64) v = cis.readDouble();
                    else if (!cis.skipField(tag)) break;
                }
                writeDouble(gen, v, nf);
            }
            case FLOAT_VALUE -> {
                float v = 0f;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_FIXED32) v = cis.readFloat();
                    else if (!cis.skipField(tag)) break;
                }
                writeFloat(gen, v, nf);
            }
            case INT64_VALUE, UINT64_VALUE -> {
                long v = 0L;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_VARINT) v = cis.readInt64();
                    else if (!cis.skipField(tag)) break;
                }
                if (kind == WireWkt.INT64_VALUE) ProtoMapConverter.writeInt64(gen, v);
                else ProtoMapConverter.writeUInt64(gen, v);
            }
            case INT32_VALUE, UINT32_VALUE, BOOL_VALUE -> {
                long v = 0L;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_VARINT) v = cis.readInt64();
                    else if (!cis.skipField(tag)) break;
                }
                switch (kind) {
                    case INT32_VALUE -> gen.writeNumber((int) v);
                    case UINT32_VALUE -> gen.writeNumber(Integer.toUnsignedLong((int) v));
                    default -> gen.writeBoolean(v != 0L);
                }
            }
            case STRING_VALUE -> {
                String v = "";
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_BYTES) v = cis.readStringRequireUtf8();
                    else if (!cis.skipField(tag)) break;
                }
                gen.writeString(v);
            }
            case BYTES_VALUE -> {
                byte[] v = null;
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_BYTES) v = cis.readByteArray();
                    else if (!cis.skipField(tag)) break;
                }
                gen.writeString(v == null ? "" : BASE64.encodeToString(v));
            }
            case STRUCT -> writeStruct(cis, gen, 0, nf);
            case VALUE -> writeValue(cis, gen, 0, nf);
            case LIST_VALUE -> writeListValue(cis, gen, 0, nf);
            case FIELD_MASK -> {
                // The JSON form converts each path to lowerCamelCase: protobuf-util's rule, kept
                // by handing it the paths as a FieldMask rather than reimplementing it.
                FieldMask.Builder mask = FieldMask.newBuilder();
                int tag;
                while ((tag = cis.readTag()) != 0) {
                    if (tag == TAG_1_BYTES) mask.addPaths(cis.readStringRequireUtf8());
                    else if (!cis.skipField(tag)) break;
                }
                gen.writeString(FieldMaskUtil.toJsonString(mask.build()));
            }
        }
        cis.popLimit(oldLimit);
    }

    // ── google.protobuf.Struct / Value / ListValue off the wire ───────────────────────

    private static final int TAG_2_FIXED64 = tag(2, WireFormat.WIRETYPE_FIXED64);
    private static final int TAG_2_BYTES = tag(2, WireFormat.WIRETYPE_LENGTH_DELIMITED);
    private static final int TAG_3_BYTES = tag(3, WireFormat.WIRETYPE_LENGTH_DELIMITED);
    private static final int TAG_4_VARINT = tag(4, WireFormat.WIRETYPE_VARINT);
    private static final int TAG_5_BYTES = tag(5, WireFormat.WIRETYPE_LENGTH_DELIMITED);
    private static final int TAG_6_BYTES = tag(6, WireFormat.WIRETYPE_LENGTH_DELIMITED);

    /**
     * The content of a Struct (the limit already pushed): its {@code fields} map entries as a JSON
     * object. A key repeated within one Struct is last-wins once parsed, which a stream cannot
     * express, so it bails; the entry layout rule is the same as for any map.
     */
    private static void writeStruct(CodedInputStream cis, JsonGenerator gen, int depth, JsonNumberFormat nf)
            throws IOException {
        if (depth > MAX_DEPTH) throw Bail.INSTANCE;
        gen.writeStartObject();
        java.util.HashSet<String> keys = null;
        int tag;
        while ((tag = cis.readTag()) != 0) {
            if (tag != TAG_1_BYTES) {
                if (!cis.skipField(tag)) break;
                continue;
            }
            int length = cis.readRawVarint32();
            int oldLimit = cis.pushLimit(length);
            int entryTag = cis.readTag();
            String key = "";
            if (entryTag == TAG_1_BYTES) {
                key = cis.readStringRequireUtf8();
                entryTag = cis.readTag();
            }
            if (keys == null) keys = new java.util.HashSet<>();
            if (!keys.add(key)) throw Bail.INSTANCE;
            gen.writeFieldName(key);
            if (entryTag == TAG_2_BYTES) {
                int valueLength = cis.readRawVarint32();
                int valueLimit = cis.pushLimit(valueLength);
                writeValue(cis, gen, depth + 1, nf);
                cis.popLimit(valueLimit);
                entryTag = cis.readTag();
            } else {
                gen.writeNull(); // an entry without a value: an unset Value, which is null
            }
            while (entryTag != 0) {
                int number = WireFormat.getTagFieldNumber(entryTag);
                if (number == 1 || number == 2) throw Bail.INSTANCE;
                if (!cis.skipField(entryTag)) break;
                entryTag = cis.readTag();
            }
            cis.popLimit(oldLimit);
        }
        gen.writeEndObject();
    }

    /**
     * The content of a Value (the limit already pushed): its one set member as the JSON value,
     * {@code null} when none is set. A second member on the wire would replace the first once
     * parsed, which a stream cannot express, so it bails.
     */
    private static void writeValue(CodedInputStream cis, JsonGenerator gen, int depth, JsonNumberFormat nf)
            throws IOException {
        if (depth > MAX_DEPTH) throw Bail.INSTANCE;
        boolean written = false;
        int tag;
        while ((tag = cis.readTag()) != 0) {
            int number = WireFormat.getTagFieldNumber(tag);
            if (number < 1 || number > 6) {
                if (!cis.skipField(tag)) break;
                continue;
            }
            if (written) throw Bail.INSTANCE;
            written = true;
            if (tag == TAG_1_VARINT) {
                cis.readEnum();
                gen.writeNull();
            } else if (tag == TAG_2_FIXED64) {
                writeDouble(gen, cis.readDouble(), nf);
            } else if (tag == TAG_3_BYTES) {
                gen.writeString(cis.readStringRequireUtf8());
            } else if (tag == TAG_4_VARINT) {
                gen.writeBoolean(cis.readBool());
            } else if (tag == TAG_5_BYTES) {
                int length = cis.readRawVarint32();
                int oldLimit = cis.pushLimit(length);
                writeStruct(cis, gen, depth + 1, nf);
                cis.popLimit(oldLimit);
            } else if (tag == TAG_6_BYTES) {
                int length = cis.readRawVarint32();
                int oldLimit = cis.pushLimit(length);
                writeListValue(cis, gen, depth + 1, nf);
                cis.popLimit(oldLimit);
            } else {
                throw Bail.INSTANCE; // a member with the wrong wire type: not a Value a parser would build
            }
        }
        if (!written) gen.writeNull();
    }

    /** The content of a ListValue (the limit already pushed): its values as a JSON array. */
    private static void writeListValue(CodedInputStream cis, JsonGenerator gen, int depth, JsonNumberFormat nf)
            throws IOException {
        if (depth > MAX_DEPTH) throw Bail.INSTANCE;
        gen.writeStartArray();
        int tag;
        while ((tag = cis.readTag()) != 0) {
            if (tag != TAG_1_BYTES) {
                if (!cis.skipField(tag)) break;
                continue;
            }
            int length = cis.readRawVarint32();
            int oldLimit = cis.pushLimit(length);
            writeValue(cis, gen, depth + 1, nf);
            cis.popLimit(oldLimit);
        }
        gen.writeEndArray();
    }

    private static void writeWireWktDefault(WireWkt kind, JsonGenerator gen, JsonNumberFormat nf) throws IOException {
        switch (kind) {
            case TIMESTAMP -> gen.writeString(ProtoWkt.timestampText(0L, 0));
            case DURATION -> gen.writeString(ProtoWkt.durationText(0L, 0));
            case DOUBLE_VALUE -> writeDouble(gen, 0d, nf);
            case FLOAT_VALUE -> writeFloat(gen, 0f, nf);
            case INT64_VALUE -> ProtoMapConverter.writeInt64(gen, 0L);
            case UINT64_VALUE -> ProtoMapConverter.writeUInt64(gen, 0L);
            case INT32_VALUE -> gen.writeNumber(0);
            case UINT32_VALUE -> gen.writeNumber(0L);
            case BOOL_VALUE -> gen.writeBoolean(false);
            case STRING_VALUE, BYTES_VALUE -> gen.writeString("");
            case STRUCT -> {
                gen.writeStartObject();
                gen.writeEndObject();
            }
            case VALUE -> gen.writeNull();
            case LIST_VALUE -> {
                gen.writeStartArray();
                gen.writeEndArray();
            }
            case FIELD_MASK -> gen.writeString("");
        }
    }

    // ── Scalars ───────────────────────────────────────────────────────────────────────

    private static void writeScalarElement(
            FieldDescriptor fd, CodedInputStream cis, JsonGenerator gen, JsonPrintOptions opts) throws IOException {
        switch (fd.getType()) {
            case INT32 -> gen.writeNumber(cis.readInt32());
            case SINT32 -> gen.writeNumber(cis.readSInt32());
            case SFIXED32 -> gen.writeNumber(cis.readSFixed32());
            case UINT32 -> gen.writeNumber(Integer.toUnsignedLong(cis.readUInt32()));
            case FIXED32 -> gen.writeNumber(Integer.toUnsignedLong(cis.readFixed32()));
            case INT64 -> ProtoMapConverter.writeInt64(gen, cis.readInt64());
            case SINT64 -> ProtoMapConverter.writeInt64(gen, cis.readSInt64());
            case SFIXED64 -> ProtoMapConverter.writeInt64(gen, cis.readSFixed64());
            case UINT64 -> ProtoMapConverter.writeUInt64(gen, cis.readUInt64());
            case FIXED64 -> ProtoMapConverter.writeUInt64(gen, cis.readFixed64());
            case BOOL -> gen.writeBoolean(cis.readBool());
            case FLOAT -> writeFloat(gen, cis.readFloat(), opts.numberFormat());
            case DOUBLE -> writeDouble(gen, cis.readDouble(), opts.numberFormat());
            case STRING -> gen.writeString(cis.readStringRequireUtf8());
            case BYTES -> gen.writeString(BASE64.encodeToString(cis.readByteArray()));
            case ENUM -> writeEnum(fd, cis.readEnum(), gen, opts);
            default -> throw Bail.INSTANCE;
        }
    }

    private static void writeEnum(FieldDescriptor fd, int value, JsonGenerator gen, JsonPrintOptions opts)
            throws IOException {
        EnumValueDescriptor ev = opts.enumsAsInts() ? null : fd.getEnumType().findValueByNumber(value);
        if (ev != null) {
            gen.writeString(ev.getName());
        } else {
            gen.writeNumber(value); // enums-as-ints, or an open enum's unknown value: the raw number
        }
    }

    /**
     * One singular scalar or enum, read as a value so {@code omitDefaultValues} can drop a default
     * before anything is written. Boxed: only that option takes this path.
     */
    private static Object readScalar(FieldDescriptor fd, CodedInputStream cis) throws IOException {
        return switch (fd.getType()) {
            case INT32 -> cis.readInt32();
            case SINT32 -> cis.readSInt32();
            case SFIXED32 -> cis.readSFixed32();
            case UINT32 -> cis.readUInt32();
            case FIXED32 -> cis.readFixed32();
            case INT64 -> cis.readInt64();
            case SINT64 -> cis.readSInt64();
            case SFIXED64 -> cis.readSFixed64();
            case UINT64 -> cis.readUInt64();
            case FIXED64 -> cis.readFixed64();
            case BOOL -> cis.readBool();
            case FLOAT -> cis.readFloat();
            case DOUBLE -> cis.readDouble();
            case STRING -> cis.readStringRequireUtf8();
            case BYTES -> cis.readByteArray();
            case ENUM -> cis.readEnum();
            default -> throw Bail.INSTANCE;
        };
    }

    /** Whether a value read by {@link #readScalar} is its type's default (0 bits for floats, as proto does). */
    private static boolean isDefault(Object value) {
        return switch (value) {
            case Integer i -> i == 0;
            case Long l -> l == 0L;
            case Boolean b -> !b;
            case Float f -> Float.floatToRawIntBits(f) == 0;
            case Double d -> Double.doubleToRawLongBits(d) == 0L;
            case String str -> str.isEmpty();
            case byte[] bytes -> bytes.length == 0;
            default -> false;
        };
    }

    private static void writeScalarValue(FieldDescriptor fd, Object value, JsonGenerator gen, JsonPrintOptions opts)
            throws IOException {
        switch (fd.getType()) {
            case INT32, SINT32, SFIXED32 -> gen.writeNumber((Integer) value);
            case UINT32, FIXED32 -> gen.writeNumber(Integer.toUnsignedLong((Integer) value));
            case INT64, SINT64, SFIXED64 -> ProtoMapConverter.writeInt64(gen, (Long) value);
            case UINT64, FIXED64 -> ProtoMapConverter.writeUInt64(gen, (Long) value);
            case BOOL -> gen.writeBoolean((Boolean) value);
            case FLOAT -> writeFloat(gen, (Float) value, opts.numberFormat());
            case DOUBLE -> writeDouble(gen, (Double) value, opts.numberFormat());
            case STRING -> gen.writeString((String) value);
            case BYTES -> gen.writeString(BASE64.encodeToString((byte[]) value));
            case ENUM -> writeEnum(fd, (Integer) value, gen, opts);
            default -> throw Bail.INSTANCE;
        }
    }

    private static void writeFloat(JsonGenerator gen, float v, JsonNumberFormat nf) throws IOException {
        if (Float.isFinite(v)) {
            JsonNumbers.writeFloat(gen, v, nf);
        } else {
            gen.writeString(Float.isNaN(v) ? "NaN" : (v > 0 ? "Infinity" : "-Infinity"));
        }
    }

    private static void writeDouble(JsonGenerator gen, double v, JsonNumberFormat nf) throws IOException {
        if (Double.isFinite(v)) {
            JsonNumbers.writeDouble(gen, v, nf);
        } else {
            gen.writeString(Double.isNaN(v) ? "NaN" : (v > 0 ? "Infinity" : "-Infinity"));
        }
    }

    /** Mirrors JsonFormat.includingDefaultValueFields for one field absent from the wire. */
    private static void writeAbsentDefault(Entry e, JsonGenerator gen, JsonPrintOptions opts) throws IOException {
        // hasPresence covers singular message/WKT/optional/oneof fields — omitted when unset.
        // Repeated message/WKT fields fall through to the empty-array branch below.
        if (e.hasPresence) return;
        gen.writeFieldName(name(e, opts));
        if (e.kind == Kind.MAP) {
            gen.writeStartObject();
            gen.writeEndObject();
        } else if (e.repeated) {
            gen.writeStartArray();
            gen.writeEndArray();
        } else {
            writeDefaultScalar(e.fd, gen, opts);
        }
    }

    private static void writeDefaultScalar(FieldDescriptor fd, JsonGenerator gen, JsonPrintOptions opts)
            throws IOException {
        switch (fd.getType()) {
            case INT32, SINT32, SFIXED32, UINT32, FIXED32 -> gen.writeNumber(0);
            case INT64, SINT64, SFIXED64, UINT64, FIXED64 -> ProtoMapConverter.writeInt64(gen, 0L);
            case BOOL -> gen.writeBoolean(false);
            case FLOAT, DOUBLE -> JsonNumbers.writeDouble(gen, 0.0, opts.numberFormat());
            case STRING, BYTES -> gen.writeString("");
            case ENUM -> writeEnum(fd, 0, gen, opts);
            default -> throw Bail.INSTANCE;
        }
    }
}
