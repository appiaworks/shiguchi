package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
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
 * Opt-in request strictness is enforced end to end on the REST surface (Boot 3):
 * reject-conflicting-values prevents different values for the same field across sources, and
 * case-insensitive-enums accepts enum names in any case. The backend only receives requests that
 * pass these checks.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rest.request-validation.reject-conflicting-values=true",
            "shiguchi.rest.request-validation.case-insensitive-enums=true"
        })
@DisplayName("Request strictness on the REST surface end to end (Boot 3)")
class RestRequestStrictnessEndToEndTest {

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
    @DisplayName("GIVEN PATCH /v1/notes/7 with body {\"id\":\"7\",\"text\":\"hi\"} "
            + "WHEN sent SHOULD answer 200 and backend called once")
    void given_patchWithMatchingId_when_sent_should_reachBackend() {
        Reply reply = client.patch()
                .uri("/v1/notes/7")
                .bodyValue("{\"id\":\"7\",\"text\":\"hi\"}")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"id\":\"7\"");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN PATCH /v1/notes/7 with body {\"id\":\"9\",\"text\":\"hi\"} "
            + "WHEN sent SHOULD answer 400 with BINDING_CONFLICT and backend NOT called")
    void given_patchWithConflictingId_when_sent_should_rejectWithBindingConflict() {
        Reply reply = client.patch()
                .uri("/v1/notes/7")
                .bodyValue("{\"id\":\"9\",\"text\":\"hi\"}")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
        assertThat(reply.body()).contains("BINDING_CONFLICT");
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("GIVEN PATCH /v1/notes/7 with body {\"text\":\"hi\",\"mood\":\"happy\"} "
            + "WHEN sent SHOULD answer 200 with normalized mood \"HAPPY\"")
    void given_patchWithLowercaseEnum_when_sent_should_normalizeAndReachBackend() {
        Reply reply = client.patch()
                .uri("/v1/notes/7")
                .bodyValue("{\"text\":\"hi\",\"mood\":\"happy\"}")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"mood\":\"HAPPY\"");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN PATCH /v1/notes/7?mood=Happy with body {\"text\":\"hi\"} "
            + "WHEN sent SHOULD answer 200 with normalized mood \"HAPPY\" from query param")
    void given_patchWithMixedCaseEnumInQuery_when_sent_should_normalizeAndReachBackend() {
        Reply reply = client.patch()
                .uri("/v1/notes/7?mood=Happy")
                .bodyValue("{\"text\":\"hi\"}")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"mood\":\"HAPPY\"");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            EnumDescriptorProto mood = EnumDescriptorProto.newBuilder()
                    .setName("Mood")
                    .addValue(EnumValueDescriptorProto.newBuilder()
                            .setName("MOOD_UNSPECIFIED")
                            .setNumber(0)
                            .build())
                    .addValue(EnumValueDescriptorProto.newBuilder()
                            .setName("HAPPY")
                            .setNumber(1)
                            .build())
                    .build();

            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rs/notes.proto")
                    .setPackage("rs")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addEnumType(mood)
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Note")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .build())
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .build())
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("mood")
                                    .setNumber(3)
                                    .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                                    .setTypeName(".rs.Mood")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .build())
                            .build())
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Notes")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Update")
                                    .setInputType(".rs.Note")
                                    .setOutputType(".rs.Note")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPatch("/v1/notes/{id}")
                                                            .setBody("*")
                                                            .build()))
                                    .build())
                            .build())
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("rs-notes", ".pb");
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
        var update = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rs.Notes/Update")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("rs.Notes")
                .addMethod(update, ServerCalls.asyncUnaryCall((request, observer) -> {
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
