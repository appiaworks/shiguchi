package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * REST auto-mapping end-to-end test on Boot 4: a method without a {@code google.api.http} rule
 * gets a POST route at the gRPC path when {@code shiguchi.rest.auto-mapping} is enabled,
 * unless the service-level override disables it; a method WITH a rule never gets the auto route
 * even when auto-mapping is on.
 *
 * <p>The test configures two services against the same backend:
 * <ul>
 * <li>{@code auto}: service-level auto-mapping enabled (inherits global setting)
 * <li>{@code manual}: service-level auto-mapping explicitly disabled
 * </ul>
 *
 * <p>The proto defines two separate services ({@code Notes} and {@code Memos}) to avoid duplicate
 * service name issues when both are registered on the same backend. Each service has:
 * <ul>
 * <li>{@code Echo}: unannotated method (gets auto route only if auto-mapping is on)
 * <li>{@code Create}: method with {@code google.api.http POST /v1/} rule (never gets auto route)
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rest.auto-mapping=true"
        })
@DisplayName("REST auto-mapping on Boot 4 gateway: enabled globally, disabled per service")
class RestAutoMappingEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor NOTE = FILE.findMessageTypeByName("Note");
    private static final Descriptor MEMO = FILE.findMessageTypeByName("Memo");
    private static final AtomicInteger NOTES_ECHO_CALLS = new AtomicInteger();
    private static final AtomicInteger NOTES_CREATE_CALLS = new AtomicInteger();
    private static final AtomicInteger MEMOS_ECHO_CALLS = new AtomicInteger();
    private static final AtomicInteger MEMOS_CREATE_CALLS = new AtomicInteger();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // Service 0: "auto" service over Notes, inherits global auto-mapping=true
        registry.add("shiguchi.services[0].name", () -> "auto");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[0].path-prefix", () -> "/a");
        registry.add("shiguchi.services[0].include-methods[0]", () -> "am.Notes/*");

        // Service 1: "manual" service over Memos, explicitly disables auto-mapping
        registry.add("shiguchi.services[1].name", () -> "manual");
        registry.add("shiguchi.services[1].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[1].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[1].host", () -> "localhost");
        registry.add("shiguchi.services[1].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[1].path-prefix", () -> "/m");
        registry.add("shiguchi.services[1].include-methods[0]", () -> "am.Memos/*");
        registry.add("shiguchi.services[1].auto-mapping", () -> "false");
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
        NOTES_ECHO_CALLS.set(0);
        NOTES_CREATE_CALLS.set(0);
        MEMOS_ECHO_CALLS.set(0);
        MEMOS_CREATE_CALLS.set(0);
    }

    private record Reply(int status, String body) {}

    private static String json(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    // ===== Service "auto" (Notes, auto-mapping enabled) =====

    @Test
    @DisplayName(
            "GIVEN auto-mapping enabled WHEN posting to the unannotated Echo method SHOULD reach the backend with 200")
    void given_autoMappingEnabled_when_echoAutoRoute_should_reachBackend() {
        Reply reply = client.post()
                .uri("/a/am.Notes/Echo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json("hello"))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"text\":\"hello\"");
        assertThat(NOTES_ECHO_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN an annotated method WHEN posting to it via its explicit route SHOULD work even with auto-mapping on")
    void given_annotatedCreate_when_explicitRoute_should_reachBackend() {
        Reply reply = client.post()
                .uri("/a/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json("explicit"))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"text\":\"explicit\"");
        assertThat(NOTES_CREATE_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN an annotated method WHEN posting to its auto-mapped path SHOULD answer 404, not call backend")
    void given_annotatedCreate_when_autoRoute_should_answer404() {
        Reply reply = client.post()
                .uri("/a/am.Notes/Create")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json("should-fail"))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(404);
        assertThat(NOTES_CREATE_CALLS.get()).isZero();
    }

    // ===== Service "manual" (Memos, auto-mapping disabled) =====

    @Test
    @DisplayName(
            "GIVEN auto-mapping disabled on a service WHEN posting to an unannotated method SHOULD answer 404, not call backend")
    void given_autoMappingDisabled_when_echoAutoRoute_should_answer404() {
        Reply reply = client.post()
                .uri("/m/am.Memos/Echo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json("should-fail"))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(404);
        assertThat(MEMOS_ECHO_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("GIVEN auto-mapping disabled WHEN posting via an annotated method's route SHOULD work normally")
    void given_autoMappingDisabled_when_explicitRoute_should_reachBackend() {
        Reply reply = client.post()
                .uri("/m/v1/memos")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json("memo-text"))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"text\":\"memo-text\"");
        assertThat(MEMOS_CREATE_CALLS.get()).isEqualTo(1);
    }

    // ================================================================ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("am/notes.proto")
                    .setPackage("am")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    // Message Note for Notes service
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Note")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    // Message Memo for Memos service
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Memo")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    // Notes service: Echo (unannotated) and Create (annotated)
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Notes")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Echo")
                                    .setInputType(".am.Note")
                                    .setOutputType(".am.Note"))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Create")
                                    .setInputType(".am.Note")
                                    .setOutputType(".am.Note")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/notes")
                                                            .setBody("*")
                                                            .build()))))
                    // Memos service: Echo (unannotated) and Create (annotated)
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Memos")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Echo")
                                    .setInputType(".am.Memo")
                                    .setOutputType(".am.Memo"))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Create")
                                    .setInputType(".am.Memo")
                                    .setOutputType(".am.Memo")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/memos")
                                                            .setBody("*")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("am-notes-memos", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        var notesEcho = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("am.Notes/Echo")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .build();
        var notesCreate = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("am.Notes/Create")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .build();
        var notesService = ServerServiceDefinition.builder("am.Notes")
                .addMethod(notesEcho, ServerCalls.asyncUnaryCall((request, observer) -> {
                    NOTES_ECHO_CALLS.incrementAndGet();
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .addMethod(notesCreate, ServerCalls.asyncUnaryCall((request, observer) -> {
                    NOTES_CREATE_CALLS.incrementAndGet();
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .build();

        var memosEcho = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("am.Memos/Echo")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(MEMO))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(MEMO))
                .build();
        var memosCreate = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("am.Memos/Create")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(MEMO))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(MEMO))
                .build();
        var memosService = ServerServiceDefinition.builder("am.Memos")
                .addMethod(memosEcho, ServerCalls.asyncUnaryCall((request, observer) -> {
                    MEMOS_ECHO_CALLS.incrementAndGet();
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .addMethod(memosCreate, ServerCalls.asyncUnaryCall((request, observer) -> {
                    MEMOS_CREATE_CALLS.incrementAndGet();
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .build();

        try {
            return ServerBuilder.forPort(0)
                    .addService(notesService)
                    .addService(memosService)
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
