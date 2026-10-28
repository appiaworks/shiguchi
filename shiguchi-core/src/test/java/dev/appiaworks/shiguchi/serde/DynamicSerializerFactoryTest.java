package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style integration tests for DynamicSerializerFactory.
 *
 * Tests use compileDescriptor() — the public entry point — with custom inline descriptors
 * that contain Timestamp fields, mirroring the real-world messages where the codegen bugs manifested.
 */
class DynamicSerializerFactoryTest {

    @BeforeAll
    static void requiresCodegen() {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
    }

    private DynamicSerializerFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DynamicSerializerFactory();
    }

    // ── Timestamp singular field: the core serialize() bug ──

    @Test
    @DisplayName(
            "Given message with singular Timestamp field, when serialize, should emit ISO-8601 string not JSON object")
    void given_messageWithSingularTimestampField_when_serialize_should_emitIso8601StringNotObject() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Event", "occurred_at", false);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage tsMsg = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700000000L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("occurred_at"), tsMsg)
                .build();

        String json = toJson(serializer, msg);

        // Should be a string value like "2023-11-14T...", NOT a nested {"seconds":...,"nanos":...}
        assertThat(json).contains("\"occurredAt\"");
        assertThat(json).doesNotContain("{\"seconds\"");
        // The ISO string will contain the date
        assertThat(json).contains("2023-11-14");
    }

    @Test
    @DisplayName(
            "Given message with repeated Timestamp field, when serialize, should emit array of ISO-8601 strings not objects")
    void given_messageWithRepeatedTimestampField_when_serialize_should_emitArrayOfIsoStrings() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Log", "events", true);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage ts1 = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700000000L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();
        DynamicMessage ts2 = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700003600L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();

        FieldDescriptor fd = descriptor.findFieldByName("events");
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .addRepeatedField(fd, ts1)
                .addRepeatedField(fd, ts2)
                .build();

        String json = toJson(serializer, msg);

        // Should be ["2023-11-14T...", "2023-11-14T..."], not [{"seconds":...},{"seconds":...}]
        assertThat(json).contains("\"events\":[");
        assertThat(json).doesNotContain("{\"seconds\"");
        assertThat(json).contains("2023-11-14");
    }

    // ── Timestamp toMap() bug: should return OffsetDateTime not raw Map ──

    @Test
    @DisplayName(
            "Given message with singular Timestamp field, when toMap, should return OffsetDateTime value not raw Map")
    void given_messageWithTimestampField_when_toMap_should_returnOffsetDateTimeNotMap() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Event", "occurred_at", false);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage tsMsg = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700000000L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("occurred_at"), tsMsg)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = serializer.toMap(msg);

        Object occurredAt = result.get("occurredAt");
        assertThat(occurredAt)
                .as("Timestamp field should be OffsetDateTime in GraphQL path, not raw Map")
                .isInstanceOf(OffsetDateTime.class);
        OffsetDateTime odt = (OffsetDateTime) occurredAt;
        assertThat(odt.getYear()).isEqualTo(2023);
        assertThat(odt.getMonthValue()).isEqualTo(11);
        assertThat(odt.getDayOfMonth()).isEqualTo(14);
    }

    @Test
    @DisplayName("Given message with repeated Timestamp field, when toMap, should return List of OffsetDateTime")
    void given_messageWithRepeatedTimestampField_when_toMap_should_returnListOfOffsetDateTime() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Log", "events", true);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage ts1 = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByName("seconds"), 1700000000L)
                .setField(tsDesc.findFieldByName("nanos"), 0)
                .build();
        FieldDescriptor fd = descriptor.findFieldByName("events");
        DynamicMessage msg =
                DynamicMessage.newBuilder(descriptor).addRepeatedField(fd, ts1).build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = serializer.toMap(msg);

        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) result.get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(OffsetDateTime.class);
    }

    // ── Unset message field omitted ──

    @Test
    @DisplayName("Given message with unset Timestamp field, when toMap, should omit the field from result")
    void given_messageWithUnsetTimestampField_when_toMap_should_omitField() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Event", "occurred_at", false);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage msg = DynamicMessage.newBuilder(descriptor).build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = serializer.toMap(msg);

        assertThat(result).doesNotContainKey("occurredAt");
    }

    // ── fromMap: String coercion for numeric fields (the GraphQL INT64-as-String bug) ──

    @Test
    @DisplayName("Given int64 field value is String '12345', when fromMap, should coerce to long 12345")
    void given_int64FieldValueIsString_when_fromMap_should_coerceToLong() throws Exception {
        Descriptor descriptor = buildScalarDescriptor("Request", "id", FieldDescriptorProto.Type.TYPE_INT64);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage result = serializer.fromMap(Map.of("id", "12345"), DynamicMessage.newBuilder(descriptor));

        assertThat(result.getField(descriptor.findFieldByName("id"))).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Given int64 field value is Long 12345, when fromMap, should preserve value")
    void given_int64FieldValueIsLong_when_fromMap_should_preserveValue() throws Exception {
        Descriptor descriptor = buildScalarDescriptor("Request", "id", FieldDescriptorProto.Type.TYPE_INT64);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage result = serializer.fromMap(Map.of("id", 12345L), DynamicMessage.newBuilder(descriptor));

        assertThat(result.getField(descriptor.findFieldByName("id"))).isEqualTo(12345L);
    }

    @Test
    @DisplayName("Given int32 field value is String '99', when fromMap, should coerce to int 99")
    void given_int32FieldValueIsString_when_fromMap_should_coerceToInt() throws Exception {
        Descriptor descriptor = buildScalarDescriptor("Msg", "count", FieldDescriptorProto.Type.TYPE_INT32);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage result = serializer.fromMap(Map.of("count", "99"), DynamicMessage.newBuilder(descriptor));

        assertThat(result.getField(descriptor.findFieldByName("count"))).isEqualTo(99);
    }

    @Test
    @DisplayName("Given repeated int64 field values are Strings, when fromMap, should coerce all to Long")
    void given_repeatedInt64FieldValuesAreStrings_when_fromMap_should_coerceAll() throws Exception {
        Descriptor descriptor = buildRepeatedScalarDescriptor("Batch", "ids", FieldDescriptorProto.Type.TYPE_INT64);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage result =
                serializer.fromMap(Map.of("ids", List.of("1", "2", "3")), DynamicMessage.newBuilder(descriptor));

        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) result.getField(descriptor.findFieldByName("ids"));
        assertThat(ids).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("Given repeated int32 field values are Strings, when fromMap, should coerce all to Int")
    void given_repeatedInt32FieldValuesAreStrings_when_fromMap_should_coerceAll() throws Exception {
        Descriptor descriptor = buildRepeatedScalarDescriptor("Batch", "vals", FieldDescriptorProto.Type.TYPE_INT32);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage result =
                serializer.fromMap(Map.of("vals", List.of("10", "20")), DynamicMessage.newBuilder(descriptor));

        @SuppressWarnings("unchecked")
        List<Integer> vals = (List<Integer>) result.getField(descriptor.findFieldByName("vals"));
        assertThat(vals).containsExactly(10, 20);
    }

    // ── fromMap: Timestamp ISO string input ──

    @Test
    @DisplayName("Given Timestamp field value is ISO-8601 String, when fromMap, should parse into Timestamp message")
    void given_timestampFieldValueIsIsoString_when_fromMap_should_parseTimestamp() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Event", "occurred_at", false);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage result =
                serializer.fromMap(Map.of("occurredAt", "2023-11-14T22:13:20Z"), DynamicMessage.newBuilder(descriptor));

        Descriptor tsDesc = com.google.protobuf.Timestamp.getDescriptor();
        DynamicMessage ts = (DynamicMessage) result.getField(descriptor.findFieldByName("occurred_at"));
        long seconds = ((Number) ts.getField(tsDesc.findFieldByName("seconds"))).longValue();
        assertThat(seconds).isEqualTo(1700000000L);
    }

    @Test
    @DisplayName("Given Timestamp field is absent from map, when fromMap, should leave field unset")
    void given_timestampFieldAbsent_when_fromMap_should_leaveFieldUnset() throws Exception {
        Descriptor descriptor = buildDescriptorWithTimestamp("Event", "occurred_at", false);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage result = serializer.fromMap(Map.of(), DynamicMessage.newBuilder(descriptor));

        assertThat(result.hasField(descriptor.findFieldByName("occurred_at"))).isFalse();
    }

    // ── Scalar field round-trips ──

    @Test
    @DisplayName("Given message with string field, when toMap and fromMap, should round-trip value")
    void given_messageWithStringField_when_toMapAndFromMap_should_roundTripValue() throws Exception {
        Descriptor descriptor = buildScalarDescriptor("Item", "name", FieldDescriptorProto.Type.TYPE_STRING);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("name"), "hello world")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> map = serializer.toMap(msg);
        assertThat(map.get("name")).isEqualTo("hello world");

        DynamicMessage restored = serializer.fromMap(map, DynamicMessage.newBuilder(descriptor));
        assertThat(restored.getField(descriptor.findFieldByName("name"))).isEqualTo("hello world");
    }

    @Test
    @DisplayName("Given message with int32 field, when toMap and fromMap, should round-trip value")
    void given_messageWithInt32Field_when_toMapAndFromMap_should_roundTripValue() throws Exception {
        Descriptor descriptor = buildScalarDescriptor("Counter", "count", FieldDescriptorProto.Type.TYPE_INT32);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("count"), 42)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> map = serializer.toMap(msg);
        assertThat(map.get("count")).isEqualTo(42);

        DynamicMessage restored = serializer.fromMap(map, DynamicMessage.newBuilder(descriptor));
        assertThat(restored.getField(descriptor.findFieldByName("count"))).isEqualTo(42);
    }

    @Test
    @DisplayName("Given message with bytes field, when serialize, should emit base64-encoded string")
    void given_messageWithBytesField_when_serialize_should_emitBase64() throws Exception {
        Descriptor descriptor = buildScalarDescriptor("Blob", "data", FieldDescriptorProto.Type.TYPE_BYTES);
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        byte[] raw = new byte[] {0x01, 0x02, 0x03};
        DynamicMessage msg = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("data"), ByteString.copyFrom(raw))
                .build();

        String json = toJson(serializer, msg);

        String expected = Base64.getEncoder().encodeToString(raw);
        assertThat(json).contains("\"data\":\"" + expected + "\"");
    }

    // ── MESSAGE field deserialization (oneof / nested) ──

    @Test
    @DisplayName(
            "Given message with nested MESSAGE field, when deserialize JSON object, should populate nested message")
    void given_messageWithNestedMessageField_when_deserializeJsonObject_should_populateNestedMessage()
            throws Exception {
        FileDescriptor fd = buildFileWithNestedMessage();
        Descriptor outerDescriptor = fd.findMessageTypeByName("Outer");
        AbstractDynamicSerializer serializer = factory.compileDescriptor(outerDescriptor);

        String json = "{\"inner\":{\"name\":\"hello\"}}";
        DynamicMessage result = fromJson(serializer, outerDescriptor, json);

        FieldDescriptor innerFd = outerDescriptor.findFieldByName("inner");
        assertThat(result.hasField(innerFd)).isTrue();
        DynamicMessage innerMsg = (DynamicMessage) result.getField(innerFd);
        Descriptor innerDescriptor = innerFd.getMessageType();
        assertThat(innerMsg.getField(innerDescriptor.findFieldByName("name"))).isEqualTo("hello");
    }

    @Test
    @DisplayName("Given message with oneof MESSAGE field, when deserialize JSON with that field set, should set field")
    void given_messageWithOneofMessageField_when_deserializeJsonWithFieldSet_should_setField() throws Exception {
        FileDescriptor fd = buildFileWithOneofMessageField();
        Descriptor descriptor = fd.findMessageTypeByName("Container");
        AbstractDynamicSerializer serializer = factory.compileDescriptor(descriptor);

        String json = "{\"inner\":{\"name\":\"test-value\"}}";
        DynamicMessage result = fromJson(serializer, descriptor, json);

        FieldDescriptor innerFd = descriptor.findFieldByName("inner");
        assertThat(result.hasField(innerFd)).isTrue();
        DynamicMessage innerMsg = (DynamicMessage) result.getField(innerFd);
        Descriptor innerDescriptor = innerFd.getMessageType();
        assertThat(innerMsg.getField(innerDescriptor.findFieldByName("name"))).isEqualTo("test-value");
    }

    @Test
    @DisplayName(
            "Given message with nested MESSAGE field, when multiple fields in JSON, should parse all fields correctly")
    void given_messageWithNestedMessageField_when_multipleFieldsInJson_should_parseAllFieldsCorrectly()
            throws Exception {
        FileDescriptor fd = buildFileWithNestedMessage();
        Descriptor outerDescriptor = fd.findMessageTypeByName("Outer");
        AbstractDynamicSerializer serializer = factory.compileDescriptor(outerDescriptor);

        String json = "{\"title\":\"doc\",\"inner\":{\"name\":\"value\"},\"count\":7}";
        DynamicMessage result = fromJson(serializer, outerDescriptor, json);

        assertThat(result.getField(outerDescriptor.findFieldByName("title"))).isEqualTo("doc");
        assertThat(result.getField(outerDescriptor.findFieldByName("count"))).isEqualTo(7);
        assertThat(result.hasField(outerDescriptor.findFieldByName("inner"))).isTrue();
    }

    // ── Helpers ──

    private static Descriptor buildDescriptorWithTimestamp(String msgName, String fieldName, boolean repeated)
            throws Exception {
        FieldDescriptorProto.Label label =
                repeated ? FieldDescriptorProto.Label.LABEL_REPEATED : FieldDescriptorProto.Label.LABEL_OPTIONAL;

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
                                .setLabel(label)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(
                        proto, new FileDescriptor[] {Timestamp.getDescriptor().getFile()})
                .findMessageTypeByName(msgName);
    }

    private static Descriptor buildScalarDescriptor(String msgName, String fieldName, FieldDescriptorProto.Type type)
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

    private static Descriptor buildRepeatedScalarDescriptor(
            String msgName, String fieldName, FieldDescriptorProto.Type type) throws Exception {
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

    private static String toJson(AbstractDynamicSerializer serializer, DynamicMessage msg) throws Exception {
        StringWriter sw = new StringWriter();
        JsonGenerator gen = new JsonFactory().createGenerator(sw);
        serializer.serialize(msg, gen);
        gen.flush();
        gen.close();
        return sw.toString();
    }

    private static DynamicMessage fromJson(AbstractDynamicSerializer serializer, Descriptor descriptor, String json)
            throws Exception {
        ObjectMapper om = new ObjectMapper();
        try (JsonParser p = om.createParser(json)) {
            p.nextToken(); // START_OBJECT
            return serializer.deserialize(p, DynamicMessage.newBuilder(descriptor));
        }
    }

    private static FileDescriptor buildFileWithNestedMessage() throws Exception {
        DescriptorProto innerProto = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto outerProto = DescriptorProto.newBuilder()
                .setName("Outer")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("title")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("inner")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".Inner")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("count")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setSyntax("proto3")
                        .addMessageType(innerProto)
                        .addMessageType(outerProto)
                        .build(),
                new FileDescriptor[0]);
    }

    private static FileDescriptor buildFileWithOneofMessageField() throws Exception {
        DescriptorProto innerProto = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto containerProto = DescriptorProto.newBuilder()
                .setName("Container")
                .addOneofDecl(com.google.protobuf.DescriptorProtos.OneofDescriptorProto.newBuilder()
                        .setName("payload")
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("inner")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".Inner")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setOneofIndex(0)
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setSyntax("proto3")
                        .addMessageType(innerProto)
                        .addMessageType(containerProto)
                        .build(),
                new FileDescriptor[0]);
    }
}
