package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BDD-style tests for AvroSchemaController.
 *
 * Tests verify that:
 * - resolve(String name) finds schemas by proto full name or fingerprint hex
 * - resolve derives schemas on demand from service descriptors
 * - schema endpoint returns 200 with fingerprint header or 404
 */
@DisplayName("AvroSchemaController")
class AvroSchemaControllerTest {

    private AvroRestBodyCodec codec;
    private GrpcRouteRegistry registry;
    private AvroSchemaController controller;
    private FileDescriptor testFile;

    @BeforeEach
    void setUp() throws Exception {
        codec = new AvroRestBodyCodec(new dev.appiaworks.shiguchi.config.GatewayProperties());
        registry = new GrpcRouteRegistry();

        // Build a test proto file with a message
        testFile = FileDescriptor.buildFrom(
                DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setPackage("library.v1")
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName("Book")
                                .addField(field("title", 1, FieldDescriptorProto.Type.TYPE_STRING))
                                .addField(field("author", 2, FieldDescriptorProto.Type.TYPE_STRING))
                                .build())
                        .build(),
                new FileDescriptor[0]);

        controller = new AvroSchemaController(codec, registry);
    }

    @Test
    @DisplayName(
            "GIVEN a GrpcRouteRegistry with a service context containing proto descriptors WHEN resolve called with proto full name SHOULD derive schema on demand")
    void given_registryWithServiceContext_when_resolveByName_should_deriveSchemaOnDemand() throws Exception {
        // Arrange
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("library");
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);

        Map<String, FileDescriptor> files = new HashMap<>();
        files.put(testFile.getName(), testFile);
        context.setFileDescriptors(files);
        registry.registerContext(context);

        // Act
        Optional<Schema> result = controller.resolve("library.v1.Book");

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(result.get().getName()).isEqualTo("Book");
        assertThat(result.get().getNamespace()).isEqualTo("library.v1");
    }

    @Test
    @DisplayName(
            "GIVEN a derived schema WHEN resolve called with its fingerprint as 16-hex string SHOULD return the schema")
    void given_derivedSchema_when_resolveByFingerprint_should_findIt() throws Exception {
        // Arrange
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("library");
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);

        Map<String, FileDescriptor> files = new HashMap<>();
        files.put(testFile.getName(), testFile);
        context.setFileDescriptors(files);
        registry.registerContext(context);

        // First, derive the schema to populate the cache
        Optional<Schema> first = controller.resolve("library.v1.Book");
        assertThat(first).isPresent();

        String fingerprintHex = AvroRestBodyCodec.fingerprintHex(first.get());

        // Act
        Optional<Schema> result = controller.resolve(fingerprintHex);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Book");
    }

    @Test
    @DisplayName(
            "GIVEN a registry without a particular message WHEN resolve called with non-existent name SHOULD return empty Optional")
    void given_registryWithoutMessage_when_resolveUnknownName_should_returnEmpty() throws Exception {
        // Arrange
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("library");
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);

        Map<String, FileDescriptor> files = new HashMap<>();
        files.put(testFile.getName(), testFile);
        context.setFileDescriptors(files);
        registry.registerContext(context);

        // Act
        Optional<Schema> result = controller.resolve("library.v1.NonExistent");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a valid proto message WHEN schema endpoint called with full name SHOULD return 200 with schema and fingerprint header")
    void given_validMessage_when_schemaEndpoint_should_return200WithHeader() throws Exception {
        // Arrange
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("library");
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);

        Map<String, FileDescriptor> files = new HashMap<>();
        files.put(testFile.getName(), testFile);
        context.setFileDescriptors(files);
        registry.registerContext(context);

        // Act
        ResponseEntity<String> response = controller.schema("library.v1.Book");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotBlank();
        assertThat(response.getHeaders()).containsKey(AvroRestBodyCodec.FINGERPRINT_HEADER);
        String fingerprint = response.getHeaders().getFirst(AvroRestBodyCodec.FINGERPRINT_HEADER);
        assertThat(fingerprint).hasSize(16);
        assertThat(fingerprint).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("GIVEN an unknown message name WHEN schema endpoint called SHOULD return 404 Not Found")
    void given_unknownMessage_when_schemaEndpoint_should_return404() {
        // Arrange
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("library");
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);
        registry.registerContext(context);

        // Act
        ResponseEntity<String> response = controller.schema("library.v1.Unknown");

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    // Helper methods

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type);
    }
}
