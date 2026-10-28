package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.WireFormat;
import dev.appiaworks.protobuf.extras.serde.JsonWireTranscoder;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.WireBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Encodes a REST request — JSON body, path variables, query parameters — straight to the wire
 * bytes of the gRPC request message, skipping the request {@code Map} and the {@code
 * DynamicMessage} the reflective path builds and serialises.
 *
 * <p>The body streams through {@link JsonWireTranscoder}; path and query values are appended
 * after it as fields of their own, which is how they win: a singular scalar on the wire twice is
 * last-wins, and a nested one merges into its submessage, exactly the precedence the {@code Map}
 * overlay gives them. The result parses to the same message the reflective path would build.
 *
 * <p>It only takes the requests it can encode identically; {@link #encode} returns null for the
 * rest and the caller builds the {@code Map}. That is the case for: a named {@code body} field in
 * the HTTP rule; any gateway convention parameter ({@code pageSize}, {@code sort},
 * {@code fields}, ...); a parameter whose value the reflective path would coerce rather than
 * parse; a parameter that targets a field the body already set unless the target is a scalar
 * inside messages (where append and overlay agree); and everything the transcoder bails on.
 */
public final class DirectRequestEncoder {

    private final JsonWireTranscoder json = new JsonWireTranscoder();
    private final boolean caseInsensitiveEnums;

    public DirectRequestEncoder(boolean caseInsensitiveEnums) {
        this.caseInsensitiveEnums = caseInsensitiveEnums;
    }

    /** Signals a request the reflective path must handle. */
    private static final class Bail extends RuntimeException {
        static final Bail INSTANCE = new Bail();

        private Bail() {
            super(null, null, false, false);
        }
    }

    /**
     * The request message's wire bytes, or null when this request needs the {@code Map} path.
     *
     * @param bodyField the HTTP rule's {@code body}; only {@code "*"}/null (the whole message) is
     *     taken here
     */
    public byte[] encode(
            Descriptor input,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            String bodyField) {
        if (GrpcRequestBuilder.isNamedBodyField(bodyField)) return null;
        JsonWireTranscoder.Program program = json.programFor(input);
        if (program == null) return null;
        for (String key : queryParams.keySet()) {
            if (GrpcRequestBuilder.isGatewayParam(key)) return null;
        }

        WireBuffer out = new WireBuffer(256);
        long seen = 0L;
        if (body != null && !body.isBlank() && !body.equals("{}")) {
            seen = json.transcode(program, body, out, caseInsensitiveEnums);
            if (seen < 0) return null;
        }
        try {
            for (var entry : queryParams.entrySet()) {
                writeParam(input, program, seen, entry.getKey(), entry.getValue(), queryParams, out);
            }
            for (var entry : pathVars.entrySet()) {
                writeParam(input, program, seen, entry.getKey(), entry.getValue(), null, out);
            }
        } catch (Bail bail) {
            return null;
        }
        return out.toByteArray();
    }

    /**
     * One parameter as a field (nested along its dotted key). A key no field matches is skipped,
     * as the overlay's unknown map keys are ignored by the parse.
     */
    private void writeParam(
            Descriptor input,
            JsonWireTranscoder.Program program,
            long bodySeen,
            String key,
            String value,
            Map<String, String> multiValued,
            WireBuffer out) {
        String[] segments = key.split("\\.", -1);
        List<FieldDescriptor> path = new ArrayList<>(segments.length);
        Descriptor type = input;
        for (String segment : segments) {
            if (type == null) return;
            FieldDescriptor fd = QueryParameterValidator.field(type, segment);
            if (fd == null) return;
            path.add(fd);
            type = fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !fd.isMapField()
                    ? fd.getMessageType()
                    : null;
        }
        FieldDescriptor leaf = path.get(path.size() - 1);
        FieldDescriptor top = path.get(0);
        boolean bodySetTop = (bodySeen & (1L << top.getIndex())) != 0;
        if (bodySetTop) {
            // Append merges where the overlay replaces: only a scalar leaf reached through
            // singular messages agrees with it.
            if (leaf.isRepeated() || leaf.getJavaType() == FieldDescriptor.JavaType.MESSAGE) throw Bail.INSTANCE;
            for (int i = 0; i < path.size() - 1; i++) {
                if (path.get(i).isRepeated()) throw Bail.INSTANCE;
            }
        }
        for (int i = 0; i < path.size() - 1; i++) {
            if (path.get(i).isRepeated()) throw Bail.INSTANCE; // the overlay cannot address these either
        }

        if (leaf.isMapField()) throw Bail.INSTANCE;
        if (leaf.isRepeated()) {
            if (leaf.getJavaType() == FieldDescriptor.JavaType.MESSAGE) throw Bail.INSTANCE;
            // A path variable cannot fill a repeated field (the overlay's String is not a List); a
            // query parameter takes every value of its name.
            if (multiValued == null) return;
            List<String> values = QueryParameters.all(multiValued, key);
            int[] marks = openPath(path, out);
            if (leaf.isPacked()) {
                out.writeTag(leaf.getNumber(), WireFormat.WIRETYPE_LENGTH_DELIMITED);
                int mark = out.beginLengthDelimited();
                for (String v : values) writeScalarNoTag(leaf, v, out);
                out.endLengthDelimited(mark);
            } else {
                for (String v : values) {
                    out.writeTag(leaf.getNumber(), leaf.getLiteType().getWireType());
                    writeScalarNoTag(leaf, v, out);
                }
            }
            closePath(marks, out);
            return;
        }

        int[] marks = openPath(path, out);
        if (leaf.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            Descriptor md = leaf.getMessageType();
            if (!ProtoWkt.isWkt(md)) throw Bail.INSTANCE; // a String for a message: the overlay drops it
            DynamicMessage wkt;
            try {
                wkt = ProtoWkt.fromJava(value, md);
            } catch (RuntimeException malformed) {
                throw Bail.INSTANCE;
            }
            if (wkt != null) {
                out.writeTag(leaf.getNumber(), WireFormat.WIRETYPE_LENGTH_DELIMITED);
                out.writeBytes(wkt.toByteArray());
            }
        } else {
            out.writeTag(leaf.getNumber(), leaf.getLiteType().getWireType());
            writeScalarNoTag(leaf, value, out);
        }
        closePath(marks, out);
    }

    /** Opens the submessages a dotted key descends through; returns their length marks. */
    private static int[] openPath(List<FieldDescriptor> path, WireBuffer out) {
        int[] marks = new int[path.size() - 1];
        for (int i = 0; i < marks.length; i++) {
            out.writeTag(path.get(i).getNumber(), WireFormat.WIRETYPE_LENGTH_DELIMITED);
            marks[i] = out.beginLengthDelimited();
        }
        return marks;
    }

    private static void closePath(int[] marks, WireBuffer out) {
        for (int i = marks.length - 1; i >= 0; i--) out.endLengthDelimited(marks[i]);
    }

    /**
     * A parameter's text as the field's value: a whole number in range, a float, {@code
     * true}/{@code false}, text, base64, an enum value name. Anything the reflective path would
     * coerce or drop (a fraction for an int, an unknown name) bails to it.
     */
    private void writeScalarNoTag(FieldDescriptor fd, String value, WireBuffer out) {
        try {
            switch (fd.getType()) {
                case INT32 -> out.writeVarint64(int32(value));
                case SINT32 -> out.writeSInt32(int32(value));
                case SFIXED32 -> out.writeFixed32(int32(value));
                case UINT32 -> out.writeVarint32(uint32(value));
                case FIXED32 -> out.writeFixed32(uint32(value));
                case INT64 -> out.writeVarint64(Long.parseLong(value));
                case SINT64 -> out.writeSInt64(Long.parseLong(value));
                case SFIXED64 -> out.writeFixed64(Long.parseLong(value));
                case UINT64 -> out.writeVarint64(Long.parseUnsignedLong(value));
                case FIXED64 -> out.writeFixed64(Long.parseUnsignedLong(value));
                case BOOL -> {
                    if ("true".equals(value)) out.writeBool(true);
                    else if ("false".equals(value)) out.writeBool(false);
                    else throw Bail.INSTANCE;
                }
                case FLOAT -> out.writeFixed32(Float.floatToRawIntBits((float) Double.parseDouble(value)));
                case DOUBLE -> out.writeFixed64(Double.doubleToRawLongBits(Double.parseDouble(value)));
                case STRING -> {
                    if (!out.writeString(value)) throw Bail.INSTANCE;
                }
                case BYTES -> out.writeBytes(Base64.getDecoder().decode(value));
                case ENUM -> {
                    EnumValueDescriptor ev = fd.getEnumType().findValueByName(value);
                    if (ev == null && caseInsensitiveEnums) {
                        for (EnumValueDescriptor candidate : fd.getEnumType().getValues()) {
                            if (candidate.getName().equalsIgnoreCase(value)) {
                                ev = candidate;
                                break;
                            }
                        }
                    }
                    if (ev == null) throw Bail.INSTANCE;
                    out.writeVarint64(ev.getNumber());
                }
                default -> throw Bail.INSTANCE;
            }
        } catch (IllegalArgumentException notParsable) { // NumberFormatException, bad base64
            throw Bail.INSTANCE;
        }
    }

    private static int int32(String value) {
        long v = Long.parseLong(value);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw Bail.INSTANCE;
        return (int) v;
    }

    private static int uint32(String value) {
        long v = Long.parseLong(value);
        if (v < 0 || v > 0xFFFFFFFFL) throw Bail.INSTANCE;
        return (int) v;
    }
}
