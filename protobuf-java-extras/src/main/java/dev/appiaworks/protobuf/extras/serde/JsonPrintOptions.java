package dev.appiaworks.protobuf.extras.serde;

/**
 * How a service's REST responses are printed ({@code shiguchi.json.print}, overridable per
 * service with {@code services[].json-print}).
 *
 * <p>{@link #DEFAULT} is the gateway's historical output: lowerCamelCase field names, enums by
 * name, and every field without presence printed even when it holds its default. A service on
 * the default options takes every fast serializer tier; one that changes any option is printed by
 * the wire-to-JSON transcoder ({@code WireJsonTranscoder}, which applies the options per call), and
 * where that cannot serve — pagination needing the decoded message, a rare wire encoding — by the
 * reflective writer ({@link ProtoMapConverter#writeMessageJson(com.fasterxml.jackson.core.JsonGenerator,
 * com.google.protobuf.DynamicMessage, JsonPrintOptions)}), or by {@code JsonFormat} when the
 * message reaches {@code google.protobuf.Any}.
 *
 * <p>Output only: requests accept both field spellings and enum names or numbers either way.
 *
 * @param preserveProtoFieldNames field names as declared in the proto ({@code display_name})
 *     instead of lowerCamelCase ({@code displayName})
 * @param enumsAsInts enum values as their numbers instead of their names
 * @param omitDefaultValues leave out fields without presence that hold their default value
 *     ({@code 0}, {@code ""}, {@code false}, an empty list or map), as {@code JsonFormat} does by
 *     default
 */
public record JsonPrintOptions(boolean preserveProtoFieldNames, boolean enumsAsInts, boolean omitDefaultValues) {

    /** The gateway's historical output. */
    public static final JsonPrintOptions DEFAULT = new JsonPrintOptions(false, false, false);

    private static volatile JsonPrintOptions global = DEFAULT;

    /** Whether these are the historical options, which every serializer tier produces. */
    public boolean isDefault() {
        return !preserveProtoFieldNames && !enumsAsInts && !omitDefaultValues;
    }

    /** Installs the global options; done once by the gateway at startup. */
    public static void install(JsonPrintOptions options) {
        global = options == null ? DEFAULT : options;
    }

    /** The installed global options. */
    public static JsonPrintOptions global() {
        return global;
    }

    /** The name a field is printed under. */
    public String fieldName(com.google.protobuf.Descriptors.FieldDescriptor field) {
        return preserveProtoFieldNames ? field.getName() : field.getJsonName();
    }
}
