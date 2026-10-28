package dev.appiaworks.shiguchi.avro;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.config.AvroSurfaceConfig.Uint64Mapping;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.JsonProperties;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;

/**
 * Derives an Avro schema from a proto message descriptor, once per message, for the gateway's
 * own REST surface: what a caller asking for {@code avro/binary} receives, and what it must send.
 *
 * <p>Mapping, the inverse of the downstream table with the lossy edges called out:
 *
 * <table>
 *   <tr><th>proto</th><th>Avro</th></tr>
 *   <tr><td>proto3 scalar (no presence)</td><td>the scalar, with its zero value as default; never null</td></tr>
 *   <tr><td>{@code optional} / proto2 optional scalar, message, wrapper type</td><td>{@code ["null", T]}, default null</td></tr>
 *   <tr><td>repeated</td><td>array, default empty</td></tr>
 *   <tr><td>map</td><td>map with string keys (numeric and bool keys are stringified), default empty</td></tr>
 *   <tr><td>enum</td><td>enum with the proto value names, default the zero value</td></tr>
 *   <tr><td>int32, sint32, sfixed32</td><td>int</td></tr>
 *   <tr><td>uint32, fixed32</td><td>long</td></tr>
 *   <tr><td>int64, sint64, sfixed64</td><td>long</td></tr>
 *   <tr><td>uint64, fixed64</td><td>long (wraps above 2^63) or string, per {@code shiguchi.avro.uint64-as}</td></tr>
 *   <tr><td>google.protobuf.Timestamp</td><td>long timestamp-micros (nanos truncated)</td></tr>
 *   <tr><td>google.protobuf.Duration</td><td>string, the JSON mapping's text</td></tr>
 *   <tr><td>google.type Date, TimeOfDay, DateTime, Decimal (while {@code shiguchi.json.scalar-types} maps them)</td><td>string, the same ISO text as JSON</td></tr>
 *   <tr><td>google.type.Money (while mapped)</td><td>record {@code {currency, amount}}, amount a decimal string</td></tr>
 *   <tr><td>Struct, Value, ListValue, Any, FieldMask</td><td>string holding JSON</td></tr>
 *   <tr><td>bytes</td><td>bytes</td></tr>
 *   <tr><td>nested message {@code Outer.Inner}</td><td>record {@code Outer_Inner} in the proto package's namespace</td></tr>
 *   <tr><td>recursive message</td><td>the record referenced by name</td></tr>
 * </table>
 */
public class ProtoToAvroSchema {

    private final Uint64Mapping uint64;
    private final Map<String, Schema> byMessage = new ConcurrentHashMap<>();
    private final Map<Long, Schema> byFingerprint = new ConcurrentHashMap<>();

    public ProtoToAvroSchema() {
        this(Uint64Mapping.LONG);
    }

    public ProtoToAvroSchema(Uint64Mapping uint64) {
        this.uint64 = uint64 != null ? uint64 : Uint64Mapping.LONG;
    }

    /** The record schema for {@code descriptor}, derived on first use and cached by full name. */
    public Schema schemaFor(Descriptor descriptor) {
        Schema cached = byMessage.get(descriptor.getFullName());
        if (cached != null) return cached;
        Schema built = build(descriptor, new HashMap<>());
        Schema previous = byMessage.putIfAbsent(descriptor.getFullName(), built);
        Schema result = previous != null ? previous : built;
        byFingerprint.putIfAbsent(AvroSchemaRegistry.fingerprint(result), result);
        return result;
    }

    /** A schema already derived, by proto full name. */
    public Optional<Schema> find(String messageFullName) {
        return Optional.ofNullable(byMessage.get(messageFullName));
    }

    /** A schema already derived, by its CRC-64-AVRO fingerprint. */
    public Optional<Schema> byFingerprint(long fingerprint) {
        return Optional.ofNullable(byFingerprint.get(fingerprint));
    }

    public Collection<Schema> known() {
        return byMessage.values();
    }

    // ------------------------------------------------------------------

    private Schema build(Descriptor d, Map<String, Schema> inProgress) {
        Schema known = byMessage.get(d.getFullName());
        if (known != null) return known;
        Schema started = inProgress.get(d.getFullName());
        if (started != null) return started;

        Schema record = Schema.createRecord(
                avroName(d.getFullName(), d.getFile().getPackage()),
                null,
                namespace(d.getFile().getPackage()),
                false);
        inProgress.put(d.getFullName(), record);
        List<Schema.Field> fields = new ArrayList<>();
        for (FieldDescriptor fd : d.getFields()) {
            fields.add(field(fd, inProgress));
        }
        record.setFields(fields);
        return record;
    }

    private Schema.Field field(FieldDescriptor fd, Map<String, Schema> inProgress) {
        String name = fd.getJsonName();
        if (fd.isMapField()) {
            Schema value = singular(fd.getMessageType().findFieldByName("value"), inProgress, false);
            return new Schema.Field(name, Schema.createMap(value), null, Map.of());
        }
        if (fd.isRepeated()) {
            return new Schema.Field(name, Schema.createArray(singular(fd, inProgress, false)), null, List.of());
        }
        boolean nullable = fd.getType() == FieldDescriptor.Type.MESSAGE || fd.hasPresence();
        if (nullable) {
            Schema inner = singular(fd, inProgress, true);
            return new Schema.Field(
                    name, Schema.createUnion(Schema.create(Schema.Type.NULL), inner), null, JsonProperties.NULL_VALUE);
        }
        Schema inner = singular(fd, inProgress, false);
        return new Schema.Field(name, inner, null, defaultFor(fd, inner));
    }

    /** The non-null schema of one value of {@code fd}. */
    private Schema singular(FieldDescriptor fd, Map<String, Schema> inProgress, boolean unwrapWrappers) {
        switch (fd.getType()) {
            case MESSAGE: {
                Descriptor md = fd.getMessageType();
                String full = md.getFullName();
                if (ProtoJsonMapping.isMapped(md)) return googleType(md);
                switch (full) {
                    case "google.protobuf.Timestamp":
                        return LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG));
                    case "google.protobuf.Duration":
                    case "google.protobuf.Struct":
                    case "google.protobuf.Value":
                    case "google.protobuf.ListValue":
                    case "google.protobuf.Any":
                    case "google.protobuf.FieldMask":
                    case "google.protobuf.StringValue":
                        return Schema.create(Schema.Type.STRING);
                    case "google.protobuf.BytesValue":
                        return Schema.create(Schema.Type.BYTES);
                    case "google.protobuf.BoolValue":
                        return Schema.create(Schema.Type.BOOLEAN);
                    case "google.protobuf.Int32Value":
                        return Schema.create(Schema.Type.INT);
                    case "google.protobuf.UInt32Value":
                    case "google.protobuf.Int64Value":
                        return Schema.create(Schema.Type.LONG);
                    case "google.protobuf.UInt64Value":
                        return uint64 == Uint64Mapping.STRING
                                ? Schema.create(Schema.Type.STRING)
                                : Schema.create(Schema.Type.LONG);
                    case "google.protobuf.FloatValue":
                        return Schema.create(Schema.Type.FLOAT);
                    case "google.protobuf.DoubleValue":
                        return Schema.create(Schema.Type.DOUBLE);
                    default:
                        return build(md, inProgress);
                }
            }
            case ENUM:
                return enumSchema(fd.getEnumType());
            case INT32, SINT32, SFIXED32:
                return Schema.create(Schema.Type.INT);
            case UINT32, FIXED32, INT64, SINT64, SFIXED64:
                return Schema.create(Schema.Type.LONG);
            case UINT64, FIXED64:
                return uint64 == Uint64Mapping.STRING
                        ? Schema.create(Schema.Type.STRING)
                        : Schema.create(Schema.Type.LONG);
            case FLOAT:
                return Schema.create(Schema.Type.FLOAT);
            case DOUBLE:
                return Schema.create(Schema.Type.DOUBLE);
            case BOOL:
                return Schema.create(Schema.Type.BOOLEAN);
            case BYTES:
                return Schema.create(Schema.Type.BYTES);
            case STRING:
            default:
                return Schema.create(Schema.Type.STRING);
        }
    }

    /**
     * A google.type message shiguchi.json.scalar-types writes as text is a string here too,
     * the same ISO text: Avro's date and time logical types cannot hold the partial dates,
     * {@code 24:00:00} or zone ids those messages can. Money is its flattened
     * {@code {currency, amount}} record.
     */
    private Schema googleType(Descriptor md) {
        // A message an application's ProtoScalarCodec writes as one value; an object or array is
        // a string holding JSON, as a Struct is.
        ProtoScalarCodec custom = ProtoJsonMapping.customCodecFor(md);
        if (custom != null) {
            return switch (custom.schema().shape()) {
                case INTEGER -> Schema.create(Schema.Type.LONG);
                case NUMBER -> Schema.create(Schema.Type.DOUBLE);
                case BOOLEAN -> Schema.create(Schema.Type.BOOLEAN);
                case STRING, ANY -> Schema.create(Schema.Type.STRING);
            };
        }
        if (!ProtoJsonMapping.MONEY.equals(md.getFullName())) return Schema.create(Schema.Type.STRING);
        return googleTypes.computeIfAbsent(md.getFullName(), k -> {
            Schema money = Schema.createRecord(
                    avroName(md.getFullName(), md.getFile().getPackage()),
                    "Money as {currency, amount}; amount is a decimal string",
                    namespace(md.getFile().getPackage()),
                    false);
            money.setFields(List.of(
                    new Schema.Field("currency", Schema.create(Schema.Type.STRING), null, ""),
                    new Schema.Field("amount", Schema.create(Schema.Type.STRING), null, "0")));
            return money;
        });
    }

    private final Map<String, Schema> enums = new ConcurrentHashMap<>();

    /** Flattened google.type records, kept apart from {@link #byMessage}: they are not message schemas. */
    private final Map<String, Schema> googleTypes = new ConcurrentHashMap<>();

    private Schema enumSchema(EnumDescriptor ed) {
        return enums.computeIfAbsent(ed.getFullName(), k -> {
            List<String> symbols = new ArrayList<>();
            for (EnumValueDescriptor v : ed.getValues()) if (!symbols.contains(v.getName())) symbols.add(v.getName());
            String zero =
                    ed.findValueByNumber(0) != null ? ed.findValueByNumber(0).getName() : symbols.get(0);
            return Schema.createEnum(
                    avroName(ed.getFullName(), ed.getFile().getPackage()),
                    null,
                    namespace(ed.getFile().getPackage()),
                    symbols,
                    zero);
        });
    }

    private static Object defaultFor(FieldDescriptor fd, Schema schema) {
        return switch (schema.getType()) {
            case INT -> 0;
            case LONG -> 0L;
            case FLOAT -> 0.0f;
            case DOUBLE -> 0.0d;
            case BOOLEAN -> false;
            case BYTES -> "";
            case ENUM -> schema.getEnumDefault();
            default -> "";
        };
    }

    /** {@code pkg.Outer.Inner} → {@code Outer_Inner}: Avro names cannot contain dots. */
    static String avroName(String fullName, String pkg) {
        String local = pkg == null || pkg.isEmpty() ? fullName : fullName.substring(pkg.length() + 1);
        return local.replace('.', '_');
    }

    private static String namespace(String pkg) {
        return pkg == null || pkg.isEmpty() ? null : pkg;
    }
}
