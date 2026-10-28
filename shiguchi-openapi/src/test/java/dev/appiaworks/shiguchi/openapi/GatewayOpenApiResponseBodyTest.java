package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor.MethodType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for GatewayOpenApiBuilder honouring google.api.http response_body.
 *
 * When a route's response_body names a field of the output message and the route is UNARY (not
 * server-streaming) and not a merge, the 200 response content is application/json ONLY with the
 * schema of that field. Otherwise (no response_body, "*", unknown field, server-streaming, or
 * merge) the 200 response is the whole message $ref, which may have multiple media types from
 * codecs.
 */
@DisplayName("GatewayOpenApiBuilder: google.api.http response_body")
class GatewayOpenApiResponseBodyTest {

    private static final FileDescriptor FILE = buildFile();

    @Test
    @DisplayName(
            "GIVEN unary GET route with response_body \"books\" (repeated field) WHEN built SHOULD 200 response content be application/json array of Book items with no ListBooksResponse $ref")
    void given_unaryGetWithResponseBodyRepeated_when_built_should_responseContentBeArrayOfItems() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "ListBooks", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books")
                .httpResponseBody("books")
                .build()));

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        assertThat(responseContent).isNotNull();
        var responseSchema = responseContent.getSchema();

        // Response schema should be an array (ArraySchema)
        assertThat(responseSchema).isInstanceOf(ArraySchema.class);
        var arraySchema = (ArraySchema) responseSchema;

        // Items should be a $ref to Book
        var itemsSchema = arraySchema.getItems();
        assertThat(itemsSchema.get$ref()).endsWith("/Book");

        // The whole ListBooksResponse should NOT be in the response
        assertThat(spec.getComponents().getSchemas()).doesNotContainKey("ListBooksResponse");
    }

    @Test
    @DisplayName(
            "GIVEN unary route with response_body \"books\" and a codec present WHEN built SHOULD response content be application/json ONLY (no codec media type)")
    void given_responseBodyWithCodec_when_built_should_responseContentJsonOnly() {
        // Create a fake codec that adds a non-JSON media type
        dev.appiaworks.shiguchi.rest.RestBodyCodec fakeCodec = mock(dev.appiaworks.shiguchi.rest.RestBodyCodec.class);
        when(fakeCodec.mediaType()).thenReturn("avro/binary");
        // Mock any Descriptor to return empty extensions
        when(fakeCodec.openApiSchemaExtensions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of());

        var def = new ServiceDefinition();
        def.setName("library");
        ServiceEndpointContext ctx = new ServiceEndpointContext(def, null);
        ctx.setFileDescriptors(Map.of(FILE.getName(), FILE));
        ctx.setServiceByFqn(Map.of("lib.Library", FILE.findServiceByName("Library")));
        MethodDescriptor list = FILE.findServiceByName("Library").findMethodByName("ListBooks");
        ctx.setMethodByKey(Map.of("lib.Library/ListBooks", list));

        RouteDefinition route = RouteDefinition.builder("lib.Library", "ListBooks", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books")
                .httpResponseBody("books")
                .build();
        ctx.addRoute(route);

        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        when(registry.allContexts()).thenReturn(List.of(ctx));
        var props = new GatewayProperties();
        props.getOpenapi().setEnabled(true);

        // Builder with fake codec
        GatewayOpenApiBuilder builder =
                new GatewayOpenApiBuilder(registry, props, new ProtoToOpenApiSchemaConverter(), List.of(fakeCodec));
        builder.build();
        OpenAPI spec = builder.getSpec();

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        var content = response.getContent();

        // Should have only application/json, not the codec's media type
        assertThat(content.keySet()).containsExactly("application/json");
        assertThat(content).doesNotContainKey("avro/binary");
    }

    @Test
    @DisplayName(
            "GIVEN route with response_body \"*\" (star) WHEN built SHOULD 200 response ref to whole ListBooksResponse message")
    void given_responseBodyStar_when_built_should_responseRefWholeMessage() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "ListBooks", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books")
                .httpResponseBody("*")
                .build()));

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        assertThat(responseContent.getSchema().get$ref()).isEqualTo("#/components/schemas/ListBooksResponse");
    }

    @Test
    @DisplayName(
            "GIVEN route with response_body naming a field that does not exist WHEN built SHOULD 200 response ref to whole message")
    void given_responseBodyUnknownField_when_built_should_responseRefWholeMessage() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "ListBooks", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books")
                .httpResponseBody("nonexistent_field")
                .build()));

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        assertThat(responseContent.getSchema().get$ref()).isEqualTo("#/components/schemas/ListBooksResponse");
    }

    @Test
    @DisplayName(
            "GIVEN server-streaming route with response_body \"books\" WHEN built SHOULD 200 response ref to whole ListBooksResponse (not the field)")
    void given_serverStreamingWithResponseBody_when_built_should_responseRefWholeMessage() {
        OpenAPI spec =
                build(List.of(RouteDefinition.builder("lib.Library", "ListBooks", MethodType.SERVER_STREAMING, "t")
                        .httpBinding("GET", "/v1/books")
                        .httpResponseBody("books")
                        .build()));

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        assertThat(response).isNotNull();
        // A stream is described per message, framed as NDJSON or SSE; each item is the whole message.
        assertThat(response.getContent()).doesNotContainKey("application/json");
        var responseContent = response.getContent().get("application/x-ndjson");
        assertThat(responseContent.getSchema().get$ref()).isEqualTo("#/components/schemas/ListBooksResponse");
    }

    @Test
    @DisplayName(
            "GIVEN route with response_body \"next_page_token\" (scalar string field) WHEN built SHOULD 200 response schema type be string")
    void given_responseBodyScalarField_when_built_should_responseSchemaBeScalarType() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "ListBooks", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books")
                .httpResponseBody("next_page_token")
                .build()));

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        var schema = responseContent.getSchema();

        // Scalar string field should have type "string"
        assertThat(schema.getType()).isEqualTo("string");

        // Should not be an array and should not be a $ref
        assertThat(schema).isNotInstanceOf(ArraySchema.class);
        assertThat(schema.get$ref()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN service with preserveProtoFieldNames=true and response_body \"books\" (repeated message field) WHEN built SHOULD 200 response be array of Book_proto_names items")
    void given_responseBodyWithProtoNames_when_built_should_responseArrayUseProtoNamesComponent() {
        // Create service context with proto-names override
        var def = new ServiceDefinition();
        def.setName("library");
        def.setHost("localhost");
        def.setPort(50051);
        var override = new ServiceDefinition.JsonPrintOverride();
        override.setPreserveProtoFieldNames(true);
        def.setJsonPrint(override);

        ServiceEndpointContext ctx = new ServiceEndpointContext(def, null);
        ctx.setFileDescriptors(Map.of(FILE.getName(), FILE));
        ctx.setServiceByFqn(Map.of("lib.Library", FILE.findServiceByName("Library")));
        MethodDescriptor list = FILE.findServiceByName("Library").findMethodByName("ListBooks");
        ctx.setMethodByKey(Map.of("lib.Library/ListBooks", list));

        RouteDefinition route = RouteDefinition.builder("lib.Library", "ListBooks", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books")
                .httpResponseBody("books")
                .build();
        ctx.addRoute(route);

        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        when(registry.allContexts()).thenReturn(List.of(ctx));
        var props = new GatewayProperties();
        props.getOpenapi().setEnabled(true);
        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, props, new ProtoToOpenApiSchemaConverter());
        builder.build();
        OpenAPI spec = builder.getSpec();

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        var responseSchema = responseContent.getSchema();

        // Should be an array
        assertThat(responseSchema).isInstanceOf(ArraySchema.class);
        var arraySchema = (ArraySchema) responseSchema;

        // Items should be a $ref to Book_proto_names (the variant with proto field names)
        var itemsSchema = arraySchema.getItems();
        assertThat(itemsSchema.get$ref()).endsWith("/Book_proto_names");

        // Book_proto_names component should exist and have snake_case field names
        var bookComponent = spec.getComponents().getSchemas().get("Book_proto_names");
        assertThat(bookComponent).isNotNull();
        assertThat(bookComponent.getProperties()).containsKey("display_name");
    }

    @Test
    @DisplayName(
            "GIVEN route without response_body (null) WHEN built SHOULD 200 response ref to whole ListBooksResponse")
    void given_noResponseBody_when_built_should_responseRefWholeMessage() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "ListBooks", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books")
                // No httpResponseBody() call
                .build()));

        var response = spec.getPaths().get("/v1/books").getGet().getResponses().get("200");
        assertThat(response).isNotNull();
        var responseContent = response.getContent().get("application/json");
        assertThat(responseContent.getSchema().get$ref()).isEqualTo("#/components/schemas/ListBooksResponse");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static OpenAPI build(List<RouteDefinition> routes) {
        var def = new ServiceDefinition();
        def.setName("library");
        ServiceEndpointContext ctx = new ServiceEndpointContext(def, null);
        ctx.setFileDescriptors(Map.of(FILE.getName(), FILE));
        ctx.setServiceByFqn(Map.of("lib.Library", FILE.findServiceByName("Library")));
        MethodDescriptor list = FILE.findServiceByName("Library").findMethodByName("ListBooks");
        ctx.setMethodByKey(Map.of("lib.Library/ListBooks", list));
        routes.forEach(ctx::addRoute);

        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        when(registry.allContexts()).thenReturn(List.of(ctx));
        var props = new GatewayProperties();
        props.getOpenapi().setEnabled(true);
        GatewayOpenApiBuilder builder = new GatewayOpenApiBuilder(registry, props, new ProtoToOpenApiSchemaConverter());
        builder.build();
        return builder.getSpec();
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/library.proto")
                    .setPackage("lib")
                    .setSyntax("proto3")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(scalar("display_name", 1))
                            .build())
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListBooksRequest")
                            .build())
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListBooksResponse")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("books")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".lib.Book")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                                    .build())
                            .addField(scalar("next_page_token", 2))
                            .build())
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Library")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListBooks")
                                    .setInputType(".lib.ListBooksRequest")
                                    .setOutputType(".lib.ListBooksResponse")
                                    .build())
                            .build())
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
