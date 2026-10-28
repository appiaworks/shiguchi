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
import io.grpc.stub.StreamObserver;
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
import reactor.core.publisher.Flux;

/**
 * A client-streaming RPC on the Boot 4 gateway's REST surface, in front of a real gRPC server:
 * the caller posts NDJSON, one JSON message per line, and gets the backend's single response.
 *
 * <p>The upload is never aggregated by the gateway, so the interesting cases are the streaming
 * ones: a body far larger than {@code rest.max-request-body} still goes through, and a malformed
 * line stops the upload at that line instead of reaching the backend.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            // Deliberately small: the body as a whole must not be bound by it, only one line.
            "shiguchi.rest.max-request-body=64KB"
        })
@DisplayName("Client streaming on the REST surface end to end (Boot 4)")
class RestClientStreamingEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor POINT = FILE.findMessageTypeByName("Point");
    private static final Descriptor SUMMARY = FILE.findMessageTypeByName("Summary");

    /** Messages the backend actually received on the call. */
    private static final AtomicInteger RECEIVED = new AtomicInteger();

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "routes");
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
        client = WebClient.builder()
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                // The upload is unbounded; the default 256 KB in-memory limit is about responses,
                // but raise it anyway so nothing in the client masks what the gateway does.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        RECEIVED.set(0);
    }

    private record Reply(int status, String body) {}

    private Reply post(Flux<String> lines) {
        return client.post()
                .uri("/v1/routes:record")
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(lines, String.class)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(60));
    }

    private static String point(int x, int y) {
        return "{\"x\":" + x + ",\"y\":" + y + "}\n";
    }

    @Test
    @DisplayName(
            "GIVEN a client-streaming route WHEN NDJSON lines are posted SHOULD send one message per line and answer the single response")
    void given_clientStreamingRoute_when_posted_should_sendOneMessagePerLine() {
        Reply reply = post(Flux.range(1, 4).map(i -> point(i, i * 2)));

        assertThat(reply.status()).isEqualTo(200);
        // 1+2+3+4 = 10 on x, doubled on y.
        assertThat(reply.body())
                .contains("\"count\":4")
                // proto3 JSON renders 64-bit ints as strings.
                .contains("\"sumX\":\"10\"")
                .contains("\"sumY\":\"20\"");
        assertThat(RECEIVED.get()).isEqualTo(4);
    }

    @Test
    @DisplayName(
            "GIVEN a body far larger than max-request-body WHEN streamed SHOULD go through, because the cap bounds one line and not the upload")
    void given_uploadLargerThanBodyCap_when_streamed_should_succeed() {
        // 64 KB cap, ~2 MB of lines: aggregating this would be a 413.
        int lines = 40_000;
        Reply reply = post(Flux.range(1, lines).map(i -> point(1, 1)));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"count\":" + lines);
        assertThat(RECEIVED.get()).isEqualTo(lines);
    }

    @Test
    @DisplayName(
            "GIVEN a malformed line mid-upload WHEN it is reached SHOULD fail the call and stop sending, leaving the later lines unsent")
    void given_malformedLine_when_reached_should_failAndStop() {
        Flux<String> lines = Flux.concat(
                Flux.just(point(1, 1), point(2, 2)),
                Flux.just("{not json}\n"),
                Flux.range(1, 50).map(i -> point(9, 9)));

        Reply reply = post(lines);

        assertThat(reply.status()).isEqualTo(400);
        // The two good lines may or may not have reached the backend before the cancel, but the
        // fifty after the bad one must not have.
        assertThat(RECEIVED.get()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN an empty body WHEN posted SHOULD complete the stream with no messages and still answer")
    void given_emptyBody_when_posted_should_answerWithZeroCount() {
        Reply reply = post(Flux.empty());

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"count\":0");
        assertThat(RECEIVED.get()).isZero();
    }

    @Test
    @DisplayName("GIVEN lines without a trailing newline WHEN posted SHOULD still send the last one")
    void given_noTrailingNewline_when_posted_should_sendLastLine() {
        Reply reply = post(Flux.just("{\"x\":1,\"y\":1}\n{\"x\":2,\"y\":2}"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"count\":2").contains("\"sumX\":\"3\"");
        assertThat(RECEIVED.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rt/routes.proto")
                    .setPackage("rt")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Point")
                            .addField(field("x", 1, FieldDescriptorProto.Type.TYPE_INT32))
                            .addField(field("y", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Summary")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32))
                            .addField(field("sum_x", 2, FieldDescriptorProto.Type.TYPE_INT64))
                            .addField(field("sum_y", 3, FieldDescriptorProto.Type.TYPE_INT64)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("RouteRecorder")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("RecordRoute")
                                    .setInputType(".rt.Point")
                                    .setOutputType(".rt.Summary")
                                    .setClientStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/routes:record")
                                                            .setBody("*")
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
            Path path = Files.createTempFile("rt-routes", ".pb");
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

    /** Sums the points it is sent and answers once, the shape of a client-streaming RPC. */
    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> record =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.CLIENT_STREAMING)
                        .setFullMethodName("rt.RouteRecorder/RecordRoute")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(POINT))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(SUMMARY))
                        .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("rt.RouteRecorder")
                .addMethod(record, ServerCalls.asyncClientStreamingCall(responseObserver -> new StreamObserver<>() {
                    private int count;
                    private long sumX;
                    private long sumY;

                    @Override
                    public void onNext(DynamicMessage point) {
                        RECEIVED.incrementAndGet();
                        count++;
                        sumX += ((Number) point.getField(POINT.findFieldByName("x"))).longValue();
                        sumY += ((Number) point.getField(POINT.findFieldByName("y"))).longValue();
                    }

                    @Override
                    public void onError(Throwable t) {
                        // The caller gave up (a cancelled upload); nothing to answer.
                    }

                    @Override
                    public void onCompleted() {
                        responseObserver.onNext(DynamicMessage.newBuilder(SUMMARY)
                                .setField(SUMMARY.findFieldByName("count"), count)
                                .setField(SUMMARY.findFieldByName("sum_x"), sumX)
                                .setField(SUMMARY.findFieldByName("sum_y"), sumY)
                                .build());
                        responseObserver.onCompleted();
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
