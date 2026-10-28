package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProtoMapConverterTest {

    // ── dynamicFromMap: String→Number coercion (the ClassCastException bugs) ──

    @Test
    @DisplayName("Given int32 field value is String '42', when dynamicFromMap, should set field to integer 42")
    void given_int32FieldValueIsString_when_dynamicFromMap_should_coerceToInteger() throws Exception {
        Descriptor descriptor = buildDescriptor("Msg", "count", FieldDescriptorProto.Type.TYPE_INT32);

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(Map.of("count", "42"), descriptor);

        assertThat(result.getField(descriptor.findFieldByName("count"))).isEqualTo(42);
    }

    @Test
    @DisplayName("Given int64 field value is String '9999999999', when dynamicFromMap, should set field to Long")
    void given_int64FieldValueIsString_when_dynamicFromMap_should_coerceToLong() throws Exception {
        Descriptor descriptor = buildDescriptor("Msg", "id", FieldDescriptorProto.Type.TYPE_INT64);

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(Map.of("id", "9999999999"), descriptor);

        assertThat(result.getField(descriptor.findFieldByName("id"))).isEqualTo(9999999999L);
    }

    @Test
    @DisplayName("Given double field value is String '3.14', when dynamicFromMap, should set field to Double")
    void given_doubleFieldValueIsString_when_dynamicFromMap_should_coerceToDouble() throws Exception {
        Descriptor descriptor = buildDescriptor("Msg", "ratio", FieldDescriptorProto.Type.TYPE_DOUBLE);

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(Map.of("ratio", "3.14"), descriptor);

        assertThat((Double) result.getField(descriptor.findFieldByName("ratio")))
                .isCloseTo(3.14, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── dynamicFromMap: Timestamp input from DateTime scalar (the dropped-arg bug) ──

    @Test
    @DisplayName(
            "Given Timestamp field value is OffsetDateTime, when dynamicFromMap, should set seconds/nanos instead of dropping the field")
    void given_timestampFieldValueIsOffsetDateTime_when_dynamicFromMap_should_setSecondsAndNanos() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Req", "from_date");
        OffsetDateTime odt = OffsetDateTime.of(2026, 5, 20, 0, 0, 0, 0, ZoneOffset.UTC);

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(Map.of("fromDate", odt), descriptor);

        FieldDescriptor fd = descriptor.findFieldByName("from_date");
        assertThat(result.hasField(fd)).isTrue();
        DynamicMessage ts = (DynamicMessage) result.getField(fd);
        assertThat(ts.getField(Timestamp.getDescriptor().findFieldByName("seconds")))
                .isEqualTo(odt.toEpochSecond());
    }

    @Test
    @DisplayName("Given Timestamp field value is java.time.Instant, when dynamicFromMap, should set seconds/nanos")
    void given_timestampFieldValueIsInstant_when_dynamicFromMap_should_setSecondsAndNanos() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Req", "from_date");
        java.time.Instant instant = java.time.Instant.ofEpochSecond(1747699200L);

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(Map.of("fromDate", instant), descriptor);

        FieldDescriptor fd = descriptor.findFieldByName("from_date");
        assertThat(result.hasField(fd)).isTrue();
        DynamicMessage ts = (DynamicMessage) result.getField(fd);
        assertThat(ts.getField(Timestamp.getDescriptor().findFieldByName("seconds")))
                .isEqualTo(1747699200L);
    }

    // ── dynamicToMap: Timestamp serialization (the Map-not-ISO bug) ──

    @Test
    @DisplayName(
            "Given message with nested Timestamp field, when dynamicToMap, should emit ISO-8601 String not raw Map")
    void given_messageWithTimestampField_when_dynamicToMap_should_emitIso8601StringNotMap() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Event", "occurred_at");
        Descriptor tsDesc = Timestamp.getDescriptor();

        DynamicMessage tsMsg = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700000000L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("occurred_at"), tsMsg)
                .build();

        Map<String, Object> result = ProtoMapConverter.dynamicToMap(msg);

        assertThat(result.get("occurredAt"))
                .isInstanceOf(String.class)
                .asString()
                .contains("2023-11-14");
    }

    @Test
    @DisplayName("Given repeated Timestamp list, when dynamicToMap, should emit List of ISO-8601 Strings")
    void given_repeatedTimestampField_when_dynamicToMap_should_emitListOfIsoStrings() throws Exception {
        Descriptor descriptor = buildDescriptorWithRepeatedTimestamp("Log", "timestamps");
        Descriptor tsDesc = Timestamp.getDescriptor();

        DynamicMessage ts1 = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700000000L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();
        DynamicMessage ts2 = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700003600L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();

        FieldDescriptor fd = descriptor.findFieldByName("timestamps");
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .addRepeatedField(fd, ts1)
                .addRepeatedField(fd, ts2)
                .build();

        Map<String, Object> result = ProtoMapConverter.dynamicToMap(msg);

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result.get("timestamps");
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).isInstanceOf(String.class);
        assertThat(list.get(1)).isInstanceOf(String.class);
    }

    // ── convertMessageToJava: Timestamp → OffsetDateTime (GraphQL path) ──

    @Test
    @DisplayName("Given Timestamp DynamicMessage, when convertMessageToJava, should return OffsetDateTime not String")
    void given_timestampDynamicMessage_when_convertMessageToJava_should_returnOffsetDateTime() {
        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage tsMsg = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700000000L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();

        Object result = ProtoMapConverter.convertMessageToJava(tsMsg);

        assertThat(result).isInstanceOf(OffsetDateTime.class);
        assertThat((OffsetDateTime) result)
                .isEqualTo(OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Given non-Timestamp DynamicMessage, when convertMessageToJava, should return Map")
    void given_nonTimestampDynamicMessage_when_convertMessageToJava_should_returnMap() throws Exception {
        Descriptor descriptor = buildDescriptor("Item", "name", FieldDescriptorProto.Type.TYPE_STRING);
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("name"), "hello")
                .build();

        Object result = ProtoMapConverter.convertMessageToJava(msg);

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("name")).isEqualTo("hello");
    }

    // ── coerceLong / coerceInt helpers ──

    @Test
    @DisplayName("Given Long value, when coerceLong, should return same long")
    void given_longValue_when_coerceLong_should_returnLong() {
        assertThat(ProtoMapConverter.coerceLong(9999999999L)).isEqualTo(9999999999L);
    }

    @Test
    @DisplayName("Given String '12345', when coerceLong, should parse to 12345L")
    void given_stringValue_when_coerceLong_should_parseLong() {
        assertThat(ProtoMapConverter.coerceLong("12345")).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Given Integer value 42, when coerceInt, should return 42")
    void given_intValue_when_coerceInt_should_returnInt() {
        assertThat(ProtoMapConverter.coerceInt(42)).isEqualTo(42);
    }

    @Test
    @DisplayName("Given String '99', when coerceInt, should parse to 99")
    void given_stringValue_when_coerceInt_should_parseInt() {
        assertThat(ProtoMapConverter.coerceInt("99")).isEqualTo(99);
    }

    @Test
    @DisplayName("Given unparseable String, when coerceLong, should return 0")
    void given_unparseableString_when_coerceLong_should_returnZero() {
        assertThat(ProtoMapConverter.coerceLong("not-a-number")).isEqualTo(0L);
    }

    // ── timestampFromIso8601 ──

    @Test
    @DisplayName(
            "Given ISO-8601 string '2023-11-14T22:13:20Z', when timestampFromIso8601, should produce correct seconds")
    void given_iso8601String_when_timestampFromIso8601_should_produceCorrectSeconds() {
        Descriptor tsDesc = com.google.protobuf.Timestamp.getDescriptor();

        DynamicMessage ts = ProtoMapConverter.timestampFromIso8601("2023-11-14T22:13:20Z", tsDesc);

        long seconds = ((Number) ts.getField(tsDesc.findFieldByName("seconds"))).longValue();
        assertThat(seconds).isEqualTo(1700000000L);
    }

    @Test
    @DisplayName("Given ISO-8601 string with nanos, when timestampFromIso8601, should preserve nanos")
    void given_iso8601StringWithNanos_when_timestampFromIso8601_should_preserveNanos() {
        Descriptor tsDesc = com.google.protobuf.Timestamp.getDescriptor();

        DynamicMessage ts = ProtoMapConverter.timestampFromIso8601("2023-11-14T22:13:20.123456789Z", tsDesc);

        int nanos = ((Number) ts.getField(tsDesc.findFieldByName("nanos"))).intValue();
        assertThat(nanos).isEqualTo(123456789);
    }

    // ── dynamicFromMap: Timestamp ISO String coercion (Tier 3 path) ──

    @Test
    @DisplayName(
            "Given Timestamp field value is ISO String in map, when dynamicFromMap, should parse timestamp into seconds")
    void given_timestampFieldIsIsoString_when_dynamicFromMap_should_parseTimestamp() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Event", "occurred_at");
        Descriptor tsDesc = com.google.protobuf.Timestamp.getDescriptor();

        DynamicMessage result =
                ProtoMapConverter.dynamicFromMap(Map.of("occurredAt", "2023-11-14T22:13:20Z"), descriptor);

        assertThat(result.hasField(descriptor.findFieldByName("occurred_at"))).isTrue();
        DynamicMessage ts = (DynamicMessage) result.getField(descriptor.findFieldByName("occurred_at"));
        long seconds = ((Number) ts.getField(tsDesc.findFieldByName("seconds"))).longValue();
        assertThat(seconds).isEqualTo(1700000000L);
    }

    // ── Enum and Bytes ──

    @Test
    @DisplayName("Given enum field, when dynamicToMap, should emit enum name String not numeric value")
    void given_enumField_when_dynamicToMap_should_emitEnumNameString() throws Exception {
        Descriptor descriptor = buildDescriptorWithEnum("Thing", "status", "Status", List.of("ACTIVE", "INACTIVE"));

        FieldDescriptor fd = descriptor.findFieldByName("status");
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(fd, fd.getEnumType().findValueByName("INACTIVE"))
                .build();

        Map<String, Object> result = ProtoMapConverter.dynamicToMap(msg);

        assertThat(result.get("status")).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName(
            "Given bytes field, when round-tripped through dynamicToMap and dynamicFromMap, should preserve byte content")
    void given_bytesField_when_roundTripped_should_preserveByteContent() throws Exception {
        Descriptor descriptor = buildDescriptor("Blob", "data", FieldDescriptorProto.Type.TYPE_BYTES);
        byte[] original = new byte[] {0x01, 0x02, 0x03, (byte) 0xFF};

        FieldDescriptor fd = descriptor.findFieldByName("data");
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(fd, ByteString.copyFrom(original))
                .build();

        Map<String, Object> map = ProtoMapConverter.dynamicToMap(msg);
        assertThat(map.get("data")).isEqualTo(Base64.getEncoder().encodeToString(original));

        DynamicMessage restored = ProtoMapConverter.dynamicFromMap(map, descriptor);
        assertThat(((ByteString) restored.getField(fd)).toByteArray()).isEqualTo(original);
    }

    @Test
    @DisplayName("Given repeated field map with a null element, when dynamicFromMap, should skip null without NPE")
    void given_repeatedFieldWithNullElement_when_dynamicFromMap_should_skipNullWithoutNpe() throws Exception {
        Descriptor descriptor = buildRepeatedDescriptor("Items", "tags", FieldDescriptorProto.Type.TYPE_STRING);

        List<String> listWithNull = new ArrayList<>();
        listWithNull.add("alpha");
        listWithNull.add(null);
        listWithNull.add("gamma");

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(Map.of("tags", listWithNull), descriptor);

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) result.getField(descriptor.findFieldByName("tags"));
        assertThat(tags).containsExactly("alpha", "gamma");
    }

    // ── Descriptor builders ──

    private static Descriptor buildDescriptor(String msgName, String fieldName, FieldDescriptorProto.Type type)
            throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(msgName)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(type)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(proto, new FileDescriptor[0]).findMessageTypeByName(msgName);
    }

    private static Descriptor buildRepeatedDescriptor(String msgName, String fieldName, FieldDescriptorProto.Type type)
            throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(msgName)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(type)
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(proto, new FileDescriptor[0]).findMessageTypeByName(msgName);
    }

    private static Descriptor buildDescriptorWithTimestamp(String msgName, String fieldName) throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setSyntax("proto3")
                .addDependency("google/protobuf/timestamp.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(msgName)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Timestamp")
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(
                        proto, new FileDescriptor[] {Timestamp.getDescriptor().getFile()})
                .findMessageTypeByName(msgName);
    }

    private static Descriptor buildDescriptorWithRepeatedTimestamp(String msgName, String fieldName) throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setSyntax("proto3")
                .addDependency("google/protobuf/timestamp.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(msgName)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Timestamp")
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(
                        proto, new FileDescriptor[] {Timestamp.getDescriptor().getFile()})
                .findMessageTypeByName(msgName);
    }

    private static Descriptor buildDescriptorWithEnum(
            String msgName, String fieldName, String enumName, List<String> values) throws Exception {
        EnumDescriptorProto.Builder enumBuilder =
                EnumDescriptorProto.newBuilder().setName(enumName);
        for (int i = 0; i < values.size(); i++) {
            enumBuilder.addValue(EnumValueDescriptorProto.newBuilder()
                    .setName(values.get(i))
                    .setNumber(i)
                    .build());
        }
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(msgName)
                        .addEnumType(enumBuilder.build())
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                                .setTypeName("." + msgName + "." + enumName)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(proto, new FileDescriptor[0]).findMessageTypeByName(msgName);
    }
}
