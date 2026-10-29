package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpBodyProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.ByteString;
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
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@code shiguchi.rest.max-response-body} on the Boot 4 gateway: a transcoded unary response
 * over the cap is answered 502 {@code RESPONSE_TOO_LARGE} without relaying it, and a unary or
 * streaming response under the cap goes through untouched. For server-streaming, a too-large
 * message is signaled in-band as a trailing NDJSON error line once any messages were already sent.
 * The cap limits each message of a server-streaming response, not the stream's running total.
 * The cap also applies to raw {@link com.google.api.HttpBody} unary responses (over → HTTP 502),
 * server-streaming {@link com.google.api.HttpBody} downloads (over → 502 or connection abort),
 * and gRPC-Web responses (over → trailers frame with {@code grpc-status:8}).
 * Per-service {@code max-inbound-message-size} overrides the channel's gRPC message limit and
 * also answers 502 for responses over that limit.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.grpc-web.enabled=true",
            "shiguchi.rest.max-response-body=1KB"
        })
@DisplayName("Response body cap on the REST surface end to end (Boot 4)")
class RestResponseSizeLimitEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor SIZE_REQUEST = FILE.findMessageTypeByName("SizeRequest");
    private static final Descriptor BLOB = FILE.findMessageTypeByName("Blob");
    private static final Descriptor HTTP_BODY = HttpBodyProto.getDescriptor().findMessageTypeByName("HttpBody");
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        // Service 1: "capped" — subject to the REST max-response-body cap (1 KB)
        registry.add("shiguchi.services[0].name", () -> "capped");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[0].include-methods", () -> "rs2.Capped/*");
        // Service 2: "tiny" — subject to per-service max-inbound-message-size (256 bytes)
        registry.add("shiguchi.services[1].name", () -> "tiny");
        registry.add("shiguchi.services[1].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[1].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[1].host", () -> "localhost");
        registry.add("shiguchi.services[1].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[1].include-methods", () -> "rs2.Tiny/*");
        registry.add("shiguchi.services[1].max-inbound-message-size", () -> 256);
        // Service 3: "tiny" again under /retry, with gRPC retries on and RESOURCE_EXHAUSTED retryable
        // (the default list): a too-large response must not be retried.
        registry.add("shiguchi.services[2].name", () -> "tiny-retry");
        registry.add("shiguchi.services[2].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[2].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[2].host", () -> "localhost");
        registry.add("shiguchi.services[2].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[2].include-methods", () -> "rs2.Tiny/*");
        registry.add("shiguchi.services[2].path-prefix", () -> "/retry");
        registry.add("shiguchi.services[2].max-inbound-message-size", () -> 256);
        registry.add("shiguchi.services[2].retry.enabled", () -> true);
        registry.add("shiguchi.services[2].retry.max-attempts", () -> 3);
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

    @Test
    @DisplayName("GIVEN a unary response under the cap WHEN called SHOULD reach the backend and answer 200")
    void given_smallUnaryResponse_when_called_should_answer200() {
        Reply reply = client.get()
                .uri("/v1/capped/blob?size=100")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"text\":\"" + "x".repeat(100) + "\"");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a unary response over the REST cap WHEN called SHOULD answer 502 RESPONSE_TOO_LARGE naming the limit, without calling the backend twice")
    void given_largeUnaryResponse_when_called_should_answer502() {
        Reply reply = client.get()
                .uri("/v1/capped/blob?size=2000")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).contains("RESPONSE_TOO_LARGE").contains("exceeds the limit of 1024 bytes");
    }

    @Test
    @DisplayName(
            "GIVEN retries on with RESOURCE_EXHAUSTED retryable WHEN a unary response exceeds max-inbound-message-size SHOULD answer 502 after calling the backend once")
    void given_retriesOn_when_responseExceedsInboundLimit_should_notRetry() {
        Reply reply = client.get()
                .uri("/retry/v1/tiny/blob?size=500")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).contains("RESPONSE_TOO_LARGE");
        // The response headers arrived before the oversized message, which commits the call:
        // gRPC retries only attempts that have not received headers, so a too-large response
        // (a deterministic failure) is never repeated.
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a unary response that exceeds per-service max-inbound-message-size WHEN called SHOULD answer 502 RESPONSE_TOO_LARGE (not 429)")
    void given_unaryResponseExceedsPerServiceLimit_when_called_should_answer502() {
        // size=500: exceeds tiny service's 256-byte limit, so gRPC message rejection becomes RESPONSE_TOO_LARGE (502)
        Reply reply = client.get()
                .uri("/v1/tiny/blob?size=500")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).contains("RESPONSE_TOO_LARGE");
    }

    @Test
    @DisplayName(
            "GIVEN a stream whose total passes the cap but no message does WHEN called SHOULD answer 200 with every item and no error line")
    void given_streamTotalExceedsCapButNoMessageDoes_when_called_should_answerWithAllItems() {
        // size=300 x count=5: 1500 bytes in total against a 1 KB cap, every message ~300
        var response = client.get()
                .uri("/v1/capped/stream?size=300&count=5&Accept=application%2Fx-ndjson")
                .exchangeToFlux(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToFlux(String.class);
                })
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(response).hasSize(5);
        for (String line : response) {
            assertThat(line).contains("\"text\"").doesNotContain("\"error\"");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a stream whose later message is over the cap WHEN called SHOULD answer 200 with the earlier items, then a trailing RESPONSE_TOO_LARGE error line")
    void given_streamMessageOverCapMidStream_when_called_should_answerWithErrorLine() {
        // size=-300 x count=3: two 300-byte messages, then a 2000-byte one
        var response = client.get()
                .uri("/v1/capped/stream?size=-300&count=3&Accept=application%2Fx-ndjson")
                .exchangeToFlux(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToFlux(String.class);
                })
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(response).hasSize(3);
        assertThat(response.get(0)).contains("\"text\"");
        assertThat(response.get(1)).contains("\"text\"");
        assertThat(response.get(2)).contains("\"error\"").contains("RESPONSE_TOO_LARGE");
    }

    @Test
    @DisplayName(
            "GIVEN a streaming response where the first item exceeds the cap WHEN called SHOULD answer 502 with error body (not NDJSON)")
    void given_streamingResponseFirstItemExceedsCap_when_called_should_answer502() {
        // size=2000 × count=1: first item alone is over 1 KB, so 502 before opening the stream
        Reply reply = client.get()
                .uri("/v1/capped/stream?size=2000&count=1")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).contains("RESPONSE_TOO_LARGE");
    }

    @Test
    @DisplayName(
            "GIVEN a streaming response fully under the cap WHEN called SHOULD answer 200 with all items as NDJSON, no error line")
    void given_streamingResponseUnderCap_when_called_should_answerWithAllItems() {
        // size=100 × count=3: all items fit within 1 KB
        var response = client.get()
                .uri("/v1/capped/stream?size=100&count=3")
                .exchangeToFlux(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToFlux(String.class);
                })
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(response).hasSize(3);
        for (String line : response) {
            assertThat(line).contains("\"text\"");
            assertThat(line).doesNotContain("\"error\"");
        }
    }

    @Test
    @DisplayName("GIVEN a raw HttpBody unary response under the cap WHEN called SHOULD answer 200 with text/plain body")
    void given_rawUnaryResponseUnderCap_when_called_should_answer200() {
        Reply reply = client.get()
                .uri("/v1/capped/raw?size=100")
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(r.statusCode().value(), new String(b))))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo("x".repeat(100));
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a raw HttpBody unary response over the cap WHEN called SHOULD answer 502 RESPONSE_TOO_LARGE")
    void given_rawUnaryResponseOverCap_when_called_should_answer502() {
        Reply reply = client.get()
                .uri("/v1/capped/raw?size=2000")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).contains("RESPONSE_TOO_LARGE").contains("exceeds the limit of 1024 bytes");
    }

    @Test
    @DisplayName("GIVEN a streaming HttpBody download under the cap WHEN called SHOULD answer 200 with all bytes")
    void given_downloadResponseUnderCap_when_called_should_answer200() {
        Reply reply = client.get()
                .uri("/v1/capped/raw-stream?size=100&count=3")
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(r.statusCode().value(), new String(b))))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isEqualTo("x".repeat(300));
    }

    @Test
    @DisplayName(
            "GIVEN a streaming HttpBody download where first chunk exceeds cap WHEN called SHOULD answer 502 RESPONSE_TOO_LARGE")
    void given_downloadResponseFirstChunkOverCap_when_called_should_answer502() {
        Reply reply = client.get()
                .uri("/v1/capped/raw-stream?size=2000&count=1")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).contains("RESPONSE_TOO_LARGE");
    }

    @Test
    @DisplayName("GIVEN a streaming HttpBody download crossing cap mid-stream WHEN called SHOULD abort the connection")
    void given_downloadResponseCrossesCap_when_called_should_abort() {
        var result = client.get()
                .uri("/v1/capped/raw-stream?size=400&count=5")
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> "COMPLETE")
                        .onErrorResume(e -> reactor.core.publisher.Mono.just("ABORTED")))
                .block(Duration.ofSeconds(30));

        assertThat(result).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web unary call under the cap WHEN called SHOULD answer 200 with data frame and trailers with grpc-status:0")
    void given_grpcWebUnaryUnderCap_when_called_should_answerWithDataAndTrailers() {
        DynamicMessage request = DynamicMessage.newBuilder(SIZE_REQUEST)
                .setField(SIZE_REQUEST.findFieldByName("size"), 100)
                .build();
        byte[] payload = request.toByteArray();
        byte[] frame = dataFrame(payload);

        var response = client.post()
                .uri("/rs2.Capped/Get")
                .contentType(MediaType.valueOf("application/grpc-web+proto"))
                .bodyValue(frame)
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(r.statusCode().value(), new String(b, StandardCharsets.ISO_8859_1))))
                .block(Duration.ofSeconds(30));

        assertThat(response.status()).isEqualTo(200);
        FrameParser parser = new FrameParser(response.body().getBytes(StandardCharsets.ISO_8859_1));
        Frame dataFrameResp = parser.nextFrame();
        assertThat(dataFrameResp).isNotNull();
        assertThat(dataFrameResp.flag).isEqualTo((byte) 0x00);
        Frame trailersFrame = parser.nextFrame();
        assertThat(trailersFrame).isNotNull();
        assertThat(trailersFrame.flag).isEqualTo((byte) 0x80);
        String trailers = new String(trailersFrame.payload, StandardCharsets.ISO_8859_1);
        assertThat(trailers).contains("grpc-status:0");
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web unary call over the cap WHEN called SHOULD answer 200 with trailers containing grpc-status:8")
    void given_grpcWebUnaryOverCap_when_called_should_answerWithErrorTrailers() {
        DynamicMessage request = DynamicMessage.newBuilder(SIZE_REQUEST)
                .setField(SIZE_REQUEST.findFieldByName("size"), 2000)
                .build();
        byte[] payload = request.toByteArray();
        byte[] frame = dataFrame(payload);

        var response = client.post()
                .uri("/rs2.Capped/Get")
                .contentType(MediaType.valueOf("application/grpc-web+proto"))
                .bodyValue(frame)
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(r.statusCode().value(), new String(b, StandardCharsets.ISO_8859_1))))
                .block(Duration.ofSeconds(30));

        assertThat(response.status()).isEqualTo(200);
        FrameParser parser = new FrameParser(response.body().getBytes(StandardCharsets.ISO_8859_1));
        Frame firstFrame = parser.nextFrame();
        assertThat(firstFrame).isNotNull();
        // The oversized message is never relayed: the only frame is the trailers frame.
        assertThat(firstFrame.flag).isEqualTo((byte) 0x80);
        String trailers = new String(firstFrame.payload, StandardCharsets.ISO_8859_1);
        assertThat(trailers)
                .contains("grpc-status:8")
                .contains("grpc-status-details-bin")
                .contains("exceeds the limit of 1024 bytes");
        assertThat(parser.nextFrame()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web stream whose total passes the cap but no message does WHEN called SHOULD answer every data frame, then trailers with grpc-status:0")
    void given_grpcWebStreamTotalExceedsCapButNoMessageDoes_when_called_should_completeNormally() {
        var frames = grpcWebStream(300, 5);

        assertThat(frames).hasSize(6);
        assertThat(frames.subList(0, 5)).allSatisfy(f -> assertThat(f.flag).isEqualTo((byte) 0x00));
        Frame last = frames.get(5);
        assertThat(last.flag).isEqualTo((byte) 0x80);
        assertThat(new String(last.payload, StandardCharsets.ISO_8859_1)).contains("grpc-status:0");
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web stream with one message over the cap WHEN called SHOULD answer the earlier data frames, then trailers with grpc-status:8")
    void given_grpcWebStreamMessageOverCap_when_called_should_answerWithTrailersError() {
        var frames = grpcWebStream(-300, 3);

        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).flag).isEqualTo((byte) 0x00);
        assertThat(frames.get(1).flag).isEqualTo((byte) 0x00);
        Frame last = frames.get(2);
        assertThat(last.flag).isEqualTo((byte) 0x80);
        String trailers = new String(last.payload, StandardCharsets.ISO_8859_1);
        assertThat(trailers).contains("grpc-status:8").contains("exceeds the limit of 1024 bytes");
    }

    private java.util.List<Frame> grpcWebStream(int size, int count) {
        DynamicMessage request = DynamicMessage.newBuilder(SIZE_REQUEST)
                .setField(SIZE_REQUEST.findFieldByName("size"), size)
                .setField(SIZE_REQUEST.findFieldByName("count"), count)
                .build();
        var response = client.post()
                .uri("/rs2.Capped/Stream")
                .contentType(MediaType.valueOf("application/grpc-web+proto"))
                .bodyValue(dataFrame(request.toByteArray()))
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(r.statusCode().value(), new String(b, StandardCharsets.ISO_8859_1))))
                .block(Duration.ofSeconds(30));
        assertThat(response.status()).isEqualTo(200);
        FrameParser parser = new FrameParser(response.body().getBytes(StandardCharsets.ISO_8859_1));
        var frames = new java.util.ArrayList<Frame>();
        Frame f;
        while ((f = parser.nextFrame()) != null) frames.add(f);
        return frames;
    }

    // ------------------------------------------------------------------ fixtures

    private static byte[] dataFrame(byte[] message) {
        // gRPC-Web frame: flag (1 byte) + length (4 bytes) + payload
        byte[] out = new byte[5 + message.length];
        out[0] = 0x00; // DATA flag
        out[1] = (byte) (message.length >>> 24);
        out[2] = (byte) (message.length >>> 16);
        out[3] = (byte) (message.length >>> 8);
        out[4] = (byte) message.length;
        System.arraycopy(message, 0, out, 5, message.length);
        return out;
    }

    static class Frame {
        byte flag;
        byte[] payload;

        Frame(byte flag, byte[] payload) {
            this.flag = flag;
            this.payload = payload;
        }
    }

    static class FrameParser {
        private final byte[] data;
        private int pos = 0;

        FrameParser(byte[] data) {
            this.data = data;
        }

        Frame nextFrame() {
            if (pos + 5 > data.length) return null;
            byte flag = data[pos++];
            long length = ((data[pos++] & 0xFFL) << 24)
                    | ((data[pos++] & 0xFFL) << 16)
                    | ((data[pos++] & 0xFFL) << 8)
                    | (data[pos++] & 0xFFL);
            if (pos + length > data.length) return null;
            byte[] payload = new byte[(int) length];
            System.arraycopy(data, pos, payload, 0, (int) length);
            pos += (int) length;
            return new Frame(flag, payload);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rs2/blobs.proto")
                    .setPackage("rs2")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addDependency("google/api/httpbody.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("SizeRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("size")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("count")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Blob")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    // Service rs2.Capped: Get, Stream, GetRaw, ExportRaw
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Capped")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Get")
                                    .setInputType(".rs2.SizeRequest")
                                    .setOutputType(".rs2.Blob")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/capped/blob")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Stream")
                                    .setInputType(".rs2.SizeRequest")
                                    .setOutputType(".rs2.Blob")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/capped/stream")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetRaw")
                                    .setInputType(".rs2.SizeRequest")
                                    .setOutputType(".google.api.HttpBody")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/capped/raw")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ExportRaw")
                                    .setInputType(".rs2.SizeRequest")
                                    .setOutputType(".google.api.HttpBody")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/capped/raw-stream")
                                                            .build()))))
                    // Service rs2.Tiny: Get, Stream (same methods)
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Tiny")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Get")
                                    .setInputType(".rs2.SizeRequest")
                                    .setOutputType(".rs2.Blob")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/tiny/blob")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Stream")
                                    .setInputType(".rs2.SizeRequest")
                                    .setOutputType(".rs2.Blob")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/tiny/stream")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(
                    proto, new FileDescriptor[] {AnnotationsProto.getDescriptor(), HttpBodyProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("rs2-blobs", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(com.google.protobuf.AnyProto.getDescriptor().toProto())
                    .addFile(HttpBodyProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        // Build method descriptors for both services
        var cappedGet = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rs2.Capped/Get")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SIZE_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BLOB))
                .build();
        var cappedStream = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("rs2.Capped/Stream")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SIZE_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BLOB))
                .build();
        var cappedGetRaw = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rs2.Capped/GetRaw")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SIZE_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(HTTP_BODY))
                .build();
        var cappedExportRaw = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("rs2.Capped/ExportRaw")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SIZE_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(HTTP_BODY))
                .build();
        var tinyGet = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rs2.Tiny/Get")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SIZE_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BLOB))
                .build();
        var tinyStream = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("rs2.Tiny/Stream")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SIZE_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BLOB))
                .build();

        ServerServiceDefinition cappedService = ServerServiceDefinition.builder("rs2.Capped")
                .addMethod(cappedGet, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    int size = (int) request.getField(SIZE_REQUEST.findFieldByName("size"));
                    observer.onNext(DynamicMessage.newBuilder(BLOB)
                            .setField(BLOB.findFieldByName("text"), "x".repeat(Math.max(size, 1)))
                            .build());
                    observer.onCompleted();
                }))
                .addMethod(cappedStream, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int size = (int) request.getField(SIZE_REQUEST.findFieldByName("size"));
                    int count = (int) request.getField(SIZE_REQUEST.findFieldByName("count"));
                    // A negative size means |size|-byte messages with an oversized (2000-byte) last one.
                    String payload = "x".repeat(Math.max(Math.abs(size), 1));
                    for (int i = 0; i < count; i++) {
                        String text = size < 0 && i == count - 1 ? "x".repeat(2000) : payload;
                        observer.onNext(DynamicMessage.newBuilder(BLOB)
                                .setField(BLOB.findFieldByName("text"), text)
                                .build());
                    }
                    observer.onCompleted();
                }))
                .addMethod(cappedGetRaw, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    int size = (int) request.getField(SIZE_REQUEST.findFieldByName("size"));
                    byte[] bytes = "x".repeat(Math.max(size, 1)).getBytes(StandardCharsets.UTF_8);
                    observer.onNext(DynamicMessage.newBuilder(HTTP_BODY)
                            .setField(HTTP_BODY.findFieldByName("content_type"), "text/plain")
                            .setField(HTTP_BODY.findFieldByName("data"), ByteString.copyFrom(bytes))
                            .build());
                    observer.onCompleted();
                }))
                .addMethod(cappedExportRaw, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int size = (int) request.getField(SIZE_REQUEST.findFieldByName("size"));
                    int count = (int) request.getField(SIZE_REQUEST.findFieldByName("count"));
                    byte[] bytes = "x".repeat(Math.max(size, 1)).getBytes(StandardCharsets.UTF_8);
                    for (int i = 0; i < count; i++) {
                        observer.onNext(DynamicMessage.newBuilder(HTTP_BODY)
                                .setField(HTTP_BODY.findFieldByName("content_type"), "text/plain")
                                .setField(HTTP_BODY.findFieldByName("data"), ByteString.copyFrom(bytes))
                                .build());
                    }
                    observer.onCompleted();
                }))
                .build();

        ServerServiceDefinition tinyService = ServerServiceDefinition.builder("rs2.Tiny")
                .addMethod(tinyGet, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    int size = (int) request.getField(SIZE_REQUEST.findFieldByName("size"));
                    observer.onNext(DynamicMessage.newBuilder(BLOB)
                            .setField(BLOB.findFieldByName("text"), "x".repeat(Math.max(size, 1)))
                            .build());
                    observer.onCompleted();
                }))
                .addMethod(tinyStream, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int size = (int) request.getField(SIZE_REQUEST.findFieldByName("size"));
                    int count = (int) request.getField(SIZE_REQUEST.findFieldByName("count"));
                    String payload = "x".repeat(Math.max(size, 1));
                    for (int i = 0; i < count; i++) {
                        observer.onNext(DynamicMessage.newBuilder(BLOB)
                                .setField(BLOB.findFieldByName("text"), payload)
                                .build());
                    }
                    observer.onCompleted();
                }))
                .build();

        try {
            return ServerBuilder.forPort(0)
                    .addService(cappedService)
                    .addService(tinyService)
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
