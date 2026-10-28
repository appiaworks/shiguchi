package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for AvroRegistryRegistrar.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>{@code messageTypes} collects the request/response types of gRPC-backed routes only,
 *       skipping HTTP passthrough routes, keyed by proto full name
 *   <li>{@code onSchemaReloaded} registers those types with the schema registry through the
 *       {@link AvroRestBodyCodec}
 *   <li>A registry that is unreachable does not propagate an exception out of the reload path
 * </ul>
 */
@DisplayName("AvroRegistryRegistrar tests")
class AvroRegistryRegistrarTest {

    private static final String SERVICE = "library";

    private StubSchemaRegistry stub;
    private GrpcRouteRegistry routeRegistry;
    private FileDescriptor testFile;
    private Descriptor requestType;
    private Descriptor responseType;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubSchemaRegistry();
        routeRegistry = new GrpcRouteRegistry();

        testFile = FileDescriptor.buildFrom(
                DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("library.proto")
                        .setPackage("library.v1")
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName("GetBookRequest")
                                .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                                .build())
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName("Book")
                                .addField(field("title", 1, FieldDescriptorProto.Type.TYPE_STRING))
                                .build())
                        .addService(ServiceDescriptorProto.newBuilder()
                                .setName("LibraryService")
                                .addMethod(MethodDescriptorProto.newBuilder()
                                        .setName("GetBook")
                                        .setInputType(".library.v1.GetBookRequest")
                                        .setOutputType(".library.v1.Book")
                                        .build())
                                .build())
                        .build(),
                new FileDescriptor[0]);
        requestType = testFile.findMessageTypeByName("GetBookRequest");
        responseType = testFile.findMessageTypeByName("Book");
    }

    @AfterEach
    void tearDown() {
        stub.dispose();
    }

    private ServiceEndpointContext contextWithGrpcAndPassthroughRoutes() {
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName(SERVICE);
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);

        Map<String, FileDescriptor> files = new HashMap<>();
        files.put(testFile.getName(), testFile);
        context.setFileDescriptors(files);

        com.google.protobuf.Descriptors.MethodDescriptor protoMethod =
                testFile.findServiceByName("LibraryService").findMethodByName("GetBook");
        Map<String, com.google.protobuf.Descriptors.MethodDescriptor> methodByKey = new HashMap<>();
        methodByKey.put("library.v1.LibraryService/GetBook", protoMethod);
        context.setMethodByKey(methodByKey);

        RouteDefinition grpcRoute = RouteDefinition.builder(
                        "library.v1.LibraryService",
                        "GetBook",
                        io.grpc.MethodDescriptor.MethodType.UNARY,
                        "localhost:1")
                .httpBinding("GET", "/v1/books/{id}")
                .build();
        context.addRoute(grpcRoute);

        RouteDefinition passthroughRoute = RouteDefinition.builder(
                        "library.v1.LibraryService", "Legacy", io.grpc.MethodDescriptor.MethodType.UNARY, "localhost:1")
                .httpUpstream("http://legacy-backend:8080")
                .build();
        context.addRoute(passthroughRoute);

        return context;
    }

    @Test
    @DisplayName(
            "GIVEN a registry with one gRPC route and one passthrough route WHEN messageTypes called SHOULD return only the gRPC route's request and response types, keyed by full name")
    void given_grpcAndPassthroughRoutes_when_messageTypes_should_returnOnlyGrpcRouteTypes() {
        // Arrange
        routeRegistry.registerContext(contextWithGrpcAndPassthroughRoutes());
        AvroRestBodyCodec codec = new AvroRestBodyCodec(new ProtoToAvroSchema(), AvroWireFormat.SINGLE_OBJECT);
        AvroRegistryRegistrar registrar = new AvroRegistryRegistrar(codec, routeRegistry);

        // Act
        Map<String, Descriptor> types = registrar.messageTypes();

        // Assert
        assertThat(types).containsKey("library.v1.GetBookRequest");
        assertThat(types).containsKey("library.v1.Book");
        assertThat(types).hasSize(2);
    }

    @Test
    @DisplayName(
            "GIVEN gRPC route types WHEN onSchemaReloaded called SHOULD register both message types' schemas with the registry")
    void given_grpcRouteTypes_when_onSchemaReloaded_should_registerBothSchemas() {
        // Arrange
        routeRegistry.registerContext(contextWithGrpcAndPassthroughRoutes());
        SchemaRegistryClient client = new SchemaRegistryClient(stub.url());
        AvroRestBodyCodec codec = new AvroRestBodyCodec(new ProtoToAvroSchema(), AvroWireFormat.CONFLUENT, client);
        AvroRegistryRegistrar registrar = new AvroRegistryRegistrar(codec, routeRegistry);

        // Act
        registrar.onSchemaReloaded();

        // Assert
        assertThat(stub.receivedRequests())
                .anyMatch(r -> r.equals("POST /subjects/library.v1.GetBookRequest/versions"))
                .anyMatch(r -> r.equals("POST /subjects/library.v1.Book/versions"));
    }

    @Test
    @DisplayName("GIVEN a schema registry that is unreachable WHEN onSchemaReloaded called SHOULD not throw")
    void given_unreachableRegistry_when_onSchemaReloaded_should_notThrow() {
        // Arrange
        routeRegistry.registerContext(contextWithGrpcAndPassthroughRoutes());
        int closedPort = stub.port();
        stub.dispose();
        SchemaRegistryClient client =
                new SchemaRegistryClient("http://localhost:" + closedPort, java.time.Duration.ofSeconds(2));
        AvroRestBodyCodec codec = new AvroRestBodyCodec(new ProtoToAvroSchema(), AvroWireFormat.CONFLUENT, client);
        AvroRegistryRegistrar registrar = new AvroRegistryRegistrar(codec, routeRegistry);

        // Act & Assert
        assertThatNoException().isThrownBy(registrar::onSchemaReloaded);
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type);
    }
}
