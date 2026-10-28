package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProtoWktTest {

    // Test 1: toJsonValue for Timestamp → RFC3339 string
    @Test
    @DisplayName(
            "Given Timestamp WKT with seconds=1700000000 and nanos=123456789, when toJsonValue, should return RFC3339 string")
    void given_timestampWkt_when_toJsonValue_should_returnRfc3339String() {
        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage ts = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByNumber(1), 1700000000L)
                .setField(tsDesc.findFieldByNumber(2), 123456789)
                .build();

        Object result = ProtoWkt.toJsonValue(ts);

        assertThat(result)
                .isInstanceOf(String.class)
                .asString()
                .contains("2023-11-14")
                .contains("22:13:20");
    }

    // Test 2a: toJsonValue for Duration → "Ns" string
    @Test
    @DisplayName(
            "Given Duration WKT with seconds=123 and nanos=456789000, when toJsonValue, should return Ns string format")
    void given_durationWkt_when_toJsonValue_should_returnNsString() {
        Descriptor durDesc = Duration.getDescriptor();
        DynamicMessage dur = DynamicMessage.newBuilder(durDesc)
                .setField(durDesc.findFieldByNumber(1), 123L)
                .setField(durDesc.findFieldByNumber(2), 456789000)
                .build();

        Object result = ProtoWkt.toJsonValue(dur);

        // Matches Durations.toString / JsonFormat: fractional seconds, "s" suffix
        assertThat(result).isEqualTo("123.456789s");
    }

    // Test 2b: toJsonValue for FieldMask → camelCase comma-joined string
    @Test
    @DisplayName(
            "Given FieldMask WKT with paths ['user.name', 'user.email'], when toJsonValue, should return camelCase comma-joined string")
    void given_fieldMaskWkt_when_toJsonValue_should_returnCamelCaseCommaJoinedString() {
        Descriptor maskDesc = FieldMask.getDescriptor();
        DynamicMessage mask = DynamicMessage.newBuilder(maskDesc)
                .addRepeatedField(maskDesc.findFieldByNumber(1), "user.name")
                .addRepeatedField(maskDesc.findFieldByNumber(1), "user.email")
                .build();

        Object result = ProtoWkt.toJsonValue(mask);

        assertThat(result)
                .isInstanceOf(String.class)
                .asString()
                .contains("user.name")
                .contains("user.email");
    }

    // Test 3a: toJsonValue for Int64Value → String
    @Test
    @DisplayName("Given Int64Value WKT with value=9999999999, when toJsonValue, should return String '9999999999'")
    void given_int64ValueWkt_when_toJsonValue_should_returnString() {
        Descriptor int64Desc = Int64Value.getDescriptor();
        DynamicMessage val = DynamicMessage.newBuilder(int64Desc)
                .setField(int64Desc.findFieldByNumber(1), 9999999999L)
                .build();

        Object result = ProtoWkt.toJsonValue(val);

        assertThat(result).isEqualTo("9999999999");
    }

    // Test 3b: toJsonValue for UInt64Value with negative long bits → unsigned string
    @Test
    @DisplayName(
            "Given UInt64Value WKT with negative long bits (Long.MAX_VALUE), when toJsonValue, should return unsigned string")
    void given_uint64ValueWktWithNegativeBits_when_toJsonValue_should_returnUnsignedString() {
        Descriptor uint64Desc = UInt64Value.getDescriptor();
        long negBits = Long.MAX_VALUE;
        DynamicMessage val = DynamicMessage.newBuilder(uint64Desc)
                .setField(uint64Desc.findFieldByNumber(1), negBits)
                .build();

        Object result = ProtoWkt.toJsonValue(val);

        assertThat(result).isEqualTo(Long.toUnsignedString(negBits));
    }

    // Test 3c: toJsonValue for BytesValue → base64
    @Test
    @DisplayName("Given BytesValue WKT with bytes [1, 2, 3, 255], when toJsonValue, should return base64 string")
    void given_bytesValueWkt_when_toJsonValue_should_returnBase64String() {
        Descriptor bytesDesc = BytesValue.getDescriptor();
        byte[] data = new byte[] {0x01, 0x02, 0x03, (byte) 0xFF};
        DynamicMessage val = DynamicMessage.newBuilder(bytesDesc)
                .setField(bytesDesc.findFieldByNumber(1), ByteString.copyFrom(data))
                .build();

        Object result = ProtoWkt.toJsonValue(val);

        assertThat(result).isEqualTo(Base64.getEncoder().encodeToString(data));
    }

    // Test 4: toJavaValue for Timestamp → OffsetDateTime (UTC)
    @Test
    @DisplayName(
            "Given Timestamp WKT with seconds=1700000000 nanos=0, when toJavaValue, should return OffsetDateTime UTC")
    void given_timestampWkt_when_toJavaValue_should_returnOffsetDateTimeUtc() {
        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage ts = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByNumber(1), 1700000000L)
                .setField(tsDesc.findFieldByNumber(2), 0)
                .build();

        Object result = ProtoWkt.toJavaValue(ts);

        assertThat(result)
                .isInstanceOf(OffsetDateTime.class)
                .isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
    }

    // Test 5: fromJava round-trip for Struct with nested map
    @Test
    @DisplayName(
            "Given nested Map with number/string/bool/null/list values, when fromJava roundtrip, should preserve structure")
    void given_nestedMapWithMixedValues_when_fromJavaRoundtrip_should_preserveStructure() {
        Descriptor structDesc = Struct.getDescriptor();

        // Build the map
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "Alice");
        input.put("age", 30.0);
        input.put("active", true);
        input.put("metadata", null);
        input.put("tags", List.of("tag1", "tag2"));

        DynamicMessage struct = ProtoWkt.fromJava(input, structDesc);

        Map<String, Object> result = (Map<String, Object>) ProtoWkt.toJsonValue(struct);

        assertThat(result)
                .containsEntry("name", "Alice")
                .containsEntry("age", 30.0)
                .containsEntry("active", true)
                .containsEntry("metadata", null)
                .containsKey("tags");
    }

    // Test 6: fromJava null for google.protobuf.Value descriptor → returns message with null_value set
    @Test
    @DisplayName(
            "Given null value and google.protobuf.Value descriptor, when fromJava, should return message with null_value set (oneof member 1)")
    void given_nullValueForValueDescriptor_when_fromJava_should_setNullValueOneof() {
        Descriptor valueDesc = Value.getDescriptor();

        DynamicMessage result = ProtoWkt.fromJava(null, valueDesc);

        assertThat(result).isNotNull();
        // Check that the null_value oneof member (field 1) is set
        assertThat(result.getOneofFieldDescriptor(valueDesc.getOneofs().get(0)))
                .isEqualTo(valueDesc.findFieldByNumber(1));
    }

    // Test 6b: fromJava null for Timestamp descriptor → returns null
    @Test
    @DisplayName("Given null value and Timestamp descriptor, when fromJava, should return null")
    void given_nullValueForTimestampDescriptor_when_fromJava_should_returnNull() {
        Descriptor tsDesc = Timestamp.getDescriptor();

        DynamicMessage result = ProtoWkt.fromJava(null, tsDesc);

        assertThat(result).isNull();
    }

    // Test 7a: fromJava for Timestamp accepts RFC3339 String
    @Test
    @DisplayName(
            "Given RFC3339 String '2023-11-14T22:13:20Z' and Timestamp descriptor, when fromJava, should parse to correct seconds")
    void given_rfc3339StringAndTimestampDescriptor_when_fromJava_should_parseCorrectly() {
        Descriptor tsDesc = Timestamp.getDescriptor();

        DynamicMessage result = ProtoWkt.fromJava("2023-11-14T22:13:20Z", tsDesc);

        assertThat(result).isNotNull();
        long seconds = ((Number) result.getField(tsDesc.findFieldByNumber(1))).longValue();
        assertThat(seconds).isEqualTo(1700000000L);
    }

    // Test 7b: fromJava for Timestamp accepts OffsetDateTime
    @Test
    @DisplayName("Given OffsetDateTime and Timestamp descriptor, when fromJava, should build correct DynamicMessage")
    void given_offsetDateTimeAndTimestampDescriptor_when_fromJava_should_buildCorrectly() {
        Descriptor tsDesc = Timestamp.getDescriptor();
        OffsetDateTime odt = OffsetDateTime.of(2023, 11, 14, 22, 13, 20, 123456789, ZoneOffset.UTC);

        DynamicMessage result = ProtoWkt.fromJava(odt, tsDesc);

        assertThat(result).isNotNull();
        long seconds = ((Number) result.getField(tsDesc.findFieldByNumber(1))).longValue();
        int nanos = ((Number) result.getField(tsDesc.findFieldByNumber(2))).intValue();
        assertThat(seconds).isEqualTo(odt.toEpochSecond());
        assertThat(nanos).isEqualTo(123456789);
    }

    // Test 8: enumValue resolves by name String, by Number, returns null for unknown
    @Test
    @DisplayName(
            "Given enum FieldDescriptor, when enumValue with name, number, or unknown name, should resolve accordingly")
    void given_enumField_when_enumValue_should_resolveByNameAndNumber() {
        // Value.null_value is a real enum field (google.protobuf.NullValue)
        FieldDescriptor nullValueFd = Value.getDescriptor().findFieldByNumber(1);

        assertThat(ProtoWkt.enumValue(nullValueFd, "NULL_VALUE"))
                .isEqualTo(nullValueFd.getEnumType().findValueByNumber(0));
        assertThat(ProtoWkt.enumValue(nullValueFd, 0))
                .isEqualTo(nullValueFd.getEnumType().findValueByNumber(0));
        assertThat(ProtoWkt.enumValue(nullValueFd, "NOT_A_VALUE")).isNull();
    }

    // Test 9: isWkt true for WKT list, false for non-WKT
    @Test
    @DisplayName("Given Timestamp descriptor, when isWkt, should return true")
    void given_timestampDescriptor_when_isWkt_should_returnTrue() {
        Descriptor tsDesc = Timestamp.getDescriptor();

        boolean result = ProtoWkt.isWkt(tsDesc);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given Any descriptor (not a WKT), when isWkt, should return false")
    void given_anyDescriptor_when_isWkt_should_returnFalse() {
        Descriptor anyDesc = com.google.protobuf.Any.getDescriptor();

        boolean result = ProtoWkt.isWkt(anyDesc);

        assertThat(result).isFalse();
    }

    // Test 10: writeJsonTree writes nested Map/List correctly
    @Test
    @DisplayName("Given nested Map with List and primitive values, when writeJsonTree, should emit valid JSON string")
    void given_nestedMapWithList_when_writeJsonTree_should_emitValidJson() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", "alice");
        data.put("count", 42);
        data.put("active", true);
        data.put("tags", List.of("tag1", "tag2"));
        data.put("metadata", Map.of("level", 5.5, "flag", false));

        StringWriter sw = new StringWriter();
        JsonGenerator gen = new JsonFactory().createGenerator(sw);
        ProtoWkt.writeJsonTree(gen, data);
        gen.close();

        String json = sw.toString();
        assertThat(json)
                .contains("\"user\":\"alice\"")
                .contains("\"count\":42")
                .contains("\"active\":true")
                .contains("\"tags\":[\"tag1\",\"tag2\"]")
                .contains("\"metadata\"")
                .contains("\"level\":5.5")
                .contains("\"flag\":false");
    }
}
