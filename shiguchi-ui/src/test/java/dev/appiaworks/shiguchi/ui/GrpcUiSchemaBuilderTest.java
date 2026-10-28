package dev.appiaworks.shiguchi.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor.MethodType;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for {@link GrpcUiSchemaBuilder}: the console's read model over every registered
 * gRPC service. Descriptors are built by hand with {@link DescriptorProtos} (same approach as
 * {@code MixedBackendMergeTest} in shiguchi-rest) rather than compiled .proto fixtures, so
 * each test controls exactly the shape it needs — including deliberately self-referential
 * messages to exercise the recursion guard.
 */
@DisplayName("GrpcUiSchemaBuilder.getSchema")
class GrpcUiSchemaBuilderTest {

    @Test
    @DisplayName("GIVEN no registered contexts WHEN building the schema SHOULD return an empty list")
    void given_noContexts_when_building_should_returnEmptySchema() {
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(new GrpcRouteRegistry());

        builder.rebuild();

        assertThat(builder.getSchema()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a registered gRPC context WHEN building the schema SHOULD list its service, methods and target")
    void given_grpcContext_when_building_should_listServiceMethodsAndTarget() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = simpleServiceFile("schema.pkg", false);
        ServiceEndpointContext ctx = context("orders-svc", file, false);
        registry.registerContext(ctx);
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        assertThat(builder.getSchema()).hasSize(1);
        GrpcUiSchemaDto.ServiceSchema service = builder.getSchema().get(0);
        assertThat(service.name()).isEqualTo("schema.pkg.OrderService");
        assertThat(service.namespace()).isEqualTo("schema.pkg");
        assertThat(service.configName()).isEqualTo("orders-svc");
        assertThat(service.methods())
                .extracting(GrpcUiSchemaDto.MethodSchema::name)
                .containsExactly("Get");
    }

    @Test
    @DisplayName("GIVEN a REST_DOWNSTREAM context (no gRPC channel) WHEN building the schema SHOULD exclude it")
    void given_restDownstreamContext_when_building_should_excludeFromSchema() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = simpleServiceFile("schema.rest", false);
        ServiceEndpointContext ctx = context("rest-svc", file, true);
        registry.registerContext(ctx);
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        assertThat(builder.getSchema()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a unary method WHEN building its schema SHOULD classify it as UNARY")
    void given_unaryMethod_when_building_should_classifyAsUnary() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = simpleServiceFile("schema.unary", false);
        registry.registerContext(context("unary-svc", file, false));
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        assertThat(builder.getSchema().get(0).methods().get(0).methodType()).isEqualTo("UNARY");
    }

    @Test
    @DisplayName("GIVEN a server-streaming method WHEN building its schema SHOULD classify it as SERVER_STREAMING")
    void given_serverStreamingMethod_when_building_should_classifyAsServerStreaming() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = simpleServiceFile("schema.stream", true);
        registry.registerContext(context("stream-svc", file, false));
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        assertThat(builder.getSchema().get(0).methods().get(0).methodType()).isEqualTo("SERVER_STREAMING");
    }

    @Test
    @DisplayName(
            "GIVEN a method with a REST binding on its route WHEN building its schema SHOULD carry the HTTP method and path")
    void given_methodWithRestBinding_when_building_should_carryHttpMethodAndPath() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = simpleServiceFile("schema.rest2", false);
        ServiceEndpointContext ctx = context("bound-svc", file, false);
        RouteDefinition route = RouteDefinition.builder(
                        "schema.rest2.OrderService", "Get", MethodType.UNARY, "bound-svc")
                .httpBinding("GET", "/v1/orders/{id}")
                .build();
        ctx.addRoute(route);
        registry.registerContext(ctx);
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        GrpcUiSchemaDto.MethodSchema method =
                builder.getSchema().get(0).methods().get(0);
        assertThat(method.httpMethod()).isEqualTo("GET");
        assertThat(method.httpPath()).isEqualTo("/v1/orders/{id}");
    }

    @Test
    @DisplayName("GIVEN a method with no REST binding WHEN building its schema SHOULD leave HTTP method and path null")
    void given_methodWithoutRestBinding_when_building_should_leaveHttpFieldsNull() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = simpleServiceFile("schema.norest", false);
        registry.registerContext(context("norest-svc", file, false));
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        GrpcUiSchemaDto.MethodSchema method =
                builder.getSchema().get(0).methods().get(0);
        assertThat(method.httpMethod()).isNull();
        assertThat(method.httpPath()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN an input message with a nested message and an enum field WHEN building its schema SHOULD inline both")
    void given_nestedMessageAndEnumFields_when_building_should_inlineFieldTree() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = nestedFieldFile("schema.nested");
        registry.registerContext(context("nested-svc", file, false));
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        GrpcUiSchemaDto.MessageSchema input =
                builder.getSchema().get(0).methods().get(0).inputSchema();
        GrpcUiSchemaDto.FieldSchema nestedField = input.fields().stream()
                .filter(f -> f.name().equals("detail"))
                .findFirst()
                .orElseThrow();
        assertThat(nestedField.type()).isEqualTo("message");
        assertThat(nestedField.fields())
                .extracting(GrpcUiSchemaDto.FieldSchema::name)
                .contains("note");

        GrpcUiSchemaDto.FieldSchema enumField = input.fields().stream()
                .filter(f -> f.name().equals("status"))
                .findFirst()
                .orElseThrow();
        assertThat(enumField.type()).isEqualTo("enum");
        assertThat(enumField.enumValues())
                .extracting(GrpcUiSchemaDto.EnumValueSchema::name)
                .contains("ACTIVE", "INACTIVE");
    }

    @Test
    @DisplayName(
            "GIVEN a self-referential message field WHEN building its schema SHOULD stop recursion instead of looping forever")
    void given_selfReferentialMessageField_when_building_should_stopRecursion() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        FileDescriptor file = selfReferentialFile("schema.recursive");
        registry.registerContext(context("recursive-svc", file, false));
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);

        builder.rebuild();

        GrpcUiSchemaDto.MessageSchema input =
                builder.getSchema().get(0).methods().get(0).inputSchema();
        GrpcUiSchemaDto.FieldSchema selfField = input.fields().stream()
                .filter(f -> f.name().equals("parent"))
                .findFirst()
                .orElseThrow();
        // Visited-set guard: the nested occurrence of the same message type yields no further fields.
        assertThat(selfField.fields()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN onSchemaReloaded is invoked WHEN a new context has since been registered SHOULD reflect it")
    void given_onSchemaReloaded_when_newContextRegistered_should_reflectUpdatedRegistry() throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);
        builder.rebuild();
        assertThat(builder.getSchema()).isEmpty();

        FileDescriptor file = simpleServiceFile("schema.reload", false);
        registry.registerContext(context("reload-svc", file, false));
        builder.onSchemaReloaded();

        assertThat(builder.getSchema()).hasSize(1);
    }

    @Test
    @DisplayName(
            "GIVEN Date and TimeOfDay request fields WHEN building the schema SHOULD tag them DATE and TIME_OF_DAY so the form shows pickers")
    void given_mappedGoogleTypeFields_when_building_should_tagForPickers() throws Exception {
        GrpcUiSchemaDto.MessageSchema input = datedInput("schema.dated");

        assertThat(field(input, "day").wellKnownType()).isEqualTo("DATE");
        assertThat(field(input, "day").fields()).isEmpty();
        assertThat(field(input, "opens").wellKnownType()).isEqualTo("TIME_OF_DAY");
    }

    @Test
    @DisplayName(
            "GIVEN google.type mappings switched off WHEN building the schema SHOULD show a Date field as its year / month / day fields")
    void given_mappingOff_when_building_should_showDateAsMessage() throws Exception {
        dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.install(
                dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.PROTO3);
        try {
            GrpcUiSchemaDto.FieldSchema day = field(datedInput("schema.dated.off"), "day");

            assertThat(day.wellKnownType()).isNull();
            assertThat(day.fields())
                    .extracting(GrpcUiSchemaDto.FieldSchema::name)
                    .containsExactly("year", "month", "day");
        } finally {
            dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.install(
                    dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.DEFAULTS);
        }
    }

    private static GrpcUiSchemaDto.MessageSchema datedInput(String pkg) throws Exception {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context("dated-svc", datedFile(pkg), false));
        GrpcUiSchemaBuilder builder = new GrpcUiSchemaBuilder(registry);
        builder.rebuild();
        return builder.getSchema().get(0).methods().get(0).inputSchema();
    }

    private static GrpcUiSchemaDto.FieldSchema field(GrpcUiSchemaDto.MessageSchema message, String name) {
        return message.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    // -------------------------------------------------------------------------

    private static ServiceEndpointContext context(String name, FileDescriptor file, boolean restDownstream)
            throws Exception {
        var config = new ServiceDefinition();
        config.setName(name);
        var ctx = new ServiceEndpointContext(
                config,
                restDownstream
                        ? null
                        : io.grpc.inprocess.InProcessChannelBuilder.forName("unused-" + name)
                                .build());
        if (restDownstream) {
            // isRestDownstream() keys off a non-null raw spec, not the null channel.
            ctx.setRawDownstreamSpec(new byte[] {'{', '}'}, "application/json");
        }
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        ServiceDescriptor service = file.getServices().get(0);
        ctx.setServiceByFqn(Map.of(service.getFullName(), service));
        return ctx;
    }

    /** One service ("OrderService") with a single method ("Get") taking a flat "GetRequest" with an "id" field. */
    private static FileDescriptor simpleServiceFile(String pkg, boolean streaming) throws Exception {
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("GetRequest")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto response = DescriptorProto.newBuilder()
                .setName("GetResponse")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("OrderService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("Get")
                        .setInputType("." + pkg + ".GetRequest")
                        .setOutputType("." + pkg + ".GetResponse")
                        .setServerStreaming(streaming))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName(pkg.replace('.', '_') + ".proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addMessageType(request)
                .addMessageType(response)
                .addService(service)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
    }

    /** Request message with a nested "detail" message field and a "status" enum field. */
    private static FileDescriptor nestedFieldFile(String pkg) throws Exception {
        DescriptorProto detail = DescriptorProto.newBuilder()
                .setName("Detail")
                .addField(scalar("note", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        EnumDescriptorProto status = EnumDescriptorProto.newBuilder()
                .setName("Status")
                .addValue(
                        EnumValueDescriptorProto.newBuilder().setName("ACTIVE").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder()
                        .setName("INACTIVE")
                        .setNumber(1))
                .build();
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("GetRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("detail")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + pkg + ".Detail")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("status")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName("." + pkg + ".Status")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        DescriptorProto response =
                DescriptorProto.newBuilder().setName("GetResponse").build();
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("OrderService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("Get")
                        .setInputType("." + pkg + ".GetRequest")
                        .setOutputType("." + pkg + ".GetResponse"))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName(pkg.replace('.', '_') + ".proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addMessageType(detail)
                .addMessageType(request)
                .addMessageType(response)
                .addEnumType(status)
                .addService(service)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
    }

    /** Request message ("Node") with a "parent" field pointing back to itself. */
    private static FileDescriptor selfReferentialFile(String pkg) throws Exception {
        DescriptorProto node = DescriptorProto.newBuilder()
                .setName("Node")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("parent")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + pkg + ".Node")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        DescriptorProto response =
                DescriptorProto.newBuilder().setName("GetResponse").build();
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("OrderService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("Get")
                        .setInputType("." + pkg + ".Node")
                        .setOutputType("." + pkg + ".GetResponse"))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName(pkg.replace('.', '_') + ".proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addMessageType(node)
                .addMessageType(response)
                .addService(service)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
    }

    /** Request message with a google.type.Date "day" and a google.type.TimeOfDay "opens". */
    private static FileDescriptor datedFile(String pkg) throws Exception {
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("GetRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("day")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.type.Date")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("opens")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.type.TimeOfDay")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        DescriptorProto response =
                DescriptorProto.newBuilder().setName("GetResponse").build();
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("OrderService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("Get")
                        .setInputType("." + pkg + ".GetRequest")
                        .setOutputType("." + pkg + ".GetResponse"))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName(pkg.replace('.', '_') + ".proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addDependency("google/type/date.proto")
                .addDependency("google/type/timeofday.proto")
                .addMessageType(request)
                .addMessageType(response)
                .addService(service)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[] {
            com.google.type.DateProto.getDescriptor(), com.google.type.TimeOfDayProto.getDescriptor()
        });
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
