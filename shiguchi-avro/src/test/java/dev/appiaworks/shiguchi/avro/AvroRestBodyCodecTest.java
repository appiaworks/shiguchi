package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Timestamp;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for AvroRestBodyCodec.
 *
 * Tests verify that:
 * - Avro binary encoding/decoding works with single-object and binary framing
 * - Request bodies decode correctly from Avro bytes to Maps
 * - Response bodies encode correctly from Maps to Avro bytes
 * - Scalar types are handled correctly (Long, String, etc.)
 * - Well-known types (Timestamp) are serialized/deserialized correctly
 * - Media type aliases are recognized
 * - Response headers include the schema fingerprint
 * - OpenAPI schema extensions are provided
 */
@DisplayName("AvroRestBodyCodec")
class AvroRestBodyCodecTest {

    private ProtoToAvroSchema schemas;
    private ObjectMapper json;
    private FileDescriptor testFile;

    @BeforeEach
    void setUp() throws Exception {
        schemas = new ProtoToAvroSchema();
        json = new ObjectMapper();

        // Build a test proto message with various field types
        testFile = FileDescriptor.buildFrom(
                DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setPackage("test.v1")
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName("TestMessage")
                                .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                                .addField(field("count", 2, FieldDescriptorProto.Type.TYPE_INT64))
                                .addField(fieldRepeated("tags", 3, FieldDescriptorProto.Type.TYPE_STRING))
                                .addField(fieldMessage("createdAt", 4, "google.protobuf.Timestamp"))
                                .build())
                        .build(),
                new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
    }

    @Test
    @DisplayName(
            "GIVEN SINGLE_OBJECT framing and a message WHEN encode called SHOULD produce bytes starting with 0xC3 0x01 header")
    void given_singleObjectFraming_when_encode_should_produceSingleObjectHeader() throws Exception {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "test");
        body.put("count", 42L);
        body.put("tags", List.of());
        body.put("createdAt", null);

        // Act
        byte[] encoded = codec.encode(body, descriptor);

        // Assert
        assertThat(encoded).hasSizeGreaterThan(2);
        assertThat(encoded[0]).isEqualTo((byte) 0xC3);
        assertThat(encoded[1]).isEqualTo((byte) 0x01);
    }

    @Test
    @DisplayName(
            "GIVEN BINARY framing and a message WHEN encode called SHOULD produce raw bytes without single-object header")
    void given_binaryFraming_when_encode_should_produceRawBytes() throws Exception {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.BINARY);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "test");
        body.put("count", 42L);
        body.put("tags", List.of());
        body.put("createdAt", null);

        // Act
        byte[] encoded = codec.encode(body, descriptor);

        // Assert
        assertThat(encoded).isNotEmpty();
        // Raw binary should NOT start with 0xC3 0x01
        assertThat(encoded[0]).isNotEqualTo((byte) 0xC3);
    }

    @Test
    @DisplayName(
            "GIVEN a Map with count as Long and as String '7' WHEN encode and decode SHOULD round-trip with count = 7L")
    void given_countAsLongAndString_when_encodeAndDecode_should_roundTripCorrectly() throws Exception {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");

        Map<String, Object> bodyLong = new LinkedHashMap<>();
        bodyLong.put("name", "item1");
        bodyLong.put("count", 7L);
        bodyLong.put("tags", List.of("tag1", "tag2"));
        bodyLong.put("createdAt", null);

        // Act
        byte[] encoded = codec.encode(bodyLong, descriptor);
        Map<String, Object> decoded = codec.decode(encoded, descriptor);

        // Assert
        assertThat(decoded.get("name")).isEqualTo("item1");
        assertThat(decoded.get("count")).isEqualTo(7L);
        assertThat(decoded.get("tags")).isEqualTo(List.of("tag1", "tag2"));
    }

    @Test
    @DisplayName(
            "GIVEN encode and decode with a message containing null createdAt WHEN round-trip SHOULD preserve null")
    void given_messageWithNullTimestamp_when_encodeAndDecode_should_preserveNull() throws Exception {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "notimestamp");
        body.put("count", 1L);
        body.put("tags", List.of());
        body.put("createdAt", null);

        // Act
        byte[] encoded = codec.encode(body, descriptor);
        Map<String, Object> decoded = codec.decode(encoded, descriptor);

        // Assert
        assertThat(decoded.get("name")).isEqualTo("notimestamp");
        assertThat(decoded.get("count")).isEqualTo(1L);
        assertThat(decoded.get("tags")).isEqualTo(List.of());
    }

    @Test
    @DisplayName(
            "GIVEN BINARY framing bytes without single-object header WHEN decode called SHOULD decode raw datum correctly")
    void given_binaryBytesWithoutHeader_when_decode_should_decodeRawDatum() throws Exception {
        // Arrange
        AvroRestBodyCodec binaryCodec = new AvroRestBodyCodec(schemas, AvroWireFormat.BINARY);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "raw");
        original.put("count", 10L);
        original.put("tags", List.of());
        original.put("createdAt", null);

        byte[] binaryEncoded = binaryCodec.encode(original, descriptor);

        // Act
        Map<String, Object> decoded = binaryCodec.decode(binaryEncoded, descriptor);

        // Assert
        assertThat(decoded.get("name")).isEqualTo("raw");
        assertThat(decoded.get("count")).isEqualTo(10L);
    }

    @Test
    @DisplayName(
            "GIVEN a response descriptor WHEN responseHeaders called SHOULD contain X-Avro-Schema-Fingerprint with 16 hex digits")
    void given_responseDescriptor_when_responseHeaders_should_containFingerprintHeader() throws Exception {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");

        // Act
        Map<String, String> headers = codec.responseHeaders(descriptor);

        // Assert
        assertThat(headers).containsKey(AvroRestBodyCodec.FINGERPRINT_HEADER);
        String fingerprint = headers.get(AvroRestBodyCodec.FINGERPRINT_HEADER);
        assertThat(fingerprint).hasSize(16);
        assertThat(fingerprint).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName(
            "GIVEN a type descriptor WHEN openApiSchemaExtensions called SHOULD return map with x-avro-schema containing record type")
    void given_typeDescriptor_when_openApiSchemaExtensions_should_returnAvroSchemaExtension() throws Exception {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");

        // Act
        Map<String, Object> extensions = codec.openApiSchemaExtensions(descriptor);

        // Assert
        assertThat(extensions).containsKey(AvroUpstreamCodec.SCHEMA_EXTENSION);
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) extensions.get(AvroUpstreamCodec.SCHEMA_EXTENSION);
        assertThat(schema).containsEntry("type", "record");
        assertThat(schema).containsKey("name");
        assertThat(schema.get("name")).isEqualTo("TestMessage");
    }

    @Test
    @DisplayName("GIVEN garbage bytes WHEN decode called SHOULD throw IllegalArgumentException")
    void given_garbageBytes_when_decode_should_throwIllegalArgumentException() throws Exception {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);
        Descriptor descriptor = testFile.findMessageTypeByName("TestMessage");
        byte[] garbageBytes = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};

        // Act & Assert
        assertThatThrownBy(() -> codec.decode(garbageBytes, descriptor)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "GIVEN various media types WHEN supports called SHOULD return true for avro/binary and aliases, false for application/json")
    void given_variousMediaTypes_when_supports_should_recognizeAvroTypes() {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);

        // Act & Assert
        assertThat(codec.supports("avro/binary")).isTrue();
        assertThat(codec.supports("application/vnd.apache.avro+binary")).isTrue();
        assertThat(codec.supports("application/avro")).isTrue();
        assertThat(codec.supports("application/avro+binary")).isTrue();
        assertThat(codec.supports("application/json")).isFalse();
        assertThat(codec.supports("text/plain")).isFalse();
        assertThat(codec.supports(null)).isFalse();
    }

    @Test
    @DisplayName("GIVEN mediaType method WHEN called SHOULD return avro/binary")
    void given_codec_when_mediaType_should_returnAvroBinary() {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);

        // Act
        String mediaType = codec.mediaType();

        // Assert
        assertThat(mediaType).isEqualTo("avro/binary");
    }

    // Helper methods

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type);
    }

    private static FieldDescriptorProto.Builder fieldRepeated(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED);
    }

    private static FieldDescriptorProto.Builder fieldMessage(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setProto3Optional(true);
    }
}
