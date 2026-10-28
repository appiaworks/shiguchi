package dev.appiaworks.shiguchi.openapi;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ScalarSchema;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Converts proto Descriptors to OpenAPI 3 Schema objects.
 *
 * Maintains a components schema map so that message types referenced in multiple
 * operations are emitted once as $ref components rather than inlined repeatedly.
 *
 * <p>Response schemas follow the service's {@link JsonPrintOptions}: proto field names under
 * {@code preserve-proto-field-names}, enum numbers under {@code enums-as-ints}. A message printed
 * that way is its own component with a suffix ({@code Book_proto_names},
 * {@code Book_enums_as_ints}, {@code Book_proto_names_enums_as_ints}), because the same message
 * is still a request in the default spelling, and another service may print it differently.
 * {@code omit-default-values} changes no schema: no property is {@code required} either way.
 */
@Component
public class ProtoToOpenApiSchemaConverter {

    private static final int MAX_DEPTH = 10;

    /**
     * Returns a $ref Schema pointing to the named component, registering the full
     * schema in componentsSchemas if it has not already been emitted.
     */
    public Schema<?> schemaForDescriptor(
            Descriptor descriptor, Map<String, Schema<?>> componentsSchemas, Set<String> visited) {
        return schemaForDescriptor(descriptor, componentsSchemas, visited, 0, JsonPrintOptions.DEFAULT);
    }

    /**
     * A $ref to the message as a response printed with {@code print}; the default component when
     * those options do not change its shape.
     */
    public Schema<?> responseSchemaForDescriptor(
            Descriptor descriptor,
            Map<String, Schema<?>> componentsSchemas,
            Set<String> visited,
            JsonPrintOptions print) {
        return schemaForDescriptor(
                descriptor, componentsSchemas, visited, 0, print == null ? JsonPrintOptions.DEFAULT : print);
    }

    private Schema<?> schemaForDescriptor(
            Descriptor descriptor,
            Map<String, Schema<?>> componentsSchemas,
            Set<String> visited,
            int depth,
            JsonPrintOptions print) {

        String name = descriptor.getName() + componentSuffix(print);

        if (isWellKnown(descriptor)) {
            return wellKnownSchema(descriptor);
        }

        if (!visited.contains(name)) {
            visited.add(name);
            if (depth <= MAX_DEPTH) {
                Schema<?> schema = buildObjectSchema(descriptor, componentsSchemas, visited, depth, print);
                componentsSchemas.putIfAbsent(name, schema);
            }
        }

        Schema<?> ref = new Schema<>();
        ref.set$ref("#/components/schemas/" + name);
        return ref;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> buildObjectSchema(
            Descriptor descriptor,
            Map<String, Schema<?>> componentsSchemas,
            Set<String> visited,
            int depth,
            JsonPrintOptions print) {

        Schema objectSchema = new Schema();
        objectSchema.setType("object");

        Map<String, Schema> properties = new LinkedHashMap<>();
        for (FieldDescriptor field : descriptor.getFields()) {
            Schema fieldSchema = fieldSchema(field, componentsSchemas, visited, depth + 1, print);
            properties.put(print.fieldName(field), fieldSchema);
        }
        objectSchema.setProperties((Map) properties);
        return objectSchema;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Schema<?> fieldSchema(
            FieldDescriptor field, Map<String, Schema<?>> componentsSchemas, Set<String> visited, int depth) {
        return fieldSchema(field, componentsSchemas, visited, depth, JsonPrintOptions.DEFAULT);
    }

    /**
     * One field of a response as {@code response_body} sends it, laid out by the service's print
     * options like the rest of its responses.
     */
    public Schema<?> responseFieldSchema(
            FieldDescriptor field,
            Map<String, Schema<?>> componentsSchemas,
            Set<String> visited,
            JsonPrintOptions print) {
        return fieldSchema(field, componentsSchemas, visited, 0, print == null ? JsonPrintOptions.DEFAULT : print);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> fieldSchema(
            FieldDescriptor field,
            Map<String, Schema<?>> componentsSchemas,
            Set<String> visited,
            int depth,
            JsonPrintOptions print) {

        Schema<?> itemSchema = baseSchema(field, componentsSchemas, visited, depth, print);

        if (field.isRepeated()) {
            ArraySchema array = new ArraySchema();
            array.setItems((Schema) itemSchema);
            return array;
        }
        return itemSchema;
    }

    private Schema<?> baseSchema(
            FieldDescriptor field,
            Map<String, Schema<?>> componentsSchemas,
            Set<String> visited,
            int depth,
            JsonPrintOptions print) {

        return switch (field.getType()) {
            case STRING -> stringSchema(null);
            case BYTES -> stringSchema("byte");
            case INT32, UINT32, FIXED32, SFIXED32, SINT32 -> intSchema("int32");
            case INT64, SFIXED64, SINT64 -> int64Schema("int64");
            case UINT64, FIXED64 -> int64Schema("uint64");
            case FLOAT -> numberSchema("float");
            case DOUBLE -> numberSchema("double");
            case BOOL -> boolSchema();
            case ENUM -> print.enumsAsInts() ? enumNumberSchema(field.getEnumType()) : enumSchema(field.getEnumType());
            case MESSAGE -> {
                if (isWellKnown(field.getMessageType())) yield wellKnownSchema(field.getMessageType());
                yield schemaForDescriptor(field.getMessageType(), componentsSchemas, visited, depth, print);
            }
            default -> stringSchema(null);
        };
    }

    // -------------------------------------------------------------------------
    // Primitive schema builders
    // -------------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private static Schema<?> stringSchema(String format) {
        Schema s = new Schema();
        s.setType("string");
        if (format != null) s.setFormat(format);
        return s;
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> intSchema(String format) {
        Schema s = new Schema();
        s.setType("integer");
        s.setFormat(format);
        return s;
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> numberSchema(String format) {
        Schema s = new Schema();
        s.setType("number");
        s.setFormat(format);
        return s;
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> boolSchema() {
        Schema s = new Schema();
        s.setType("boolean");
        return s;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> enumSchema(EnumDescriptor enumDescriptor) {
        Schema s = new Schema();
        s.setType("string");
        enumDescriptor.getValues().forEach(v -> s.addEnumItemObject(v.getName()));
        return s;
    }

    /** Enum values as their numbers, the names kept in the description. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> enumNumberSchema(EnumDescriptor enumDescriptor) {
        Schema s = intSchema("int32");
        StringBuilder names = new StringBuilder();
        enumDescriptor.getValues().forEach(v -> {
            s.addEnumItemObject(v.getNumber());
            if (!names.isEmpty()) names.append(", ");
            names.append(v.getNumber()).append(" = ").append(v.getName());
        });
        s.setDescription(names.toString());
        return s;
    }

    /** The component-name suffix for a response printed with {@code print}; empty for the default shape. */
    static String componentSuffix(JsonPrintOptions print) {
        if (print == null) return "";
        return (print.preserveProtoFieldNames() ? "_proto_names" : "") + (print.enumsAsInts() ? "_enums_as_ints" : "");
    }

    @SuppressWarnings("rawtypes")
    private static Schema<?> wellKnownSchema(Descriptor descriptor) {
        Schema<?> googleType = googleTypeSchema(descriptor);
        if (googleType != null) return googleType;
        return switch (descriptor.getFullName()) {
            case "google.protobuf.Timestamp" ->
                described(stringSchema("date-time"), "RFC 3339, e.g. 2026-09-22T14:03:11.123Z");
            case "google.protobuf.Duration" -> durationSchema();
            // Wrappers unwrap to their value's JSON form: a number stays a number, a bool a bool.
            case "google.protobuf.Int32Value" -> intSchema("int32");
            case "google.protobuf.UInt32Value" -> intSchema("int64"); // unsigned, may exceed int32
            case "google.protobuf.Int64Value" -> int64Schema("int64"); // string unless int64-as: NUMBER
            case "google.protobuf.UInt64Value" -> int64Schema("uint64");
            case "google.protobuf.FloatValue" -> numberSchema("float");
            case "google.protobuf.DoubleValue" -> numberSchema("double");
            case "google.protobuf.BoolValue" -> boolSchema();
            case "google.protobuf.StringValue" -> stringSchema(null);
            case "google.protobuf.BytesValue" -> stringSchema("byte");
            case "google.protobuf.FieldMask" ->
                described(stringSchema(null), "Comma-separated camelCase field paths, e.g. title,author.name");
            case "google.protobuf.Empty" -> {
                Schema s = new Schema();
                s.setType("object");
                yield s;
            }
            // Any JSON value: no type constraint.
            case "google.protobuf.Value" -> new Schema<>();
            default ->
                switch (descriptor.getName()) {
                    case "Any", "Struct" -> {
                        Schema s = new Schema();
                        s.setType("object");
                        yield s;
                    }
                    case "ListValue" -> {
                        ArraySchema a = new ArraySchema();
                        a.setItems(new Schema<>());
                        yield a;
                    }
                    default -> stringSchema(null);
                };
        };
    }

    /**
     * The google.type messages shiguchi.json.scalar-types writes as text, and the messages an
     * application's ProtoScalarCodec writes as one value; null for any other type.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> googleTypeSchema(Descriptor descriptor) {
        if (!ProtoJsonMapping.isMapped(descriptor)) return null;
        ProtoScalarCodec custom = ProtoJsonMapping.customCodecFor(descriptor);
        if (custom != null) return codecSchema(custom.schema());
        return switch (descriptor.getFullName()) {
            case ProtoJsonMapping.DATE ->
                described(
                        stringSchema("date"),
                        "ISO-8601 date, e.g. 2026-09-22; partial forms 2026-09, 2026 and --09-22 also occur");
            case ProtoJsonMapping.TIME_OF_DAY ->
                described(
                        stringSchema("time"),
                        "ISO-8601 time of day without offset, e.g. 14:03:11.123; 24:00:00 is end of day");
            case ProtoJsonMapping.DATE_TIME ->
                described(
                        stringSchema(null),
                        "ISO-8601 date-time; the offset and [zone] are optional, e.g. 2026-09-22T14:03:11-04:00[America/New_York]");
            case ProtoJsonMapping.DECIMAL ->
                described(stringSchema("decimal"), "Decimal number as a string, e.g. 12.34");
            case ProtoJsonMapping.MONEY -> {
                Schema s = new Schema();
                s.setType("object");
                s.addProperty("currency", described(stringSchema(null), "ISO 4217 currency code, e.g. USD"));
                s.addProperty("amount", described(stringSchema("decimal"), "Decimal amount as a string, e.g. 12.34"));
                yield s;
            }
            default -> null;
        };
    }

    /** A codec's value, with every hint its {@link ScalarSchema} carries. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> codecSchema(ScalarSchema hint) {
        Schema s = switch (hint.shape()) {
            case STRING -> stringSchema(null);
            case INTEGER -> intSchema(null);
            case NUMBER -> numberSchema(null);
            case BOOLEAN -> boolSchema();
            case ANY -> new Schema<>();
        };
        if (hint.format() != null) s.setFormat(hint.format());
        if (hint.pattern() != null) s.setPattern(hint.pattern());
        if (hint.description() != null) s.setDescription(hint.description());
        if (hint.example() != null) s.setExample(hint.example());
        return s;
    }

    private static Schema<?> durationSchema() {
        if (ProtoJsonMapping.current().durationOutput() == ProtoJsonMapping.DurationFormat.ISO_8601) {
            return described(stringSchema("duration"), "ISO-8601 duration, e.g. PT1H30M");
        }
        String accepted =
                ProtoJsonMapping.current().acceptIso8601Duration() ? "; ISO-8601 (PT1H30M) is accepted on input" : "";
        return described(stringSchema(null), "Seconds with an s suffix, e.g. 5400s or 1.5s" + accepted);
    }

    /** 64-bit integers: JSON strings by default, numbers under shiguchi.json.int64-as: NUMBER. */
    private static Schema<?> int64Schema(String format) {
        return ProtoJsonMapping.current().int64AsNumber() ? intSchema(format) : stringSchema(format);
    }

    private static Schema<?> described(Schema<?> schema, String description) {
        schema.setDescription(description);
        return schema;
    }

    private static boolean isWellKnown(Descriptor descriptor) {
        String pkg = descriptor.getFile().getPackage();
        return pkg.startsWith("google.protobuf")
                || pkg.startsWith("google.api")
                || ProtoJsonMapping.isMapped(descriptor);
    }
}
