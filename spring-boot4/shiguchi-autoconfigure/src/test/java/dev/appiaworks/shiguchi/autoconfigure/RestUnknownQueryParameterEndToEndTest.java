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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unknown query parameters are rejected with HTTP 400 {@code INVALID_ARGUMENT} on the REST
 * surface end to end (Boot 4): a GET with unknown params is answered before the backend sees it,
 * parameters on the ignored list never trigger rejection, and gateway params (paging, sorting,
 * field projection) are always accepted.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rest.request-validation.reject-unknown-query-parameters=true",
            "shiguchi.rest.request-validation.ignored-query-parameters=access_token"
        })
@DisplayName("Unknown query parameter rejection on the REST surface end to end (Boot 4)")
class RestUnknownQueryParameterEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor NOTE = FILE.findMessageTypeByName("Note");
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "notes");
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
    void setup() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        BACKEND_CALLS.set(0);
    }

    private record Reply(int status, String body) {}

    @Test
    @DisplayName("GIVEN a GET with known query parameter WHEN sent SHOULD reach backend and answer 200")
    void given_knownQueryParam_when_sent_should_passThrough() {
        Reply reply = client.get()
                .uri("/v1/notes/7?text=hi")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"text\":\"hi\"");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a GET with unknown query parameters WHEN sent SHOULD answer 400 with INVALID_ARGUMENT and parameter names")
    void given_unknownQueryParams_when_sent_should_answer400() {
        Reply reply = client.get()
                .uri("/v1/notes/7?txet=hi&bogus=1")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
        assertThat(reply.body()).contains("UNKNOWN_QUERY_PARAMETER");
        assertThat(reply.body()).contains("txet");
        assertThat(reply.body()).contains("bogus");
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("GIVEN a GET with ignored query parameter access_token WHEN sent SHOULD pass through to backend")
    void given_ignoredQueryParam_when_sent_should_passThrough() {
        Reply reply = client.get()
                .uri("/v1/notes/7?text=hi&access_token=abc")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"text\":\"hi\"");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a GET with gateway param 'fields' WHEN sent SHOULD pass through to backend")
    void given_gatewayParam_when_sent_should_passThrough() {
        Reply reply = client.get()
                .uri("/v1/notes/7?fields=text")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("uq/notes.proto")
                    .setPackage("uq")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Note")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .build())
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .build())
                            .build())
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Notes")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Get")
                                    .setInputType(".uq.Note")
                                    .setOutputType(".uq.Note")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/notes/{id}")
                                                            .build())))
                            .build())
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("uq-notes", ".pb");
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
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("uq.Notes/Get")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("uq.Notes")
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
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
