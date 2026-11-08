package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.reflection.GraphQlOptionReader;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A proto written for another gateway, carrying {@code graphql.*} options (extension 1079) as
 * unknown fields, read through {@code shiguchi.graphql.compat-options=graphql-1079}: the options
 * name the GraphQL operations and hide a field. With the property off the options are ignored and
 * the convention-based names apply.
 */
@DisplayName("graphql.* (1079) option aliases end to end (Boot 4)")
class GraphQl1079CompatEndToEndTest {

    private static final FileDescriptor GRAPHQL_FILE = buildGraphqlFile();
    private static final FileDescriptor FILE = buildServiceFile();
    private static final Descriptor GET_USER_REQUEST = FILE.findMessageTypeByName("GetUserRequest");
    private static final Descriptor CREATE_USER_REQUEST = FILE.findMessageTypeByName("CreateUserRequest");
    private static final Descriptor USER = FILE.findMessageTypeByName("User");

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "users");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
    @DisplayName("compat-options=graphql-1079")
    static class CompatOn extends Base {

        @SpringBootConfiguration
        @EnableAutoConfiguration
        static class TestApp {}

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            backend(registry);
            registry.add("shiguchi.graphql.compat-options", () -> GraphQlOptionReader.GRAPHQL_1079);
        }

        @Test
        @DisplayName(
                "GIVEN graphql.schema options WHEN the schema is introspected SHOULD expose the aliased Query and Mutation fields and omit the hidden field")
        void given_aliasOptions_when_introspected_should_useAliasNamesAndOmitField() throws Exception {
            assertThat(fieldNames("Query")).contains("fetchUser").doesNotContain("userGetUser");
            assertThat(fieldNames("Mutation")).contains("makeUser").doesNotContain("userCreateUser");
            assertThat(fieldNames("User")).contains("id", "name").doesNotContain("internalNote");
        }

        @Test
        @DisplayName("GIVEN an aliased query WHEN it is executed SHOULD reach the gRPC backend and answer the user")
        void given_aliasedQuery_when_executed_should_answerFromBackend() throws Exception {
            Struct response = graphql("{ fetchUser(id: \"1\") { id name } }");

            assertThat(response.containsFields("errors"))
                    .as(response.toString())
                    .isFalse();
            Struct user = response.getFieldsOrThrow("data")
                    .getStructValue()
                    .getFieldsOrThrow("fetchUser")
                    .getStructValue();
            assertThat(user.getFieldsOrThrow("id").getStringValue()).isEqualTo("1");
            assertThat(user.getFieldsOrThrow("name").getStringValue()).isEqualTo("User 1");
        }

        @Test
        @DisplayName(
                "GIVEN a binding read from the alias WHEN the route registry is asked SHOULD record graphql-1079 as its option source")
        void given_aliasBinding_when_registryQueried_should_recordOptionSource() {
            List<RouteDefinition> routes = registry.findByGraphQlName("fetchUser");

            assertThat(routes).hasSize(1);
            assertThat(routes.get(0).getGraphQlName()).isEqualTo("fetchUser");
            assertThat(routes.get(0).getGraphQlOptionSource()).isEqualTo("graphql-1079");
        }
    }

    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
    @DisplayName("compat-options off")
    static class CompatOff extends Base {

        @SpringBootConfiguration
        @EnableAutoConfiguration
        static class TestApp {}

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            backend(registry);
        }

        @Test
        @DisplayName(
                "GIVEN graphql.schema options and no compat setting WHEN the schema is introspected SHOULD use convention names and keep the field")
        void given_noCompat_when_introspected_should_useConventionNames() throws Exception {
            assertThat(fieldNames("Query")).contains("userGetUser").doesNotContain("fetchUser");
            assertThat(fieldNames("Mutation")).contains("userCreateUser").doesNotContain("makeUser");
            assertThat(fieldNames("User")).contains("id", "name", "internalNote");
        }

        @Test
        @DisplayName("GIVEN no compat setting WHEN the route registry is asked SHOULD hold no option source")
        void given_noCompat_when_registryQueried_should_haveNoOptionSource() {
            List<RouteDefinition> routes = registry.findByGraphQlName("userGetUser");

            assertThat(routes).hasSize(1);
            assertThat(routes.get(0).getGraphQlOptionSource()).isNull();
        }
    }

    abstract static class Base {

        @Autowired
        Environment environment;

        @Autowired
        GrpcRouteRegistry registry;

        WebClient client;

        @BeforeEach
        void bindClient() {
            client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        }

        Struct graphql(String query) throws Exception {
            String body = client.post()
                    .uri("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("query", query))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            Struct.Builder b = Struct.newBuilder();
            JsonFormat.parser().merge(body, b);
            return b.build();
        }

        List<String> fieldNames(String typeName) throws Exception {
            Struct response = graphql("{ __type(name: \"" + typeName + "\") { fields { name } } }");
            assertThat(response.containsFields("errors"))
                    .as(response.toString())
                    .isFalse();
            Value type = response.getFieldsOrThrow("data").getStructValue().getFieldsOrThrow("__type");
            assertThat(type.hasStructValue())
                    .as("type %s: %s", typeName, response)
                    .isTrue();
            return type.getStructValue().getFieldsOrThrow("fields").getListValue().getValuesList().stream()
                    .map(v -> v.getStructValue().getFieldsOrThrow("name").getStringValue())
                    .toList();
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** The upstream's options file: package graphql, extensions numbered 1079 on method and field options. */
    private static FileDescriptor buildGraphqlFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("graphql.proto")
                    .setSyntax("proto3")
                    .setPackage("graphql")
                    .addDependency("google/protobuf/descriptor.proto")
                    .addEnumType(EnumDescriptorProto.newBuilder()
                            .setName("GraphqlType")
                            .addValue(enumValue("QUERY", 0))
                            .addValue(enumValue("MUTATION", 1))
                            .addValue(enumValue("RESOLVER", 2))
                            .addValue(enumValue("SUBSCRIPTION", 3)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GraphqlRequest")
                            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("plucks", 2, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GraphqlResponse")
                            .addField(field("required", 1, FieldDescriptorProto.Type.TYPE_BOOL, null))
                            .addField(field("pluck", 2, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GraphqlSchema")
                            .addField(field("type", 1, FieldDescriptorProto.Type.TYPE_ENUM, ".graphql.GraphqlType"))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field(
                                    "request", 3, FieldDescriptorProto.Type.TYPE_MESSAGE, ".graphql.GraphqlRequest"))
                            .addField(field(
                                    "response", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".graphql.GraphqlResponse")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GraphqlField")
                            .addField(field("required", 1, FieldDescriptorProto.Type.TYPE_BOOL, null))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("default", 3, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("omit", 4, FieldDescriptorProto.Type.TYPE_BOOL, null))
                            .addField(field("resolver", 5, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addExtension(
                            field("schema", 1079, FieldDescriptorProto.Type.TYPE_MESSAGE, ".graphql.GraphqlSchema")
                                    .setExtendee(".google.protobuf.MethodOptions"))
                    .addExtension(field("field", 1079, FieldDescriptorProto.Type.TYPE_MESSAGE, ".graphql.GraphqlField")
                            .setExtendee(".google.protobuf.FieldOptions"))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {DescriptorProtos.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** compat.UserService: GetUser as query fetchUser, CreateUser as mutation makeUser; User.internal_note omitted. */
    private static FileDescriptor buildServiceFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("compat/users.proto")
                    .setSyntax("proto3")
                    .setPackage("compat")
                    .addDependency("graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetUserRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("CreateUserRequest")
                            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("User")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("internal_note", 3, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("internalNote")
                                    .setOptions(omitOption())))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("UserService")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetUser")
                                    .setInputType(".compat.GetUserRequest")
                                    .setOutputType(".compat.User")
                                    .setOptions(schemaOption("QUERY", "fetchUser")))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("CreateUser")
                                    .setInputType(".compat.CreateUserRequest")
                                    .setOutputType(".compat.User")
                                    .setOptions(schemaOption("MUTATION", "makeUser"))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GRAPHQL_FILE});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A graphql.schema option, carried as an unknown field the way a descriptor set delivers it. */
    private static MethodOptions schemaOption(String type, String name) {
        EnumDescriptor typeEnum = GRAPHQL_FILE.findEnumTypeByName("GraphqlType");
        Descriptor schema = GRAPHQL_FILE.findMessageTypeByName("GraphqlSchema");
        DynamicMessage msg = DynamicMessage.newBuilder(schema)
                .setField(schema.findFieldByName("type"), typeEnum.findValueByName(type))
                .setField(schema.findFieldByName("name"), name)
                .build();
        return MethodOptions.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(
                                1079,
                                UnknownFieldSet.Field.newBuilder()
                                        .addLengthDelimited(msg.toByteString())
                                        .build())
                        .build())
                .build();
    }

    private static FieldOptions omitOption() {
        Descriptor fieldType = GRAPHQL_FILE.findMessageTypeByName("GraphqlField");
        DynamicMessage msg = DynamicMessage.newBuilder(fieldType)
                .setField(fieldType.findFieldByName("omit"), true)
                .build();
        return FieldOptions.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(
                                1079,
                                UnknownFieldSet.Field.newBuilder()
                                        .addLengthDelimited(msg.toByteString())
                                        .build())
                        .build())
                .build();
    }

    private static EnumValueDescriptorProto.Builder enumValue(String name, int number) {
        return EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number);
    }

    private static FieldDescriptorProto.Builder field(
            String name, int number, FieldDescriptorProto.Type type, String typeName) {
        FieldDescriptorProto.Builder b = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
        if (typeName != null) b.setTypeName(typeName);
        return b;
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("compat-users", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GRAPHQL_FILE.toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        var getUser = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("compat.UserService/GetUser")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_USER_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(USER))
                .build();
        var createUser = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("compat.UserService/CreateUser")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(CREATE_USER_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(USER))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("compat.UserService")
                .addMethod(getUser, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String id = (String) request.getField(GET_USER_REQUEST.findFieldByName("id"));
                    observer.onNext(user(id, "User " + id));
                    observer.onCompleted();
                }))
                .addMethod(createUser, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String name = (String) request.getField(CREATE_USER_REQUEST.findFieldByName("name"));
                    observer.onNext(user("new", name));
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DynamicMessage user(String id, String name) {
        return DynamicMessage.newBuilder(USER)
                .setField(USER.findFieldByName("id"), id)
                .setField(USER.findFieldByName("name"), name)
                .setField(USER.findFieldByName("internal_note"), "secret")
                .build();
    }
}
