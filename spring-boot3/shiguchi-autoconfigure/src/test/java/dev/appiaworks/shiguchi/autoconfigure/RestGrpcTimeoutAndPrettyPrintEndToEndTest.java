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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Two REST options through the whole Boot 3 gateway against a real gRPC server: a
 * {@code grpc-timeout} request header becomes the backend call's deadline, and
 * {@code json-print.pretty-print} indents a service's JSON responses.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("grpc-timeout header and pretty-printed JSON end to end (Boot 3)")
class RestGrpcTimeoutAndPrettyPrintEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    /** How long the backend's Slow method takes to answer. */
    private static final long SLOW_MILLIS = 1500;

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor MSG = FILE.findMessageTypeByName("Msg");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "timer");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        // The same backend again under /pretty, printing indented JSON.
        registry.add("shiguchi.services[1].name", () -> "timer-pretty");
        registry.add("shiguchi.services[1].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[1].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[1].host", () -> "localhost");
        registry.add("shiguchi.services[1].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[1].path-prefix", () -> "/pretty");
        registry.add("shiguchi.services[1].json-print.pretty-print", () -> "true");
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN grpc-timeout: 200m WHEN the backend takes longer SHOULD answer 504 DEADLINE_EXCEEDED without waiting for it")
    void given_shortGrpcTimeout_when_backendSlow_should_answerDeadlineExceeded() {
        long start = System.nanoTime();

        client.get()
                .uri("/v1/slow")
                .header("grpc-timeout", "200m")
                .exchange()
                .expectStatus()
                .isEqualTo(504)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("DEADLINE_EXCEEDED");

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis())
                .isLessThan(SLOW_MILLIS);
    }

    @Test
    @DisplayName("GIVEN no grpc-timeout WHEN the backend is slow SHOULD wait for its answer")
    void given_noGrpcTimeout_when_backendSlow_should_waitForAnswer() {
        client.get().uri("/v1/slow").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("GIVEN a malformed grpc-timeout WHEN called SHOULD ignore it and answer normally")
    void given_malformedGrpcTimeout_when_called_should_ignoreIt() {
        client.get()
                .uri("/v1/slow")
                .header("grpc-timeout", "soon")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("GIVEN json-print.pretty-print on a service WHEN it answers SHOULD send indented JSON")
    void given_prettyPrintService_when_called_should_sendIndentedJson() {
        String body = client.get()
                .uri("/pretty/v1/echo/hi")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\n").contains("\"text\" : \"hi\"");
    }

    @Test
    @DisplayName("GIVEN the default print options WHEN a service answers SHOULD send compact JSON")
    void given_defaultPrintOptions_when_called_should_sendCompactJson() {
        byte[] body = client.get()
                .uri("/v1/echo/hi")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();

        assertThat(new String(body, StandardCharsets.UTF_8))
                .doesNotContain("\n")
                .contains("\"text\":\"hi\"");
    }

    // ---------------------------------------------------------------- the gRPC backend

    /** lib.Timer: Echo (GET /v1/echo/{text}) answers at once, Slow (GET /v1/slow) after SLOW_MILLIS. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/timer.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Msg")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Timer")
                            .addMethod(method("Echo", HttpRule.newBuilder().setGet("/v1/echo/{text}")))
                            .addMethod(method("Slow", HttpRule.newBuilder().setGet("/v1/slow"))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, HttpRule.Builder rule) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib.Msg")
                .setOutputType(".lib.Msg")
                .setOptions(MethodOptions.newBuilder().setExtension(AnnotationsProto.http, rule.build()));
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-timer", ".pb");
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

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> descriptor(String name) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Timer/" + name)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .build();
    }

    private static Server startBackend() {
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Timer")
                .addMethod(descriptor("Echo"), ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .addMethod(descriptor("Slow"), ServerCalls.asyncUnaryCall((request, observer) -> {
                    try {
                        Thread.sleep(SLOW_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    observer.onNext(request);
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
