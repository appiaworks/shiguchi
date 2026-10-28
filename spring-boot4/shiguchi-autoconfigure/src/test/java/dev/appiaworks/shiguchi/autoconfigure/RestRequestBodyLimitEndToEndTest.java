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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * {@code shiguchi.rest.max-request-body} on the Boot 4 gateway: a transcoded POST whose body
 * is over the cap is answered 413 {@code RESOURCE_EXHAUSTED} without the backend seeing it —
 * whether the size is declared up front or only discovered while streaming the body in — and a
 * body under the cap goes through untouched.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rest.max-request-body=1KB"
        })
@DisplayName("Request body cap on the REST surface end to end (Boot 4)")
class RestRequestBodyLimitEndToEndTest {

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
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        BACKEND_CALLS.set(0);
    }

    private record Reply(int status, String body) {}

    private static String json(int textLength) {
        return "{\"text\":\"" + "x".repeat(textLength) + "\"}";
    }

    @Test
    @DisplayName("GIVEN a body under the cap WHEN posted SHOULD reach the backend and answer 200")
    void given_smallBody_when_posted_should_passThrough() {
        Reply reply = client.post()
                .uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json(100))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"text\":\"" + "x".repeat(100) + "\"");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a body over the cap with Content-Length WHEN posted SHOULD answer 413 RESOURCE_EXHAUSTED naming the sizes, without calling the backend")
    void given_declaredOversizeBody_when_posted_should_answer413() {
        Reply reply = client.post()
                .uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json(4000))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.body()).contains("\"code\":\"RESOURCE_EXHAUSTED\"").contains("limit of 1024 bytes");
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a chunked body that grows past the cap while being read WHEN posted SHOULD answer 413 RESOURCE_EXHAUSTED without calling the backend")
    void given_chunkedOversizeBody_when_posted_should_answer413() {
        byte[] chunk = "x".repeat(300).getBytes(StandardCharsets.UTF_8);
        Flux<DataBuffer> body = Flux.concat(
                        Flux.just("{\"text\":\"".getBytes(StandardCharsets.UTF_8)),
                        Flux.range(0, 6).map(i -> chunk),
                        Flux.just("\"}".getBytes(StandardCharsets.UTF_8)))
                .map(bytes -> DefaultDataBufferFactory.sharedInstance.wrap(bytes));

        Reply reply = client.post()
                .uri("/v1/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromDataBuffers(body))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.body()).contains("\"code\":\"RESOURCE_EXHAUSTED\"");
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("bl/notes.proto")
                    .setPackage("bl")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Note")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Notes")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Create")
                                    .setInputType(".bl.Note")
                                    .setOutputType(".bl.Note")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/notes")
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
            Path path = Files.createTempFile("bl-notes", ".pb");
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
        var create = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("bl.Notes/Create")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(NOTE))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("bl.Notes")
                .addMethod(create, ServerCalls.asyncUnaryCall((request, observer) -> {
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
