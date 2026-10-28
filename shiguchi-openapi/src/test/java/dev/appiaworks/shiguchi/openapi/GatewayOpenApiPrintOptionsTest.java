package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style end-to-end tests for GatewayOpenApiBuilder with per-service JSON print options.
 *
 * Tests that the builder correctly applies JsonPrintOptions from ServiceEndpointContext to response
 * schemas while keeping request schemas at default. Verifies that different services can have
 * different print options and their components are correctly named with appropriate suffixes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayOpenApiBuilder — per-service JSON print options")
class GatewayOpenApiPrintOptionsTest {

    @Mock
    private GrpcRouteRegistry registry;

    private final ProtoToOpenApiSchemaConverter converter = new ProtoToOpenApiSchemaConverter();

    @AfterEach
    void restoreDefaults() {
        JsonPrintOptions.install(JsonPrintOptions.DEFAULT);
    }

    @Test
    @DisplayName(
            "GIVEN service with preserveProtoFieldNames=true and POST route with Book input/output WHEN build SHOULD requestBody ref to 'Book' and 200 response ref to 'Book_proto_names'")
    void given_serviceWithProtoNamesAndPostRoute_when_build_should_requestDefaultResponseProtoNames() {
        // Arrange
        ServiceEndpointContext ctx = createGrpcContextWithBook("books");
        ServiceEndpointContext protoNamesCtx = createGrpcContextWithBookAndProtoNames("books-proto");

        when(registry.allContexts()).thenReturn(List.of(ctx, protoNamesCtx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, createProperties(true), converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        assertThat(spec.getPaths()).containsKey("/v1/books");
        assertThat(spec.getPaths()).containsKey("/v1/books-proto");

        var protoPathItem = spec.getPaths().get("/v1/books-proto");
        var protoOp = protoPathItem.getPost();
        assertThat(protoOp).isNotNull();

        // Request body should use default schema (Book)
        var requestBody = protoOp.getRequestBody();
        assertThat(requestBody).isNotNull();
        var requestContent = requestBody.getContent().get("application/json");
        assertThat(requestContent.getSchema().get$ref()).isEqualTo("#/components/schemas/Book");

        // Response should use proto-names schema (Book_proto_names)
        var response = protoOp.getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        assertThat(responseContent.getSchema().get$ref()).isEqualTo("#/components/schemas/Book_proto_names");
    }

    @Test
    @DisplayName(
            "GIVEN one service with default print options and one with preserveProtoFieldNames=true WHEN build SHOULD both 'Book' and 'Book_proto_names' components be present")
    void given_servicesWithDifferentPrintOptions_when_build_should_bothComponentsPresent() {
        // Arrange
        ServiceEndpointContext defaultCtx = createGrpcContextWithBook("default");
        ServiceEndpointContext protoNamesCtx = createGrpcContextWithBookAndProtoNames("proto-names");

        when(registry.allContexts()).thenReturn(List.of(defaultCtx, protoNamesCtx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, createProperties(true), converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        assertThat(spec.getComponents().getSchemas()).containsKeys("Book", "Book_proto_names");
        var defaultBook = spec.getComponents().getSchemas().get("Book");
        var protoBook = spec.getComponents().getSchemas().get("Book_proto_names");
        assertThat(defaultBook).isNotNull();
        assertThat(protoBook).isNotNull();

        // Default has lowerCamelCase property names (converted from snake_case)
        assertThat(defaultBook.getProperties().keySet()).containsExactly("bookTitle");
        // Proto-names has snake_case property names (as declared in proto)
        assertThat(protoBook.getProperties().keySet()).containsExactly("book_title");
    }

    @Test
    @DisplayName(
            "GIVEN service with enumsAsInts=true and POST route with Book WHEN build SHOULD 200 response ref to 'Book_enums_as_ints' with enum field as integers")
    void given_serviceWithEnumsAsIntsAndPostRoute_when_build_should_responseRefEnumsAsInts() {
        // Arrange
        ServiceEndpointContext ctx = createGrpcContextWithBookAndEnumsAsInts("books-ints");

        when(registry.allContexts()).thenReturn(List.of(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, createProperties(true), converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        var response =
                spec.getPaths().get("/v1/books-ints").getPost().getResponses().get("200");
        var responseContent = response.getContent().get("application/json");
        assertThat(responseContent.getSchema().get$ref()).isEqualTo("#/components/schemas/Book_enums_as_ints");

        var bookEnumsComponent = spec.getComponents().getSchemas().get("Book_enums_as_ints");
        assertThat(bookEnumsComponent).isNotNull();
        Object formatFieldObj = bookEnumsComponent.getProperties().get("format");
        assertThat(formatFieldObj).isInstanceOf(Schema.class);
        Schema<?> formatField = (Schema<?>) formatFieldObj;
        assertThat(formatField.getType()).isEqualTo("integer");
        assertThat(formatField.getEnum()).hasSize(2);
        assertThat(formatField.getEnum().get(0)).isEqualTo(0);
        assertThat(formatField.getEnum().get(1)).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN service with default print options and GET route WHEN build SHOULD 200 response ref to 'Book' without suffix")
    void given_serviceWithDefaultOptionsAndGetRoute_when_build_should_responseRefUnsuffixed() {
        // Arrange
        ServiceEndpointContext ctx = createGrpcContextWithBookGetRoute();

        when(registry.allContexts()).thenReturn(List.of(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, createProperties(true), converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        var getOp = spec.getPaths().get("/v1/books/{id}").getGet();
        assertThat(getOp).isNotNull();
        var response = getOp.getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        assertThat(responseContent.getSchema().get$ref()).isEqualTo("#/components/schemas/Book");
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private ServiceEndpointContext createGrpcContextWithBook(String serviceName) {
        // Build Book message (used as both input and output)
        DescriptorProtos.DescriptorProto bookMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Book")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("book_title")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build service with CreateBook method where both input and output are Book
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("BookService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("CreateBook")
                        .setInputType("Book")
                        .setOutputType("Book")
                        .build())
                .build();

        // Build file descriptor
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("book.proto")
                .setPackage("com.example")
                .setSyntax("proto3")
                .addMessageType(bookMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc =
                    Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[] {});

            // Create service context
            var config = new ServiceDefinition();
            config.setName(serviceName);
            config.setHost("localhost");
            config.setPort(50051);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("book.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("com.example.BookService", fileDesc.findServiceByName("BookService"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "com.example.BookService/CreateBook",
                    fileDesc.findServiceByName("BookService").findMethodByName("CreateBook"));
            ctx.setMethodByKey(methods);

            // Add a POST route
            RouteDefinition route = RouteDefinition.builder(
                            "com.example.BookService",
                            "CreateBook",
                            MethodDescriptor.MethodType.UNARY,
                            "localhost:50051")
                    .httpBinding("POST", "/v1/" + serviceName)
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test file descriptor", e);
        }
    }

    private ServiceEndpointContext createGrpcContextWithBookAndProtoNames(String serviceName) {
        // Build Book message (used as both input and output)
        DescriptorProtos.DescriptorProto bookMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Book")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("book_title")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build service with CreateBook method where both input and output are Book
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("BookService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("CreateBook")
                        .setInputType("Book")
                        .setOutputType("Book")
                        .build())
                .build();

        // Build file descriptor
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("book.proto")
                .setPackage("com.example")
                .setSyntax("proto3")
                .addMessageType(bookMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc =
                    Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[] {});

            // Create service context with proto-names override
            var config = new ServiceDefinition();
            config.setName(serviceName);
            config.setHost("localhost");
            config.setPort(50051);
            // Set the proto-names override
            var override = new ServiceDefinition.JsonPrintOverride();
            override.setPreserveProtoFieldNames(true);
            config.setJsonPrint(override);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("book.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("com.example.BookService", fileDesc.findServiceByName("BookService"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "com.example.BookService/CreateBook",
                    fileDesc.findServiceByName("BookService").findMethodByName("CreateBook"));
            ctx.setMethodByKey(methods);

            // Add a POST route
            RouteDefinition route = RouteDefinition.builder(
                            "com.example.BookService",
                            "CreateBook",
                            MethodDescriptor.MethodType.UNARY,
                            "localhost:50051")
                    .httpBinding("POST", "/v1/" + serviceName)
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test file descriptor", e);
        }
    }

    private ServiceEndpointContext createGrpcContextWithBookAndEnumsAsInts(String serviceName) {
        // Build Format enum
        DescriptorProtos.EnumDescriptorProto formatEnum = DescriptorProtos.EnumDescriptorProto.newBuilder()
                .setName("Format")
                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                        .setName("UNKNOWN")
                        .setNumber(0)
                        .build())
                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                        .setName("HARDCOVER")
                        .setNumber(1)
                        .build())
                .build();

        // Build Book message with enum field
        DescriptorProtos.DescriptorProto bookMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Book")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("book_title")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("format")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName("Format")
                        .build())
                .build();

        // Build service with CreateBook method where both input and output are Book
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("BookService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("CreateBook")
                        .setInputType("Book")
                        .setOutputType("Book")
                        .build())
                .build();

        // Build file descriptor
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("book.proto")
                .setPackage("com.example")
                .setSyntax("proto3")
                .addMessageType(bookMsg)
                .addEnumType(formatEnum)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc =
                    Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[] {});

            // Create service context with enums-as-ints override
            var config = new ServiceDefinition();
            config.setName(serviceName);
            config.setHost("localhost");
            config.setPort(50051);
            // Set the enums-as-ints override
            var override = new ServiceDefinition.JsonPrintOverride();
            override.setEnumsAsInts(true);
            config.setJsonPrint(override);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("book.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("com.example.BookService", fileDesc.findServiceByName("BookService"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "com.example.BookService/CreateBook",
                    fileDesc.findServiceByName("BookService").findMethodByName("CreateBook"));
            ctx.setMethodByKey(methods);

            // Add a POST route
            RouteDefinition route = RouteDefinition.builder(
                            "com.example.BookService",
                            "CreateBook",
                            MethodDescriptor.MethodType.UNARY,
                            "localhost:50051")
                    .httpBinding("POST", "/v1/" + serviceName)
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test file descriptor", e);
        }
    }

    private ServiceEndpointContext createGrpcContextWithBookGetRoute() {
        // Build Book message
        DescriptorProtos.DescriptorProto bookMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Book")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("book_title")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build GetBookRequest
        DescriptorProtos.DescriptorProto getRequestMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("GetBookRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build service
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("BookService")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("GetBook")
                        .setInputType("GetBookRequest")
                        .setOutputType("Book")
                        .build())
                .build();

        // Build file descriptor
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("book.proto")
                .setPackage("com.example")
                .setSyntax("proto3")
                .addMessageType(getRequestMsg)
                .addMessageType(bookMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc =
                    Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[] {});

            // Create service context with default print options
            var config = new ServiceDefinition();
            config.setName("books");
            config.setHost("localhost");
            config.setPort(50051);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("book.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("com.example.BookService", fileDesc.findServiceByName("BookService"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "com.example.BookService/GetBook",
                    fileDesc.findServiceByName("BookService").findMethodByName("GetBook"));
            ctx.setMethodByKey(methods);

            // Add a GET route
            RouteDefinition route = RouteDefinition.builder(
                            "com.example.BookService", "GetBook", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                    .httpBinding("GET", "/v1/books/{id}")
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build test file descriptor", e);
        }
    }

    private GatewayProperties createProperties(boolean openapiEnabled) {
        var props = new GatewayProperties();
        var openApiConfig = new dev.appiaworks.shiguchi.config.OpenApiConfig();
        openApiConfig.setEnabled(openapiEnabled);
        props.setOpenapi(openApiConfig);
        return props;
    }
}
