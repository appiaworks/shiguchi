package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for GatewayOpenApiBuilder.
 *
 * Tests the building of OpenAPI specs from gRPC route definitions and REST_DOWNSTREAM contexts,
 * including path parameter extraction, response schemas, and merging of downstream documents.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GatewayOpenApiBuilder")
class GatewayOpenApiBuilderTest {

    @Mock
    private GrpcRouteRegistry registry;

    private final ProtoToOpenApiSchemaConverter converter = new ProtoToOpenApiSchemaConverter();

    @Test
    @DisplayName(
            "Given a registry with one gRPC context and route GET /v1/books/{id}, when build, should produce path with path parameter and Book response schema")
    void given_grpcContextWithBookRoute_when_build_should_producePath() {
        // Arrange
        ServiceEndpointContext ctx = createGrpcContext();
        GatewayProperties properties = createProperties(true);

        when(registry.allContexts()).thenReturn(Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        assertThat(spec.getPaths()).containsKey("/v1/books/{id}");
        var pathItem = spec.getPaths().get("/v1/books/{id}");
        var getOp = pathItem.getGet();
        assertThat(getOp).isNotNull();
        assertThat(getOp.getOperationId()).isEqualTo("getBook");
        assertThat(getOp.getParameters()).isNotEmpty();
        assertThat(getOp.getParameters().get(0).getName()).isEqualTo("id");
        assertThat(getOp.getResponses()).containsKey("200");
    }

    @Test
    @DisplayName(
            "Given proto context and REST_DOWNSTREAM context, when build, should contain both proto paths and downstream paths")
    void given_protoAndDownstreamContexts_when_build_should_containBothPaths() {
        // Arrange
        ServiceEndpointContext protoCtx = createGrpcContext();
        ServiceEndpointContext downstreamCtx = createDownstreamContext();

        GatewayProperties properties = createProperties(true);
        when(registry.allContexts()).thenReturn(List.of(protoCtx, downstreamCtx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        assertThat(spec.getPaths()).containsKeys("/v1/books/{id}", "/inv/items");
        // Components should include both proto-derived Book and downstream-derived Item
        assertThat(spec.getComponents().getSchemas()).containsKeys("Book", "inventory.Item");
    }

    @Test
    @DisplayName("Given downstream service with openapiEnabled=false, when build, should not include its paths")
    void given_downstreamWithOpenApiDisabled_when_build_should_excludePaths() {
        // Arrange
        ServiceEndpointContext protoCtx = createGrpcContext();
        ServiceEndpointContext downstreamCtx = createDownstreamContextWithOpenApiDisabled();

        GatewayProperties properties = createProperties(true);
        when(registry.allContexts()).thenReturn(List.of(protoCtx, downstreamCtx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        assertThat(spec.getPaths()).containsKey("/v1/books/{id}");
        assertThat(spec.getPaths()).doesNotContainKey("/inv/items");
    }

    @Test
    @DisplayName(
            "Given rebuild called after registering a new downstream context, when rebuilt, should include new paths")
    void given_rebuildAfterNewDownstream_when_rebuilt_should_includePaths() {
        // Arrange
        ServiceEndpointContext protoCtx = createGrpcContext();
        List<ServiceEndpointContext> contexts = new ArrayList<>(Collections.singletonList(protoCtx));

        GatewayProperties properties = createProperties(true);
        when(registry.allContexts()).thenReturn(contexts);

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);
        builder.build();

        OpenAPI spec1 = builder.getSpec();
        assertThat(spec1.getPaths()).doesNotContainKey("/inv/items");

        // Add a new downstream context
        ServiceEndpointContext downstreamCtx = createDownstreamContext();
        contexts.add(downstreamCtx);

        // Act
        builder.rebuild();
        OpenAPI spec2 = builder.getSpec();

        // Assert
        assertThat(spec2.getPaths()).containsKeys("/v1/books/{id}", "/inv/items");
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Creates a gRPC context with a simple FileDescriptor containing Book and GetBookRequest messages
     * and a GetBook route.
     */
    private ServiceEndpointContext createGrpcContext() {
        // Build Book message (output)
        DescriptorProtos.DescriptorProto bookMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Book")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("title")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build GetBookRequest message (input)
        DescriptorProtos.DescriptorProto getBookRequestMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("GetBookRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build service with GetBook method
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
                .addMessageType(getBookRequestMsg)
                .addMessageType(bookMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc =
                    Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[] {});

            // Create service context
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

            // Add a route
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

    /**
     * Creates a REST_DOWNSTREAM context with the inventory.json fixture.
     */
    private ServiceEndpointContext createDownstreamContext() {
        var config = new ServiceDefinition();
        config.setName("inventory");
        config.setPathPrefix("/inv");
        config.setInheritSecurity(false);

        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

        try {
            byte[] specBytes = loadFixture("inventory.json");
            ctx.setRawDownstreamSpec(specBytes, "application/json");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load inventory.json fixture", e);
        }

        // Add routes for exposed operations
        addRoute(ctx, "GET", "/inv/items");
        addRoute(ctx, "GET", "/inv/items/{sku}");

        return ctx;
    }

    /**
     * Creates a REST_DOWNSTREAM context with openapiEnabled=false.
     */
    private ServiceEndpointContext createDownstreamContextWithOpenApiDisabled() {
        var config = new ServiceDefinition();
        config.setName("disabled-inventory");
        config.setPathPrefix("/disabled");
        config.setOpenapiEnabled(false);

        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

        try {
            byte[] specBytes = loadFixture("inventory.json");
            ctx.setRawDownstreamSpec(specBytes, "application/json");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load inventory.json fixture", e);
        }

        return ctx;
    }

    private void addRoute(ServiceEndpointContext ctx, String method, String path) {
        RouteDefinition route = RouteDefinition.builder(
                        "service.Service", "method", MethodDescriptor.MethodType.UNARY, "localhost:8080")
                .httpBinding(method, path)
                .httpUpstream("http://localhost:8080")
                .build();
        ctx.addRoute(route);
    }

    private GatewayProperties createProperties(boolean openapiEnabled) {
        var props = new GatewayProperties();
        // Fully qualified: this package has its own OpenApiConfig (the Spring @Configuration).
        var openApiConfig = new dev.appiaworks.shiguchi.config.OpenApiConfig();
        openApiConfig.setEnabled(openapiEnabled);
        props.setOpenapi(openApiConfig);
        return props;
    }

    private byte[] loadFixture(String filename) throws IOException {
        try (var in = getClass().getResourceAsStream("/openapi/" + filename)) {
            if (in == null) throw new IOException("missing test fixture /openapi/" + filename);
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName(
            "GIVEN builder with Avro codec and a gRPC route with request body WHEN build SHOULD include both application/json and avro/binary in request content")
    void given_builderWithAvroCodec_when_build_should_includeAvroMediaTypeInRequestContent() {
        // Arrange
        ServiceEndpointContext ctx = createGrpcContext();
        GatewayProperties properties = createProperties(true);
        dev.appiaworks.shiguchi.rest.RestBodyCodec avroCodec = createFakeAvroCodec();

        when(registry.allContexts()).thenReturn(java.util.Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder =
                new GatewayOpenApiBuilder(registry, properties, converter, java.util.List.of(avroCodec));

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        assertThat(spec.getPaths()).containsKey("/v1/books/{id}");
        var pathItem = spec.getPaths().get("/v1/books/{id}");
        var getOp = pathItem.getGet();
        assertThat(getOp).isNotNull();
        // GET requests may not have request bodies, so check response content instead
        var response = getOp.getResponses().get("200");
        assertThat(response).isNotNull();
        var content = response.getContent();
        assertThat(content).containsKeys("application/json", "avro/binary");
    }

    @Test
    @DisplayName(
            "GIVEN builder with Avro codec WHEN build SHOULD add x-avro-schema extension to avro/binary media type entry")
    void given_builderWithAvroCodec_when_build_should_addAvroSchemaExtension() {
        // Arrange
        ServiceEndpointContext ctx = createGrpcContext();
        GatewayProperties properties = createProperties(true);
        dev.appiaworks.shiguchi.rest.RestBodyCodec avroCodec = createFakeAvroCodec();

        when(registry.allContexts()).thenReturn(java.util.Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder =
                new GatewayOpenApiBuilder(registry, properties, converter, java.util.List.of(avroCodec));

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        var pathItem = spec.getPaths().get("/v1/books/{id}");
        var getOp = pathItem.getGet();
        var response = getOp.getResponses().get("200");
        var content = response.getContent();
        var avroMediaType = content.get("avro/binary");

        assertThat(avroMediaType).isNotNull();
        if (avroMediaType.getExtensions() != null) {
            assertThat(avroMediaType.getExtensions()).containsKey("x-avro-schema");
        }
    }

    @Test
    @DisplayName("GIVEN builder without codecs WHEN build SHOULD include only application/json in content")
    void given_builderWithoutCodecs_when_build_should_onlyIncludeJson() {
        // Arrange
        ServiceEndpointContext ctx = createGrpcContext();
        GatewayProperties properties = createProperties(true);

        when(registry.allContexts()).thenReturn(java.util.Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter, java.util.List.of());

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert
        var pathItem = spec.getPaths().get("/v1/books/{id}");
        var getOp = pathItem.getGet();
        var response = getOp.getResponses().get("200");
        var content = response.getContent();

        assertThat(content.keySet()).containsExactly("application/json");
    }

    @Test
    @DisplayName(
            "GIVEN route returning google.api.HttpBody as response WHEN build SHOULD document 200 response with */* binary content")
    void given_httpBodyResponse_when_build_should_documentBinaryContent() {
        // Arrange: create a context where the response is google.api.HttpBody
        ServiceEndpointContext ctx = createHttpBodyResponseContext();
        GatewayProperties properties = createProperties(true);

        when(registry.allContexts()).thenReturn(java.util.Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert: the 200 response should have */* binary content
        assertThat(spec.getPaths()).containsKey("/v1/files/{id}");
        var pathItem = spec.getPaths().get("/v1/files/{id}");
        var getOp = pathItem.getGet();
        assertThat(getOp).isNotNull();
        var response = getOp.getResponses().get("200");
        assertThat(response).isNotNull();
        var content = response.getContent();
        assertThat(content).containsKey("*/*");
        var binarySchema = content.get("*/*").getSchema();
        assertThat(binarySchema.getType()).isEqualTo("string");
        assertThat(binarySchema.getFormat()).isEqualTo("binary");
    }

    @Test
    @DisplayName(
            "GIVEN route accepting google.api.HttpBody in request body WHEN build SHOULD document requestBody with */* binary content")
    void given_httpBodyRequest_when_build_should_documentBinaryRequestContent() {
        // Arrange: create a context where the request body is a field of type google.api.HttpBody
        ServiceEndpointContext ctx = createHttpBodyRequestContext();
        GatewayProperties properties = createProperties(true);

        when(registry.allContexts()).thenReturn(java.util.Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert: the PUT should have */* binary requestBody content
        assertThat(spec.getPaths()).containsKey("/v1/files/{id}");
        var pathItem = spec.getPaths().get("/v1/files/{id}");
        var putOp = pathItem.getPut();
        assertThat(putOp).isNotNull();
        var requestBody = putOp.getRequestBody();
        assertThat(requestBody).isNotNull();
        var content = requestBody.getContent();
        assertThat(content).containsKey("*/*");
        var binarySchema = content.get("*/*").getSchema();
        assertThat(binarySchema.getType()).isEqualTo("string");
        assertThat(binarySchema.getFormat()).isEqualTo("binary");
    }

    // -------------------------------------------------------------------------
    // Helper methods for HttpBody contexts
    // -------------------------------------------------------------------------

    /**
     * Creates a gRPC context where Download method returns google.api.HttpBody directly.
     */
    private ServiceEndpointContext createHttpBodyResponseContext() {
        // Build google.api.HttpBody message (response)
        DescriptorProtos.DescriptorProto httpBodyMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("HttpBody")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("content_type")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("data")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES)
                        .build())
                .build();

        // Build GetFileRequest message (input)
        DescriptorProtos.DescriptorProto getFileRequestMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("GetFileRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build service with Download method
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("Files")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("Download")
                        .setInputType("GetFileRequest")
                        .setOutputType("HttpBody")
                        .build())
                .build();

        // Build file descriptor with google.api package
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("files.proto")
                .setPackage("google.api")
                .setSyntax("proto3")
                .addMessageType(getFileRequestMsg)
                .addMessageType(httpBodyMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc =
                    Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[] {});

            // Create service context
            var config = new ServiceDefinition();
            config.setName("files");
            config.setHost("localhost");
            config.setPort(50051);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("files.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("google.api.Files", fileDesc.findServiceByName("Files"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "google.api.Files/Download",
                    fileDesc.findServiceByName("Files").findMethodByName("Download"));
            ctx.setMethodByKey(methods);

            // Add a route
            RouteDefinition route = RouteDefinition.builder(
                            "google.api.Files", "Download", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                    .httpBinding("GET", "/v1/files/{id}")
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build HttpBody response context", e);
        }
    }

    /**
     * Creates a gRPC context where Upload method takes a request with google.api.HttpBody field.
     */
    private ServiceEndpointContext createHttpBodyRequestContext() {
        // Build google.api.HttpBody message
        DescriptorProtos.DescriptorProto httpBodyMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("HttpBody")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("content_type")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("data")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES)
                        .build())
                .build();

        // Build UploadRequest message with HttpBody field
        DescriptorProtos.DescriptorProto uploadRequestMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("UploadRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("file")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("HttpBody")
                        .build())
                .build();

        // Build UploadReply message (response)
        DescriptorProtos.DescriptorProto uploadReplyMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("UploadReply")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("size")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
                        .build())
                .build();

        // Build service with Upload method
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("Files")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("Upload")
                        .setInputType("UploadRequest")
                        .setOutputType("UploadReply")
                        .build())
                .build();

        // Build file descriptor with google.api package
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("files.proto")
                .setPackage("google.api")
                .setSyntax("proto3")
                .addMessageType(httpBodyMsg)
                .addMessageType(uploadRequestMsg)
                .addMessageType(uploadReplyMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc =
                    Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[] {});

            // Create service context
            var config = new ServiceDefinition();
            config.setName("files");
            config.setHost("localhost");
            config.setPort(50051);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("files.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("google.api.Files", fileDesc.findServiceByName("Files"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "google.api.Files/Upload",
                    fileDesc.findServiceByName("Files").findMethodByName("Upload"));
            ctx.setMethodByKey(methods);

            // Add a route with body="file" to indicate HttpBody request
            RouteDefinition route = RouteDefinition.builder(
                            "google.api.Files", "Upload", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                    .httpBinding("PUT", "/v1/files/{id}", "file")
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build HttpBody request context", e);
        }
    }

    // Helper method to create a fake Avro codec for testing

    private static dev.appiaworks.shiguchi.rest.RestBodyCodec createFakeAvroCodec() {
        return new dev.appiaworks.shiguchi.rest.RestBodyCodec() {
            @Override
            public String mediaType() {
                return "avro/binary";
            }

            @Override
            public boolean supports(String mediaType) {
                return mediaType != null && mediaType.equals("avro/binary");
            }

            @Override
            public java.util.Map<String, Object> decode(
                    byte[] body, com.google.protobuf.Descriptors.Descriptor requestType) {
                return java.util.Map.of();
            }

            @Override
            public byte[] encode(
                    java.util.Map<String, Object> response, com.google.protobuf.Descriptors.Descriptor responseType) {
                return new byte[0];
            }

            @Override
            public java.util.Map<String, Object> openApiSchemaExtensions(
                    com.google.protobuf.Descriptors.Descriptor type) {
                return java.util.Map.of("x-avro-schema", java.util.Map.of("type", "record", "name", type.getName()));
            }
        };
    }

    @Test
    @DisplayName(
            "GIVEN server-streaming route returning google.api.HttpBody WHEN build SHOULD document response with */* binary content")
    void given_serverStreamingHttpBodyResponse_when_build_should_documentBinaryContent() {
        // Arrange: create a context where server-streaming Export method returns google.api.HttpBody
        ServiceEndpointContext ctx = createServerStreamingHttpBodyResponseContext();
        GatewayProperties properties = createProperties(true);

        when(registry.allContexts()).thenReturn(java.util.Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert: the GET /v1/exports/{id} should have */* binary response
        assertThat(spec.getPaths()).containsKey("/v1/exports/{id}");
        var pathItem = spec.getPaths().get("/v1/exports/{id}");
        var getOp = pathItem.getGet();
        assertThat(getOp).isNotNull();
        var response = getOp.getResponses().get("200");
        assertThat(response).isNotNull();
        var content = response.getContent();
        assertThat(content).containsKey("*/*");
        var binarySchema = content.get("*/*").getSchema();
        assertThat(binarySchema.getType()).isEqualTo("string");
        assertThat(binarySchema.getFormat()).isEqualTo("binary");
    }

    @Test
    @DisplayName(
            "GIVEN server-streaming route with message output and response_body naming HttpBody field WHEN build SHOULD document response as JSON not binary (streams ignore response_body)")
    void given_serverStreamingWithResponseBodyHttpBody_when_build_should_documentJsonResponse() {
        // Arrange: create a context where server-streaming method returns a wrapper message
        // with a response_body pointing to an HttpBody field (which should be ignored for streams)
        ServiceEndpointContext ctx = createServerStreamingWithResponseBodyContext();
        GatewayProperties properties = createProperties(true);

        when(registry.allContexts()).thenReturn(java.util.Collections.singletonList(ctx));

        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, properties, converter);

        // Act
        builder.build();
        OpenAPI spec = builder.getSpec();

        // Assert: the GET is a stream of whole messages (NDJSON / SSE), not */* binary
        // (response_body is ignored on streams, so the full message schema is used)
        assertThat(spec.getPaths()).containsKey("/v1/wrapped-exports/{id}");
        var pathItem = spec.getPaths().get("/v1/wrapped-exports/{id}");
        var getOp = pathItem.getGet();
        assertThat(getOp).isNotNull();
        var response = getOp.getResponses().get("200");
        assertThat(response).isNotNull();
        var content = response.getContent();
        // A stream of JSON messages, not */*
        assertThat(content).containsKey("application/x-ndjson");
        assertThat(content).doesNotContainKey("*/*");
    }

    /**
     * Creates a gRPC context where Export method is server-streaming and returns
     * google.api.HttpBody directly. Uses the actual google.api.HttpBody from the API protos.
     */
    private ServiceEndpointContext createServerStreamingHttpBodyResponseContext() {
        // Build ExportRequest message (input)
        DescriptorProtos.DescriptorProto exportRequestMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("ExportRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build service with Export method (server-streaming returning google.api.HttpBody)
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("Files")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("Export")
                        .setInputType(".files.streaming.ExportRequest")
                        .setOutputType(".google.api.HttpBody")
                        .setServerStreaming(true)
                        .build())
                .build();

        // Build file descriptor with proper dependency on google.api.httpbody
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("files.proto")
                .setPackage("files.streaming")
                .setSyntax("proto3")
                .addDependency("google/api/httpbody.proto")
                .addMessageType(exportRequestMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc = Descriptors.FileDescriptor.buildFrom(
                    fileProto, new Descriptors.FileDescriptor[] {com.google.api.HttpBodyProto.getDescriptor()});

            // Create service context
            var config = new ServiceDefinition();
            config.setName("files");
            config.setHost("localhost");
            config.setPort(50051);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("google/api/httpbody.proto", com.google.api.HttpBodyProto.getDescriptor());
            files.put("files.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("files.streaming.Files", fileDesc.findServiceByName("Files"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "files.streaming.Files/Export",
                    fileDesc.findServiceByName("Files").findMethodByName("Export"));
            ctx.setMethodByKey(methods);

            // Add a route (server-streaming)
            RouteDefinition route = RouteDefinition.builder(
                            "files.streaming.Files",
                            "Export",
                            MethodDescriptor.MethodType.SERVER_STREAMING,
                            "localhost:50051")
                    .httpBinding("GET", "/v1/exports/{id}")
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build server-streaming HttpBody response context", e);
        }
    }

    /**
     * Creates a gRPC context where a server-streaming method returns a wrapper message with
     * response_body naming an HttpBody field (which should be ignored by streams).
     */
    private ServiceEndpointContext createServerStreamingWithResponseBodyContext() {
        // Build ExportRequest message (input)
        DescriptorProtos.DescriptorProto exportRequestMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("ExportRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        // Build WrappedExport message (output, contains HttpBody field)
        DescriptorProtos.DescriptorProto wrappedMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WrappedExport")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("body")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.api.HttpBody")
                        .build())
                .build();

        // Build service with WrappedExport method (server-streaming)
        DescriptorProtos.ServiceDescriptorProto serviceProto = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName("Files")
                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                        .setName("WrappedExport")
                        .setInputType(".files.wrapped.ExportRequest")
                        .setOutputType(".files.wrapped.WrappedExport")
                        .setServerStreaming(true)
                        .build())
                .build();

        // Build file descriptor with proper dependency
        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("files.proto")
                .setPackage("files.wrapped")
                .setSyntax("proto3")
                .addDependency("google/api/httpbody.proto")
                .addMessageType(exportRequestMsg)
                .addMessageType(wrappedMsg)
                .addService(serviceProto)
                .build();

        try {
            Descriptors.FileDescriptor fileDesc = Descriptors.FileDescriptor.buildFrom(
                    fileProto, new Descriptors.FileDescriptor[] {com.google.api.HttpBodyProto.getDescriptor()});

            // Create service context
            var config = new ServiceDefinition();
            config.setName("files");
            config.setHost("localhost");
            config.setPort(50051);

            ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

            // Set descriptors
            Map<String, Descriptors.FileDescriptor> files = new HashMap<>();
            files.put("google/api/httpbody.proto", com.google.api.HttpBodyProto.getDescriptor());
            files.put("files.proto", fileDesc);
            ctx.setFileDescriptors(files);

            // Set service and method indexes
            Map<String, Descriptors.ServiceDescriptor> services = new HashMap<>();
            services.put("files.wrapped.Files", fileDesc.findServiceByName("Files"));
            ctx.setServiceByFqn(services);

            Map<String, Descriptors.MethodDescriptor> methods = new HashMap<>();
            methods.put(
                    "files.wrapped.Files/WrappedExport",
                    fileDesc.findServiceByName("Files").findMethodByName("WrappedExport"));
            ctx.setMethodByKey(methods);

            // Add a route (server-streaming with response_body that should be ignored)
            RouteDefinition route = RouteDefinition.builder(
                            "files.wrapped.Files",
                            "WrappedExport",
                            MethodDescriptor.MethodType.SERVER_STREAMING,
                            "localhost:50051")
                    .httpBinding("GET", "/v1/wrapped-exports/{id}")
                    .httpResponseBody("body")
                    .build();
            ctx.addRoute(route);

            return ctx;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build server-streaming with response_body context", e);
        }
    }
}
