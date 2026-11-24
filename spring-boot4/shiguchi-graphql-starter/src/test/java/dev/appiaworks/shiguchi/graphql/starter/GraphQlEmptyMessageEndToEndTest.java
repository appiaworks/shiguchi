package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.GraphQlRule.Op;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Empty protobuf messages on the GraphQL surface: the schema builds (an object or input type needs
 * a field), an empty response resolves to {@code {_placeholder: null}}, and the placeholder in an
 * empty request message is accepted and never reaches the proto message. Modeled on lbi-id-api's
 * {@code UnlinkResponse {}}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("GraphQL empty protobuf messages end to end (Boot 4, DGS)")
class GraphQlEmptyMessageEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor UNLINK_REQUEST = FILE.findMessageTypeByName("UnlinkRequest");
    private static final Descriptor UNLINK_RESPONSE = FILE.findMessageTypeByName("UnlinkResponse");
    private static final Descriptor PING_REQUEST = FILE.findMessageTypeByName("PingRequest");
    private static final List<String> UNLINK_CALLS = new CopyOnWriteArrayList<>();

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "ident");
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
        UNLINK_CALLS.clear();
    }

    private Struct graphql(String query) throws Exception {
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

    @Test
    @DisplayName(
            "GIVEN an RPC with an empty response WHEN the placeholder is selected SHOULD return a null placeholder")
    void GIVEN_emptyResponse_WHEN_placeholderSelected_SHOULD_returnNullPlaceholder() throws Exception {
        Struct response = graphql("mutation { unlink(input: { id: \"a\" }) { _placeholder } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertThat(response.getFieldsOrThrow("data")
                        .getStructValue()
                        .getFieldsOrThrow("unlink")
                        .getStructValue())
                .satisfies(unlink -> assertThat(
                                unlink.getFieldsOrThrow("_placeholder").hasNullValue())
                        .isTrue());
    }

    @Test
    @DisplayName("GIVEN an RPC with an empty response WHEN __typename is selected SHOULD resolve the object")
    void GIVEN_emptyResponse_WHEN_typenameSelected_SHOULD_resolveObject() throws Exception {
        Struct response = graphql("mutation { unlink(input: { id: \"a\" }) { __typename } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertThat(response.toString()).contains("UnlinkResponse");
    }

    @Test
    @DisplayName(
            "GIVEN a nested empty request message WHEN sent with, without and omitting the placeholder SHOULD reach the backend as an empty message")
    void GIVEN_nestedEmptyRequest_WHEN_sentThreeWays_SHOULD_reachBackendWithoutError() throws Exception {
        for (String input : List.of(
                "{ id: \"a\", options: { _placeholder: true } }", "{ id: \"a\", options: {} }", "{ id: \"a\" }")) {
            Struct response = graphql("mutation { unlink(input: " + input + ") { _placeholder } }");

            assertThat(response.containsFields("errors"))
                    .as(input + " -> " + response)
                    .isFalse();
        }
        assertThat(UNLINK_CALLS).hasSize(3);
        assertThat(UNLINK_CALLS.get(0)).contains("\"id\": \"a\"").contains("\"options\": {");
        assertThat(UNLINK_CALLS.get(1)).contains("\"options\": {");
    }

    @Test
    @DisplayName("GIVEN an RPC with an empty request WHEN queried with no arguments SHOULD answer an empty response")
    void GIVEN_emptyRequest_WHEN_queried_SHOULD_answerEmptyResponse() throws Exception {
        Struct response = graphql("{ ping { _placeholder } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertThat(response.getFieldsOrThrow("data").getStructValue().containsFields("ping"))
                .isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ident/id.proto")
                    .setSyntax("proto3")
                    .setPackage("ident")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder().setName("Options"))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("UnlinkRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("options", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, ".ident.Options")))
                    .addMessageType(DescriptorProto.newBuilder().setName("UnlinkResponse"))
                    .addMessageType(DescriptorProto.newBuilder().setName("PingRequest"))
                    .addMessageType(DescriptorProto.newBuilder().setName("PingResponse"))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Identity")
                            .addMethod(method(
                                    "Unlink", ".ident.UnlinkRequest", ".ident.UnlinkResponse", "unlink", Op.MUTATION))
                            .addMethod(method("Ping", ".ident.PingRequest", ".ident.PingResponse", "ping", Op.QUERY)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, String in, String out, String gql, Op op) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(in)
                .setOutputType(out)
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                GraphQlProto.graphql,
                                GraphQlRule.newBuilder()
                                        .setName(gql)
                                        .setType(op)
                                        .build()));
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
            Path path = Files.createTempFile("ident-id", ".pb");
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
        var unlink = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("ident.Identity/Unlink")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(UNLINK_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(UNLINK_RESPONSE))
                .build();
        var ping = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("ident.Identity/Ping")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(PING_REQUEST))
                .setResponseMarshaller(
                        DynamicMessageMarshaller.forDescriptor(FILE.findMessageTypeByName("PingResponse")))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("ident.Identity")
                .addMethod(unlink, ServerCalls.asyncUnaryCall((request, observer) -> {
                    try {
                        UNLINK_CALLS.add(JsonFormat.printer().print(request));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    observer.onNext(DynamicMessage.getDefaultInstance(UNLINK_RESPONSE));
                    observer.onCompleted();
                }))
                .addMethod(ping, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(DynamicMessage.getDefaultInstance(FILE.findMessageTypeByName("PingResponse")));
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
