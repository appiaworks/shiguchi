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
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the OpenAPI document says about a route whose google.api.http rule names a body field:
 * the request body is that field's schema and the remaining fields stay query parameters,
 * while an additional binding shows up as its own path.
 */
@DisplayName("GatewayOpenApiBuilder: rule body field and additional bindings")
class GatewayOpenApiBodyFieldTest {

    private static final FileDescriptor FILE = buildFile();

    @Test
    @DisplayName(
            "GIVEN POST /v1/{parent}/books with body: \"book\" WHEN built SHOULD use Book as the request body and keep validateOnly as a query parameter")
    void given_bodyField_when_built_should_useFieldSchemaAsBodyAndOthersAsQuery() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "CreateBook", MethodType.UNARY, "t")
                .httpBinding("POST", "/v1/{parent}/books", "book")
                .build()));

        Operation post = spec.getPaths().get("/v1/{parent}/books").getPost();
        assertThat(post).isNotNull();
        var json = post.getRequestBody().getContent().get("application/json").getSchema();
        assertThat(json.get$ref()).endsWith("/Book");
        assertThat(post.getParameters())
                .extracting(Parameter::getName, Parameter::getIn)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("parent", "path"),
                        org.assertj.core.groups.Tuple.tuple("validateOnly", "query"));
    }

    @Test
    @DisplayName("GIVEN POST with body: \"*\" WHEN built SHOULD use the whole request message as the body")
    void given_starBody_when_built_should_useWholeMessage() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "CreateBook", MethodType.UNARY, "t")
                .httpBinding("POST", "/v1/books", "*")
                .build()));

        var json = spec.getPaths()
                .get("/v1/books")
                .getPost()
                .getRequestBody()
                .getContent()
                .get("application/json")
                .getSchema();
        assertThat(json.get$ref()).endsWith("/CreateBookRequest");
    }

    @Test
    @DisplayName("GIVEN a primary and an additional binding of one RPC WHEN built SHOULD document both paths")
    void given_additionalBinding_when_built_should_documentBothPaths() {
        var primary = RouteDefinition.builder("lib.Library", "CreateBook", MethodType.UNARY, "t")
                .httpBinding("POST", "/v1/{parent}/books", "book")
                .build();
        var extra = RouteDefinition.builder("lib.Library", "CreateBook", MethodType.UNARY, "t")
                .httpBinding("POST", "/v1/books", "*")
                .build();

        OpenAPI spec = build(List.of(primary, extra));

        assertThat(spec.getPaths()).containsKeys("/v1/{parent}/books", "/v1/books");
    }

    @Test
    @DisplayName(
            "GIVEN a dotted path variable {book.id} WHEN built SHOULD not also advertise book as a query parameter")
    void given_dottedPathVariable_when_built_should_notRepeatFieldAsQuery() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "CreateBook", MethodType.UNARY, "t")
                .httpBinding("GET", "/v1/books/{book.id}", null)
                .build()));

        Operation get = spec.getPaths().get("/v1/books/{book.id}").getGet();
        assertThat(get.getParameters())
                .extracting(Parameter::getName, Parameter::getIn)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("book.id", "path"),
                        org.assertj.core.groups.Tuple.tuple("parent", "query"),
                        org.assertj.core.groups.Tuple.tuple("validateOnly", "query"));
    }

    @Test
    @DisplayName(
            "GIVEN a resource-name template {parent=shelves/*} with a verb WHEN built SHOULD document /v1/{parent}/books:import with parent as a path parameter")
    void given_resourceNameTemplate_when_built_should_nameThePathParameterOnly() {
        OpenAPI spec = build(List.of(RouteDefinition.builder("lib.Library", "CreateBook", MethodType.UNARY, "t")
                .httpBinding("POST", "/v1/{parent=shelves/*}/books:import", "book")
                .build()));

        assertThat(spec.getPaths()).containsOnlyKeys("/v1/{parent}/books:import");
        assertThat(spec.getPaths().get("/v1/{parent}/books:import").getPost().getParameters())
                .extracting(Parameter::getName, Parameter::getIn)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("parent", "path"),
                        org.assertj.core.groups.Tuple.tuple("validateOnly", "query"));
    }

    private static OpenAPI build(List<RouteDefinition> routes) {
        var def = new ServiceDefinition();
        def.setName("library");
        ServiceEndpointContext ctx = new ServiceEndpointContext(def, null);
        ctx.setFileDescriptors(Map.of(FILE.getName(), FILE));
        ctx.setServiceByFqn(Map.of("lib.Library", FILE.findServiceByName("Library")));
        MethodDescriptor create = FILE.findServiceByName("Library").findMethodByName("CreateBook");
        ctx.setMethodByKey(Map.of("lib.Library/CreateBook", create));
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
                            .addField(scalar("id", 1))
                            .addField(scalar("title", 2)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("CreateBookRequest")
                            .addField(scalar("parent", 1))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("book")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".lib.Book")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("validate_only")
                                    .setNumber(3)
                                    .setType(FieldDescriptorProto.Type.TYPE_BOOL)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Library")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("CreateBook")
                                    .setInputType(".lib.CreateBookRequest")
                                    .setOutputType(".lib.Book")))
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
