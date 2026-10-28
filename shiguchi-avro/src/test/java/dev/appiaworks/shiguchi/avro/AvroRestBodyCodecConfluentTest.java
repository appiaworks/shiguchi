package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for AvroRestBodyCodec's schema-registry integration (CONFLUENT framing on the
 * gateway's own REST surface).
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>Before {@code registerSchemas} is called, encoding under CONFLUENT fails clearly
 *   <li>After registration, {@code encode} frames bytes with the registry id and {@code decode}
 *       round-trips them
 *   <li>A request framed under a schema version the registry already held (registered directly,
 *       not through this codec) still decodes, because {@code registerSchemas} fetches every
 *       version of the subject
 *   <li>The constructor validates its registry requirement, and the no-registry constructor's
 *       {@code registerSchemas} is a documented no-op
 * </ul>
 */
@DisplayName("AvroRestBodyCodec Confluent wire format tests")
class AvroRestBodyCodecConfluentTest {

    private StubSchemaRegistry stub;
    private ProtoToAvroSchema schemas;
    private FileDescriptor testFile;
    private Descriptor descriptor;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubSchemaRegistry();
        schemas = new ProtoToAvroSchema();

        testFile = FileDescriptor.buildFrom(
                DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setPackage("test.v1")
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName("TestMessage")
                                .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                                .addField(field("count", 2, FieldDescriptorProto.Type.TYPE_INT64))
                                .build())
                        .build(),
                new FileDescriptor[0]);
        descriptor = testFile.findMessageTypeByName("TestMessage");
    }

    @AfterEach
    void tearDown() {
        stub.dispose();
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "test");
        body.put("count", 42L);
        return body;
    }

    @Test
    @DisplayName(
            "GIVEN CONFLUENT framing and a schema not yet registered WHEN encode called SHOULD throw IllegalStateException mentioning 'not registered'")
    void given_schemaNotYetRegistered_when_encode_should_throwIllegalStateExceptionNotRegistered() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.CONFLUENT, client);

        // Act & Assert
        assertThatThrownBy(() -> codec.encode(body(), descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    @DisplayName(
            "GIVEN registerSchemas called for the descriptor WHEN encode called SHOULD produce bytes starting with 00 and the assigned id, and decode should round-trip")
    void given_registeredSchema_when_encodeAndDecode_should_roundTripWithAssignedId() {
        // Arrange
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.CONFLUENT, client);
        int registered = codec.registerSchemas(List.of(descriptor));
        assertThat(registered).isEqualTo(1);

        // Act
        byte[] encoded = codec.encode(body(), descriptor);
        Map<String, Object> decoded = codec.decode(encoded, descriptor);

        // Assert
        assertThat(encoded[0]).isEqualTo((byte) 0x00);
        assertThat(decoded.get("name")).isEqualTo("test");
        assertThat(decoded.get("count")).isEqualTo(42L);
    }

    @Test
    @DisplayName(
            "GIVEN an evolved version already registered on the registry before registerSchemas WHEN a Confluent-framed request of that version is decoded SHOULD succeed")
    void given_evolvedVersionRegisteredBeforeRegisterSchemas_when_decodeConfluentRequest_should_succeed() {
        // Arrange: an older writer schema for the same record, registered directly on the stub
        // (simulating a prior deployment), before this codec loads and fetches every version.
        Schema currentSchema = schemas.schemaFor(descriptor);
        Schema olderSchema = org.apache.avro.SchemaBuilder.record(currentSchema.getName())
                .namespace(currentSchema.getNamespace())
                .fields()
                .name("name")
                .type()
                .stringType()
                .noDefault()
                .endRecord();
        int olderId = stub.seedVersion(currentSchema.getFullName(), olderSchema.toString());

        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.CONFLUENT, client);
        codec.registerSchemas(List.of(descriptor));

        org.apache.avro.generic.GenericData.Record olderRecord =
                new org.apache.avro.generic.GenericData.Record(olderSchema);
        olderRecord.put("name", "legacy");
        byte[] confluentBytes = AvroFraming.encodeConfluent(olderRecord, olderSchema, olderId);

        // Act
        Map<String, Object> decoded = codec.decode(confluentBytes, descriptor);

        // Assert
        assertThat(decoded.get("name")).isEqualTo("legacy");
    }

    @Test
    @DisplayName(
            "GIVEN CONFLUENT framing and a null registry client WHEN the codec is constructed SHOULD throw IllegalStateException")
    void given_confluentFramingAndNullClient_when_constructed_should_throwIllegalStateException() {
        // Act & Assert
        assertThatThrownBy(() -> new AvroRestBodyCodec(schemas, AvroWireFormat.CONFLUENT, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
            "GIVEN the two-arg constructor with no registry WHEN registerSchemas called SHOULD return 0 and be a no-op")
    void given_noRegistryConfigured_when_registerSchemas_should_returnZeroAndBeNoOp() {
        // Arrange
        AvroRestBodyCodec codec = new AvroRestBodyCodec(schemas, AvroWireFormat.SINGLE_OBJECT);

        // Act
        int registered = codec.registerSchemas(List.of(descriptor));

        // Assert
        assertThat(registered).isEqualTo(0);
        assertThat(codec.registryClient()).isEmpty();
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type);
    }
}
