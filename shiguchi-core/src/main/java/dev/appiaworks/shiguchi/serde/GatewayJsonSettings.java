package dev.appiaworks.shiguchi.serde;

import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.serde.JsonNumberFormat;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.config.JsonConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;

/**
 * Reads {@code shiguchi.json.*} into the serde's own settings. The serde lives in
 * {@code protobuf-java-extras} and knows nothing of the gateway's configuration model, so the
 * translation sits here.
 */
public final class GatewayJsonSettings {

    private GatewayJsonSettings() {}

    /** The mapping {@code shiguchi.json} configures. */
    public static ProtoJsonMapping mapping(JsonConfig config) {
        JsonConfig.ScalarTypes t = config.getScalarTypes();
        return new ProtoJsonMapping(
                t.isDate(),
                t.isTimeOfDay(),
                t.isDateTime(),
                t.isDecimal(),
                t.isMoney(),
                config.getDuration().getOutput() == JsonConfig.DurationFormat.ISO_8601
                        ? ProtoJsonMapping.DurationFormat.ISO_8601
                        : ProtoJsonMapping.DurationFormat.PROTO,
                config.getDuration().isAcceptIso8601(),
                config.getTimestamp().isAcceptEpochMillis(),
                config.getInt64As() == JsonConfig.Int64Format.NUMBER);
    }

    /**
     * A {@link ProtoJson} speaking the dialect {@code shiguchi.json} configures, with the
     * application's scalar codecs: what the gateway's REST surface would write for a message, with
     * the global print options (per-service overrides do not apply).
     */
    public static ProtoJson protoJson(JsonConfig config, Iterable<? extends ProtoScalarCodec> codecs) {
        JsonConfig.ScalarTypes t = config.getScalarTypes();
        JsonConfig.PrintOptions print = config.getPrint();
        ProtoJson.Builder builder = ProtoJson.builder()
                .dateAsText(t.isDate())
                .timeOfDayAsText(t.isTimeOfDay())
                .dateTimeAsText(t.isDateTime())
                .decimalAsText(t.isDecimal())
                .moneyAsAmount(t.isMoney())
                .durationFormat(
                        config.getDuration().getOutput() == JsonConfig.DurationFormat.ISO_8601
                                ? ProtoJson.DurationFormat.ISO_8601
                                : ProtoJson.DurationFormat.PROTO)
                .acceptIso8601Duration(config.getDuration().isAcceptIso8601())
                .acceptEpochMillisTimestamp(config.getTimestamp().isAcceptEpochMillis())
                .int64AsNumber(config.getInt64As() == JsonConfig.Int64Format.NUMBER);
        if (print != null) {
            builder.preserveProtoFieldNames(print.isPreserveProtoFieldNames())
                    .enumsAsInts(print.isEnumsAsInts())
                    .omitDefaultValues(print.isOmitDefaultValues())
                    .numberFormat(print.getNumberFormat() == null ? JsonNumberFormat.JAVA : print.getNumberFormat());
        }
        for (ProtoScalarCodec codec : codecs) builder.scalar(codec);
        return builder.build();
    }

    /** The global print options {@code shiguchi.json.print} configures. */
    public static JsonPrintOptions printOptions(JsonConfig.PrintOptions config) {
        if (config == null) return JsonPrintOptions.DEFAULT;
        return new JsonPrintOptions(
                config.isPreserveProtoFieldNames(),
                config.isEnumsAsInts(),
                config.isOmitDefaultValues(),
                config.getNumberFormat());
    }

    /** The installed global print options with whatever the service overrides. */
    public static JsonPrintOptions printOptionsFor(ServiceDefinition service) {
        JsonPrintOptions base = JsonPrintOptions.global();
        ServiceDefinition.JsonPrintOverride o = service == null ? null : service.getJsonPrint();
        if (o == null) return base;
        return new JsonPrintOptions(
                o.getPreserveProtoFieldNames() != null
                        ? o.getPreserveProtoFieldNames()
                        : base.preserveProtoFieldNames(),
                o.getEnumsAsInts() != null ? o.getEnumsAsInts() : base.enumsAsInts(),
                o.getOmitDefaultValues() != null ? o.getOmitDefaultValues() : base.omitDefaultValues(),
                o.getNumberFormat() != null ? o.getNumberFormat() : base.numberFormat());
    }
}
