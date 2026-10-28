package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.type.Date;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ScalarSchema;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.DurationFormat;
import io.swagger.v3.oas.models.media.Schema;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The OpenAPI schema of time and decimal fields describes the JSON the gateway actually sends. */
@DisplayName("ProtoToOpenApiSchemaConverter — time and decimal fields")
class ProtoToOpenApiTimeTypesTest {

    private static final Descriptor EVENT = buildEvent();

    private final ProtoToOpenApiSchemaConverter converter = new ProtoToOpenApiSchemaConverter();
    private final Map<String, Schema<?>> components = new HashMap<>();

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    private Schema<?> schemaOf(String field) {
        return converter.fieldSchema(EVENT.findFieldByName(field), components, new HashSet<>(), 0);
    }

    @Test
    @DisplayName("GIVEN a Timestamp field WHEN its schema is built SHOULD be a string with format date-time")
    void given_timestamp_when_built_should_beDateTime() {
        assertThat(schemaOf("created").getType()).isEqualTo("string");
        assertThat(schemaOf("created").getFormat()).isEqualTo("date-time");
    }

    @Test
    @DisplayName(
            "GIVEN the default mapping WHEN Date, TimeOfDay and Decimal fields are built SHOULD carry formats date, time and decimal")
    void given_defaults_when_googleTypesBuilt_should_carryFormats() {
        assertThat(schemaOf("day").getFormat()).isEqualTo("date");
        assertThat(schemaOf("opens").getFormat()).isEqualTo("time");
        assertThat(schemaOf("price").getFormat()).isEqualTo("decimal");
        assertThat(schemaOf("day").getType()).isEqualTo("string");
    }

    @Test
    @DisplayName(
            "GIVEN the default mapping WHEN a DateTime field is built SHOULD be a string without a format, as its offset is optional")
    void given_defaults_when_dateTimeBuilt_should_beStringWithoutFormat() {
        Schema<?> starts = schemaOf("starts");

        assertThat(starts.getType()).isEqualTo("string");
        assertThat(starts.getFormat()).isNull();
        assertThat(starts.getDescription()).contains("[zone]");
    }

    @Test
    @DisplayName(
            "GIVEN the default Duration output WHEN a Duration field is built SHOULD have no format; with ISO_8601 output SHOULD be format duration")
    void given_durationOutput_when_built_should_matchFormat() {
        assertThat(schemaOf("length").getFormat()).isNull();

        ProtoJsonMapping.install(
                new ProtoJsonMapping(true, true, true, true, false, DurationFormat.ISO_8601, true, false));

        assertThat(schemaOf("length").getFormat()).isEqualTo("duration");
    }

    @Test
    @DisplayName("GIVEN money mapped WHEN a Money field is built SHOULD be an object with currency and amount")
    void given_moneyMapped_when_built_should_beCurrencyAndAmount() {
        ProtoJsonMapping.install(new ProtoJsonMapping(true, true, true, true, true, DurationFormat.PROTO, true, false));

        Schema<?> cost = schemaOf("cost");

        assertThat(cost.getType()).isEqualTo("object");
        assertThat(cost.getProperties()).containsOnlyKeys("currency", "amount");
    }

    @Test
    @DisplayName(
            "GIVEN an application codec for a message WHEN its field is built SHOULD be typed by the codec's JSON shape, not as the built-in date")
    void given_customCodec_when_built_should_followJsonShape() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.NUMBER)));
        assertThat(schemaOf("day").getType()).isEqualTo("number");
        assertThat(schemaOf("day").getFormat()).isNull();

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.BOOLEAN)));
        assertThat(schemaOf("day").getType()).isEqualTo("boolean");

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.STRING)));
        assertThat(schemaOf("day").getType()).isEqualTo("string");
        assertThat(schemaOf("day").getFormat()).isNull();

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.ANY)));
        assertThat(schemaOf("day").getType()).isNull();
        assertThat(schemaOf("day").get$ref()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a codec with a schema of format, pattern, description and example WHEN its field is built SHOULD carry every hint")
    void given_codecWithSchemaHints_when_built_should_carryEveryHint() {
        ProtoJsonMapping.install(
                ProtoJsonMapping.DEFAULTS,
                List.of(dateCodec(ProtoScalarCodec.JsonShape.STRING)
                        .withSchema(ScalarSchema.string()
                                .withFormat("year")
                                .withPattern("\\d{4}")
                                .withDescription("A calendar year")
                                .withExample("2026"))));

        Schema<?> day = schemaOf("day");

        assertThat(day.getType()).isEqualTo("string");
        assertThat(day.getFormat()).isEqualTo("year");
        assertThat(day.getPattern()).isEqualTo("\\d{4}");
        assertThat(day.getDescription()).isEqualTo("A calendar year");
        assertThat(day.getExample()).isEqualTo("2026");
    }

    @Test
    @DisplayName("GIVEN a codec of INTEGER shape WHEN its field is built SHOULD be an integer with the schema's format")
    void given_integerCodec_when_built_should_beInteger() {
        ProtoJsonMapping.install(
                ProtoJsonMapping.DEFAULTS,
                List.of(dateCodec(ProtoScalarCodec.JsonShape.INTEGER)
                        .withSchema(ScalarSchema.integer().withFormat("int32").withExample(2026))));

        Schema<?> day = schemaOf("day");

        assertThat(day.getType()).isEqualTo("integer");
        assertThat(day.getFormat()).isEqualTo("int32");
        assertThat(day.getExample()).isEqualTo(2026);
        assertThat(day.getPattern()).isNull();
    }

    private static ProtoScalarCodec dateCodec(ProtoScalarCodec.JsonShape shape) {
        return ProtoScalarCodec.of(
                Date.getDefaultInstance(),
                shape,
                Date::getYear,
                json -> Date.newBuilder().setYear(((Number) json).intValue()).build());
    }

    @Test
    @DisplayName(
            "GIVEN every google.type mapping off WHEN a Date field is built SHOULD reference the Date object component")
    void given_proto3_when_dateBuilt_should_referenceComponent() {
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        assertThat(schemaOf("day").get$ref()).isEqualTo("#/components/schemas/Date");
        assertThat(components).containsKey("Date");
    }

    private static Descriptor buildEvent() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("t/event.proto")
                .setPackage("t")
                .setSyntax("proto3")
                .addDependency("google/type/date.proto")
                .addDependency("google/type/timeofday.proto")
                .addDependency("google/type/datetime.proto")
                .addDependency("google/type/decimal.proto")
                .addDependency("google/type/money.proto")
                .addDependency("google/protobuf/timestamp.proto")
                .addDependency("google/protobuf/duration.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Event")
                        .addField(message("day", 1, ".google.type.Date"))
                        .addField(message("opens", 2, ".google.type.TimeOfDay"))
                        .addField(message("starts", 3, ".google.type.DateTime"))
                        .addField(message("price", 4, ".google.type.Decimal"))
                        .addField(message("cost", 5, ".google.type.Money"))
                        .addField(message("created", 6, ".google.protobuf.Timestamp"))
                        .addField(message("length", 7, ".google.protobuf.Duration")))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {
                        com.google.type.DateProto.getDescriptor(),
                        com.google.type.TimeOfDayProto.getDescriptor(),
                        com.google.type.DateTimeProto.getDescriptor(),
                        com.google.type.DecimalProto.getDescriptor(),
                        com.google.type.MoneyProto.getDescriptor(),
                        com.google.protobuf.TimestampProto.getDescriptor(),
                        com.google.protobuf.DurationProto.getDescriptor()
                    })
                    .findMessageTypeByName("Event");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder message(String name, int number, String type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
