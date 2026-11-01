package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpBodyProto;
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
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Streaming google.api.HttpBody on REST routes (Boot 3): server-streaming downloads and
 * client-streaming uploads of raw bytes with Content-Type. Tests the full flow from HTTP through
 * the transcoding gateway to a gRPC backend.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("Streaming google.api.HttpBody on REST surface end to end (Boot 3)")
class RestStreamingHttpBodyEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor EXPORT_REQUEST = FILE.findMessageTypeByName("ExportRequest");
    private static final Descriptor IMPORT_SUMMARY = FILE.findMessageTypeByName("ImportSummary");
    private static final Descriptor HTTP_BODY = com.google.api.HttpBody.getDescriptor();

    /** Chunks the backend has handed to gRPC for the download under test, if one tracks them. */
    private static final AtomicReference<AtomicInteger> EXPORT_PROGRESS = new AtomicReference<>();

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "files");
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

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName(
            "GIVEN a download that fails before its first chunk WHEN requested SHOULD answer the mapped HTTP error with the JSON error body")
    void given_exportFailsBeforeFirstChunk_when_downloaded_should_answerHttpError() {
        var reply = client.get()
                .uri("/v1/exports/c?chunks=3&failBeforeFirst=true")
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(404);
        assertThat(new String(reply.body(), StandardCharsets.UTF_8))
                .contains("NOT_FOUND")
                .contains("no export");
    }

    @Test
    @DisplayName(
            "GIVEN a download that fails after its first chunk WHEN requested SHOULD abort the transfer rather than end it cleanly")
    void given_exportFailsAfterFirstChunk_when_downloaded_should_abortTransfer() {
        String outcome = client.get()
                .uri("/v1/exports/d?chunks=5&failAfterFirst=true")
                .retrieve()
                .bodyToMono(byte[].class)
                .map(b -> "COMPLETE:" + new String(b, StandardCharsets.UTF_8))
                .onErrorResume(e -> reactor.core.publisher.Mono.just("ABORTED"))
                .block(Duration.ofSeconds(30));

        // A clean end here would hand the caller a truncated file that looks whole.
        assertThat(outcome).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("GIVEN server-streaming export with 3 chunks WHEN downloaded SHOULD return 200 with streamed CSV rows")
    void given_exportWith3Chunks_when_downloaded_should_return200WithStreamedRows() {
        var reply = client.get()
                .uri("/v1/exports/a?chunks=3")
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b)))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contentType()).isNotNull();
        assertThat(reply.contentType().toString()).startsWith("text/csv");
        String body = new String(reply.body(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("row-0\nrow-1\nrow-2\n");
    }

    @Test
    @DisplayName("GIVEN server-streaming export with 2000 chunks WHEN downloaded SHOULD stream all rows through")
    void given_exportWith2000Chunks_when_downloaded_should_streamAllRows() {
        // Each row is "row-<i>\n"; sum the bytes
        int expectedBytes = 0;
        for (int i = 0; i < 2000; i++) {
            expectedBytes += ("row-" + i + "\n").getBytes().length;
        }

        // Track progress so a stall says where it stopped: before the headers, mid-body, or
        // after the last byte without completion.
        var status = new AtomicInteger(-1);
        var received = new AtomicLong();
        var sent = new AtomicInteger();
        EXPORT_PROGRESS.set(sent);
        Reply reply;
        try {
            reply = client.get()
                    .uri("/v1/exports/b?chunks=2000")
                    .exchangeToMono(r -> {
                        status.set(r.statusCode().value());
                        return r.bodyToFlux(DataBuffer.class)
                                .reduce(new ByteArrayOutputStream(), (out, buf) -> {
                                    received.addAndGet(buf.readableByteCount());
                                    byte[] bytes = new byte[buf.readableByteCount()];
                                    buf.read(bytes);
                                    DataBufferUtils.release(buf);
                                    out.writeBytes(bytes);
                                    return out;
                                })
                                .map(out -> new Reply(
                                        r.statusCode().value(),
                                        r.headers().asHttpHeaders().getContentType(),
                                        out.toByteArray()));
                    })
                    .block(Duration.ofSeconds(30));
        } catch (IllegalStateException timeout) {
            throw new AssertionError(
                    "download stalled: status=" + status.get() + ", bytes=" + received.get() + "/" + expectedBytes
                            + ", backend sent " + sent.get() + "/2000 chunks",
                    timeout);
        } finally {
            EXPORT_PROGRESS.set(null);
        }

        assertThat(reply.status()).isEqualTo(200);
        String body = new String(reply.body(), StandardCharsets.UTF_8);
        assertThat(body.length()).isEqualTo(expectedBytes);

        // Spot-check some rows
        assertThat(body).startsWith("row-0\n");
        assertThat(body).contains("row-1000\n");
        assertThat(body).endsWith("row-1999\n");
    }

    @Test
    @DisplayName(
            "GIVEN client-streaming import with 1 MiB data WHEN posted SHOULD return 200 with SHA-256 and byte count")
    void given_import1MiB_when_posted_should_return200WithHashAndBytes() {
        byte[] data = generateRandomBytes(1024 * 1024, 12345);
        String sha256 = sha256Hex(data);

        var reply = client.post()
                .uri("/v1/imports")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromValue(data))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b.getBytes())))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        String body = new String(reply.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"bytes\":\"1048576\"");
        assertThat(body).contains("\"sha256\":\"" + sha256 + "\"");
        assertThat(body).contains("\"contentType\":\"application/octet-stream\"");
        assertThat(body).contains("\"messages\":");
    }

    @Test
    @DisplayName("GIVEN client-streaming import with empty body WHEN posted SHOULD return 200 with bytes=0")
    void given_importEmpty_when_posted_should_return200WithZeroBytes() {
        var reply = client.post()
                .uri("/v1/imports")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromValue(new byte[0]))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b.getBytes())))
                .block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        String body = new String(reply.body(), StandardCharsets.UTF_8);
        // Empty body should still parse successfully
        assertThat(body).contains("\"bytes\":\"0\"");
        assertThat(body).contains("\"messages\":0");
    }

    // ---------------------------------------------------------------- fixtures

    private record Reply(int status, MediaType contentType, byte[] body) {}

    private static byte[] generateRandomBytes(int size, long seed) {
        java.util.Random rand = new java.util.Random(seed);
        byte[] bytes = new byte[size];
        rand.nextBytes(bytes);
        return bytes;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("files.proto")
                    .setPackage("files")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addDependency("google/api/httpbody.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ExportRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("chunks")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("fail_after_first")
                                    .setNumber(3)
                                    .setType(FieldDescriptorProto.Type.TYPE_BOOL)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("fail_before_first")
                                    .setNumber(4)
                                    .setType(FieldDescriptorProto.Type.TYPE_BOOL)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ImportRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("body")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".google.api.HttpBody")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .build())
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ImportSummary")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("bytes")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("messages")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("content_type")
                                    .setNumber(3)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("sha256")
                                    .setNumber(4)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Files")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Export")
                                    .setInputType(".files.ExportRequest")
                                    .setOutputType(".google.api.HttpBody")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/exports/{id}")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Import")
                                    .setInputType(".google.api.HttpBody")
                                    .setOutputType(".files.ImportSummary")
                                    .setClientStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/imports")
                                                            .setBody("*")
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
            Path path = Files.createTempFile("files-streaming", ".pb");
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
        var exportMethod = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("files.Files/Export")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(EXPORT_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(HTTP_BODY))
                .build();

        var importMethod = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.CLIENT_STREAMING)
                .setFullMethodName("files.Files/Import")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(HTTP_BODY))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(IMPORT_SUMMARY))
                .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("files.Files")
                .addMethod(exportMethod, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    boolean failBefore =
                            (boolean) request.getField(EXPORT_REQUEST.findFieldByName("fail_before_first"));
                    boolean failAfter = (boolean) request.getField(EXPORT_REQUEST.findFieldByName("fail_after_first"));
                    int chunks = (int) request.getField(EXPORT_REQUEST.findFieldByName("chunks"));

                    if (failBefore) {
                        observer.onError(
                                Status.NOT_FOUND.withDescription("no export").asException());
                        return;
                    }

                    try {
                        for (int i = 0; i < chunks; i++) {
                            if (failAfter && i == 1) {
                                observer.onError(Status.UNAVAILABLE.asException());
                                return;
                            }
                            DynamicMessage msg = DynamicMessage.newBuilder(HTTP_BODY)
                                    .setField(HTTP_BODY.findFieldByName("content_type"), "text/csv")
                                    .setField(
                                            HTTP_BODY.findFieldByName("data"),
                                            com.google.protobuf.ByteString.copyFrom(
                                                    ("row-" + i + "\n").getBytes(StandardCharsets.UTF_8)))
                                    .build();
                            observer.onNext(msg);
                            AtomicInteger progress = EXPORT_PROGRESS.get();
                            if (progress != null) {
                                progress.incrementAndGet();
                            }
                        }
                        observer.onCompleted();
                    } catch (Exception e) {
                        observer.onError(e);
                    }
                }))
                .addMethod(importMethod, ServerCalls.asyncClientStreamingCall((observer) -> {
                    var uploaded = new java.io.ByteArrayOutputStream();
                    var messageCount = new AtomicInteger(0);
                    var contentType = new java.util.concurrent.atomic.AtomicReference<String>("");
                    var sha256 = new java.util.concurrent.atomic.AtomicReference<String>("");

                    return new StreamObserver<DynamicMessage>() {
                        @Override
                        public void onNext(DynamicMessage request) {
                            try {
                                DynamicMessage httpBody = request;
                                byte[] data = ((com.google.protobuf.ByteString)
                                                httpBody.getField(HTTP_BODY.findFieldByName("data")))
                                        .toByteArray();
                                String ct = (String) httpBody.getField(HTTP_BODY.findFieldByName("content_type"));

                                uploaded.write(data);
                                messageCount.incrementAndGet();
                                if (messageCount.get() == 1) {
                                    contentType.set(ct);
                                }
                            } catch (Exception e) {
                                observer.onError(e);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            observer.onError(t);
                        }

                        @Override
                        public void onCompleted() {
                            try {
                                byte[] allData = uploaded.toByteArray();
                                String hexSha256 = sha256Hex(allData);
                                sha256.set(hexSha256);

                                DynamicMessage reply = DynamicMessage.newBuilder(IMPORT_SUMMARY)
                                        .setField(IMPORT_SUMMARY.findFieldByName("bytes"), (long) allData.length)
                                        .setField(IMPORT_SUMMARY.findFieldByName("messages"), messageCount.get())
                                        .setField(IMPORT_SUMMARY.findFieldByName("content_type"), contentType.get())
                                        .setField(IMPORT_SUMMARY.findFieldByName("sha256"), hexSha256)
                                        .build();
                                observer.onNext(reply);
                                observer.onCompleted();
                            } catch (Exception e) {
                                observer.onError(e);
                            }
                        }
                    };
                }))
                .build();

        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
