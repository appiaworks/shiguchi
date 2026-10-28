package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.BadRequest;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule.Op;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Rich gRPC errors on the Boot 4 GraphQL surface (DGS on spring-graphql), in front of a real gRPC
 * server: a failed backend call is one GraphQL error whose message is the backend's description and
 * whose {@code extensions} carry {@code grpcCode}, the derived {@code errorType}, the service and
 * method, and the backend's {@code google.rpc.Status} details as JSON.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("gRPC error details on the GraphQL surface end to end (Boot 4, DGS)")
class GraphQlErrorDetailsEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "store");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private Struct graphql(String query) throws Exception {
        String body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        return parse(body);
    }

    @Test
    @DisplayName(
            "GIVEN the backend fails with INVALID_ARGUMENT carrying a BadRequest WHEN queried SHOULD answer one error with the description as message and grpcCode, errorType, service, method and details in extensions")
    void given_badRequestFailure_when_queried_should_exposeDetailsInExtensions() throws Exception {
        Struct response = graphql("{ item(id: \"bad\") { id } }");

        assertThat(at(response, "data", "item").hasNullValue()).isTrue();
        assertThat(at(response, "errors").getListValue().getValuesCount()).isEqualTo(1);
        assertThat(str(response, "errors", 0, "message")).isEqualTo("id must be numeric");
        assertThat(str(response, "errors", 0, "path", 0)).isEqualTo("item");
        assertThat(str(response, "errors", 0, "extensions", "grpcCode")).isEqualTo("INVALID_ARGUMENT");
        assertThat(str(response, "errors", 0, "extensions", "errorType")).isEqualTo("BAD_REQUEST");
        assertThat(str(response, "errors", 0, "extensions", "service")).isEqualTo("errs.Store");
        assertThat(str(response, "errors", 0, "extensions", "method")).isEqualTo("GetItem");
        assertThat(str(response, "errors", 0, "extensions", "details", 0, "@type"))
                .isEqualTo("type.googleapis.com/google.rpc.BadRequest");
        assertThat(str(response, "errors", 0, "extensions", "details", 0, "fieldViolations", 0, "field"))
                .isEqualTo("id");
        assertThat(str(response, "errors", 0, "extensions", "details", 0, "fieldViolations", 0, "description"))
                .isEqualTo("must be numeric");
    }

    @Test
    @DisplayName(
            "GIVEN the backend fails without details WHEN queried SHOULD map the code and carry no details extension")
    void given_plainFailure_when_queried_should_mapCodeWithoutDetails() throws Exception {
        Struct response = graphql("{ item(id: \"plain\") { id } }");

        assertThat(str(response, "errors", 0, "message")).isEqualTo("no item plain");
        assertThat(str(response, "errors", 0, "extensions", "grpcCode")).isEqualTo("NOT_FOUND");
        assertThat(str(response, "errors", 0, "extensions", "errorType")).isEqualTo("NOT_FOUND");
        assertThat(at(response, "errors", 0, "extensions", "details")).isNull();
    }

    @Test
    @DisplayName("GIVEN the backend succeeds WHEN queried SHOULD answer data and no errors")
    void given_success_when_queried_should_answerData() throws Exception {
        Struct response = graphql("{ item(id: \"7\") { id payload } }");

        assertThat(response.containsFields("errors")).isFalse();
        assertThat(str(response, "data", "item", "id")).isEqualTo("7");
    }

    // JSON is read back through protobuf's Struct so the same test source compiles on both Boot
    // lines (Boot 4's test classpath has no Jackson 2).
    private static Struct parse(String json) throws Exception {
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().merge(json, b);
        return b.build();
    }

    /** Walks a parsed document: a String segment is a member, an Integer segment a list index. */
    private static Value at(Struct root, Object... path) {
        Value v = Value.newBuilder().setStructValue(root).build();
        for (Object seg : path) {
            if (seg instanceof Integer i) {
                if (!v.hasListValue() || v.getListValue().getValuesCount() <= i) return null;
                v = v.getListValue().getValues(i);
            } else {
                if (!v.hasStructValue() || !v.getStructValue().containsFields((String) seg)) return null;
                v = v.getStructValue().getFieldsOrThrow((String) seg);
            }
        }
        return v;
    }

    private static String str(Struct root, Object... path) {
        Value v = at(root, path);
        return v == null ? null : v.getStringValue();
    }

    // ------------------------------------------------------------------ fixtures

    /** errs.Store/GetItem(GetRequest{id}) returns Item{id,payload}, bound as query item. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("errs/store.proto")
                    .setSyntax("proto3")
                    .setPackage("errs")
                    .addDependency("shiguchi/api/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("payload", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetItem")
                                    .setInputType(".errs.GetRequest")
                                    .setOutputType(".errs.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("item")
                                                            .setType(Op.QUERY)
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("errs-store", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("errs.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("errs.Store")
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String id = (String) request.getField(GET_REQUEST.findFieldByName("id"));
                    switch (id) {
                        case "bad" ->
                            observer.onError(StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                                    .setCode(Status.Code.INVALID_ARGUMENT.value())
                                    .setMessage("id must be numeric")
                                    .addDetails(Any.pack(BadRequest.newBuilder()
                                            .addFieldViolations(BadRequest.FieldViolation.newBuilder()
                                                    .setField("id")
                                                    .setDescription("must be numeric"))
                                            .build()))
                                    .build()));
                        case "plain" ->
                            observer.onError(Status.NOT_FOUND
                                    .withDescription("no item plain")
                                    .asRuntimeException());
                        default -> {
                            observer.onNext(DynamicMessage.newBuilder(ITEM)
                                    .setField(ITEM.findFieldByName("id"), id)
                                    .setField(ITEM.findFieldByName("payload"), "ok")
                                    .build());
                            observer.onCompleted();
                        }
                    }
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
