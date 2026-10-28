package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.type.Date;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.DurationFormat;
import java.util.List;
import org.apache.avro.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** google.type fields on the gateway's Avro surface follow shiguchi.json.scalar-types. */
@DisplayName("ProtoToAvroSchema — google.type fields")
class ProtoToAvroGoogleTypeTest {

    private static final Descriptor EVENT = buildEvent();

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    /** The non-null branch of a message field's {@code ["null", T]} union. */
    private static Schema valueSchema(Schema record, String field) {
        Schema union = record.getField(field).schema();
        assertThat(union.getType()).isEqualTo(Schema.Type.UNION);
        return union.getTypes().stream()
                .filter(s -> s.getType() != Schema.Type.NULL)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName(
            "GIVEN the default mapping WHEN a message with Date, TimeOfDay, DateTime and Decimal fields is derived SHOULD make each an Avro string")
    void given_defaults_when_derived_should_mapGoogleTypesToStrings() {
        Schema event = new ProtoToAvroSchema().schemaFor(EVENT);

        assertThat(valueSchema(event, "day").getType()).isEqualTo(Schema.Type.STRING);
        assertThat(valueSchema(event, "opens").getType()).isEqualTo(Schema.Type.STRING);
        assertThat(valueSchema(event, "starts").getType()).isEqualTo(Schema.Type.STRING);
        assertThat(valueSchema(event, "price").getType()).isEqualTo(Schema.Type.STRING);
    }

    @Test
    @DisplayName(
            "GIVEN the default mapping (money off) WHEN a Money field is derived SHOULD keep the proto record with currencyCode, units and nanos")
    void given_defaults_when_moneyDerived_should_keepProtoRecord() {
        Schema money = valueSchema(new ProtoToAvroSchema().schemaFor(EVENT), "cost");

        assertThat(money.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(money.getField("currencyCode")).isNotNull();
        assertThat(money.getField("units")).isNotNull();
    }

    @Test
    @DisplayName("GIVEN money mapped WHEN a Money field is derived SHOULD be the flattened currency / amount record")
    void given_moneyMapped_when_derived_should_flattenToCurrencyAndAmount() {
        ProtoJsonMapping.install(new ProtoJsonMapping(true, true, true, true, true, DurationFormat.PROTO, true, false));

        Schema money = valueSchema(new ProtoToAvroSchema().schemaFor(EVENT), "cost");

        assertThat(money.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(money.getFields()).extracting(Schema.Field::name).containsExactly("currency", "amount");
        assertThat(money.getField("amount").schema().getType()).isEqualTo(Schema.Type.STRING);
    }

    @Test
    @DisplayName(
            "GIVEN every google.type mapping off WHEN a Date field is derived SHOULD be the year / month / day record")
    void given_proto3_when_dateDerived_should_beRecord() {
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        Schema day = valueSchema(new ProtoToAvroSchema().schemaFor(EVENT), "day");

        assertThat(day.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(day.getFields()).extracting(Schema.Field::name).containsExactly("year", "month", "day");
    }

    @Test
    @DisplayName(
            "GIVEN an application codec for a message WHEN its field is derived SHOULD follow the codec's JSON shape, an object or array being a string of JSON")
    void given_customCodec_when_derived_should_followJsonShape() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.NUMBER)));
        assertThat(valueSchema(new ProtoToAvroSchema().schemaFor(EVENT), "day").getType())
                .isEqualTo(Schema.Type.DOUBLE);

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.BOOLEAN)));
        assertThat(valueSchema(new ProtoToAvroSchema().schemaFor(EVENT), "day").getType())
                .isEqualTo(Schema.Type.BOOLEAN);

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.ANY)));
        assertThat(valueSchema(new ProtoToAvroSchema().schemaFor(EVENT), "day").getType())
                .isEqualTo(Schema.Type.STRING);
    }

    @Test
    @DisplayName("GIVEN a codec of INTEGER shape WHEN its field is derived SHOULD be an Avro long")
    void given_integerCodec_when_derived_should_beLong() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(dateCodec(ProtoScalarCodec.JsonShape.INTEGER)));

        assertThat(valueSchema(new ProtoToAvroSchema().schemaFor(EVENT), "day").getType())
                .isEqualTo(Schema.Type.LONG);
    }

    private static ProtoScalarCodec dateCodec(ProtoScalarCodec.JsonShape shape) {
        return ProtoScalarCodec.of(
                Date.getDefaultInstance(),
                shape,
                Date::getYear,
                json -> Date.newBuilder().setYear(((Number) json).intValue()).build());
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
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Event")
                        .addField(message("day", 1, ".google.type.Date"))
                        .addField(message("opens", 2, ".google.type.TimeOfDay"))
                        .addField(message("starts", 3, ".google.type.DateTime"))
                        .addField(message("price", 4, ".google.type.Decimal"))
                        .addField(message("cost", 5, ".google.type.Money")))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {
                        com.google.type.DateProto.getDescriptor(),
                        com.google.type.TimeOfDayProto.getDescriptor(),
                        com.google.type.DateTimeProto.getDescriptor(),
                        com.google.type.DecimalProto.getDescriptor(),
                        com.google.type.MoneyProto.getDescriptor()
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
