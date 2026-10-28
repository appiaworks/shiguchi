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
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * A server-streaming RPC on the Boot 3 gateway's REST surface, in front of a real gRPC server:
 * the response is NDJSON, one JSON object per line in stream order, and it is flow-controlled end
 * to end — a caller that stops reading stalls the backend instead of the gateway buffering the
 * stream, and a caller that goes away cancels the backend call.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("Server streaming on the REST surface end to end (Boot 3)")
class RestServerStreamingEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListRequest");

    /** Items the backend has handed to gRPC so far, and whether the caller cancelled. */
    private static final AtomicInteger SENT = new AtomicInteger();

    private static final AtomicBoolean CANCELLED = new AtomicBoolean();

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "catalog");
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
        SENT.set(0);
        CANCELLED.set(false);
    }

    @Test
    @DisplayName(
            "GIVEN a server-streaming route WHEN called over REST SHOULD answer NDJSON with one object per line in order")
    void given_serverStreamingRoute_when_called_should_answerNdjsonInOrder() {
        var response = client.get()
                .uri("/v1/items?count=5&size=8")
                .exchangeToFlux(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    assertThat(r.headers().contentType()).isPresent();
                    assertThat(r.headers().contentType().get().isCompatibleWith(MediaType.APPLICATION_NDJSON))
                            .as("content type %s", r.headers().contentType().get())
                            .isTrue();
                    return r.bodyToFlux(String.class);
                })
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(response).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(response.get(i)).startsWith("{").endsWith("}").contains("\"id\":\"" + i + "\"");
        }
        assertThat(SENT.get()).isEqualTo(5);
    }

    @Test
    @DisplayName(
            "GIVEN a caller that reads three items of a huge stream and leaves WHEN it disconnects SHOULD have stalled the backend and then cancelled it")
    void given_slowCallerThatLeaves_when_disconnected_should_stallThenCancelBackend() {
        int total = 200_000; // ~200 MB of items: no buffer between backend and caller can hide this

        List<String> first = client.get()
                .uri("/v1/items?count=" + total + "&size=1024")
                .retrieve()
                .bodyToFlux(String.class)
                .take(3)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(first).hasSize(3);
        assertThat(first.get(2)).contains("\"id\":\"2\"");
        // take(3) cancelled the HTTP response; the gateway must cancel the gRPC call, and by then
        // the backend can only have pushed what the socket windows absorbed, not the whole stream.
        awaitCancelled();
        assertThat(SENT.get())
                .as("items the backend produced before the cancel reached it")
                .isLessThan(total / 4);
    }

    private static void awaitCancelled() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!CANCELLED.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(CANCELLED.get())
                .as("backend call cancelled after the caller left")
                .isTrue();
    }

    // ---------------------------------------------------------------- the gRPC backend

    /** cat.Catalog/ListItems(ListRequest{count,size}) returns (stream Item{id,payload}) at GET /v1/items. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("cat/catalog.proto")
                    .setSyntax("proto3")
                    .setPackage("cat")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("payload", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32))
                            .addField(field("size", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Catalog")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListItems")
                                    .setInputType(".cat.ListRequest")
                                    .setOutputType(".cat.Item")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
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
            Path path = Files.createTempFile("cat-catalog", ".pb");
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

    /**
     * A well-behaved streaming backend: produces only while gRPC reports the transport ready, so
     * the count of produced items mirrors the demand the gateway forwards from the HTTP caller.
     */
    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> list =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("cat.Catalog/ListItems")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("cat.Catalog")
                .addMethod(list, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int count = (int) request.getField(LIST_REQUEST.findFieldByName("count"));
                    int size = (int) request.getField(LIST_REQUEST.findFieldByName("size"));
                    String payload = "x".repeat(Math.max(size, 1));
                    ServerCallStreamObserver<DynamicMessage> call = (ServerCallStreamObserver<DynamicMessage>) observer;
                    AtomicInteger next = new AtomicInteger();
                    call.setOnCancelHandler(() -> CANCELLED.set(true));
                    Runnable drain = () -> {
                        while (call.isReady() && !call.isCancelled()) {
                            int i = next.getAndIncrement();
                            if (i >= count) {
                                call.onCompleted();
                                return;
                            }
                            call.onNext(DynamicMessage.newBuilder(ITEM)
                                    .setField(ITEM.findFieldByName("id"), Integer.toString(i))
                                    .setField(ITEM.findFieldByName("payload"), payload)
                                    .build());
                            SENT.incrementAndGet();
                        }
                    };
                    call.setOnReadyHandler(drain);
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
