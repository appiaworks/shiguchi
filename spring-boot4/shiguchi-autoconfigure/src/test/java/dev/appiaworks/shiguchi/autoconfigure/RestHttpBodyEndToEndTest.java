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
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * google.api.HttpBody on unary REST routes (Boot 4): request/response bodies are raw bytes with
 * Content-Type, not JSON. Tests the full flow from HTTP upload/download through the transcoding
 * gateway to a gRPC backend.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("google.api.HttpBody on REST surface end to end (Boot 4)")
class RestHttpBodyEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final byte[] FILE_BYTES =
            new byte[] {(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xD8, 0, (byte) 0x80};
    private static final String CSV_DATA = "a,b\n1,2\n";

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor DOWNLOAD_REQUEST = FILE.findMessageTypeByName("GetFileRequest");
    private static final Descriptor UPLOAD_REQUEST = FILE.findMessageTypeByName("UploadRequest");
    private static final Descriptor UPLOAD_REPLY = FILE.findMessageTypeByName("UploadReply");
    private static final Descriptor WRAPPED = FILE.findMessageTypeByName("Wrapped");
    private static final Descriptor HTTP_BODY = com.google.api.HttpBody.getDescriptor();

    private static final AtomicReference<byte[]> RECEIVED_BYTES = new AtomicReference<>();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "hb");
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
        RECEIVED_BYTES.set(null);
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName(
            "GIVEN a typed binary response (image/png) WHEN downloaded SHOULD return 200 with exact bytes and Content-Type")
    void given_typedBinaryResponse_when_downloaded_should_return200WithBytesAndContentType() {
        var reply = client.get()
                .uri("/v1/files/7")
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contentType()).isNotNull();
        assertThat(reply.contentType().toString()).startsWith("image/png");
        assertThat(reply.body()).isEqualTo(FILE_BYTES);
    }

    @Test
    @DisplayName(
            "GIVEN untyped binary response (no content_type) WHEN downloaded SHOULD return 200 with application/octet-stream")
    void given_untypedBinaryResponse_when_downloaded_should_return200WithOctetStream() {
        var reply = client.get()
                .uri("/v1/files/7/raw")
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b)))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contentType()).isNotNull();
        assertThat(reply.contentType().toString()).isEqualTo("application/octet-stream");
        assertThat(reply.body()).isEqualTo(FILE_BYTES);
    }

    @Test
    @DisplayName(
            "GIVEN binary upload with image/png Content-Type WHEN posted SHOULD store bytes exactly, return 200 with echoed metadata")
    void given_binaryUpload_when_posted_should_storeExactBytesAndReturn200() {
        var reply = client.put()
                .uri("/v1/files/7")
                .contentType(MediaType.IMAGE_PNG)
                .body(BodyInserters.fromValue(FILE_BYTES))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b.getBytes())))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).isNotEmpty();
        String body = new String(reply.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"id\":\"7\"");
        assertThat(body).contains("\"size\":" + FILE_BYTES.length);
        assertThat(body).contains("\"contentType\":\"image/png\"");

        // Verify backend received exact bytes
        assertThat(RECEIVED_BYTES.get()).isEqualTo(FILE_BYTES);
    }

    @Test
    @DisplayName("GIVEN response_body wrapping HttpBody WHEN downloaded SHOULD unwrap and return CSV as text/csv")
    void given_wrappedHttpBody_when_downloaded_should_unwrapAndReturnCsv() {
        var reply = client.get()
                .uri("/v1/files/7/wrapped")
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getContentType(),
                                b.getBytes())))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contentType()).isNotNull();
        assertThat(reply.contentType().toString()).startsWith("text/csv");
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo(CSV_DATA);
    }

    // ---------------------------------------------------------------- fixtures

    private record Reply(int status, MediaType contentType, byte[] body) {}

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("hb/files.proto")
                    .setPackage("hb")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addDependency("google/api/httpbody.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetFileRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("UploadRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("file")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".google.api.HttpBody")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("UploadReply")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("size")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("content_type")
                                    .setNumber(3)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Wrapped")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("name")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("content")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".google.api.HttpBody")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Files")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Download")
                                    .setInputType(".hb.GetFileRequest")
                                    .setOutputType(".google.api.HttpBody")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/files/{id}")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("DownloadUntyped")
                                    .setInputType(".hb.GetFileRequest")
                                    .setOutputType(".google.api.HttpBody")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/files/{id}/raw")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Upload")
                                    .setInputType(".hb.UploadRequest")
                                    .setOutputType(".hb.UploadReply")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPut("/v1/files/{id}")
                                                            .setBody("file")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("DownloadWrapped")
                                    .setInputType(".hb.GetFileRequest")
                                    .setOutputType(".hb.Wrapped")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/files/{id}/wrapped")
                                                            .setResponseBody("content")
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
            Path path = Files.createTempFile("hb-files", ".pb");
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
        var download = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("hb.Files/Download")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(DOWNLOAD_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(HTTP_BODY))
                .build();
        var downloadUntyped = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("hb.Files/DownloadUntyped")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(DOWNLOAD_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(HTTP_BODY))
                .build();
        var upload = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("hb.Files/Upload")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(UPLOAD_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(UPLOAD_REPLY))
                .build();
        var downloadWrapped = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("hb.Files/DownloadWrapped")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(DOWNLOAD_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(WRAPPED))
                .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("hb.Files")
                .addMethod(download, ServerCalls.asyncUnaryCall((request, observer) -> {
                    DynamicMessage response = DynamicMessage.newBuilder(HTTP_BODY)
                            .setField(HTTP_BODY.findFieldByName("content_type"), "image/png")
                            .setField(
                                    HTTP_BODY.findFieldByName("data"),
                                    com.google.protobuf.ByteString.copyFrom(FILE_BYTES))
                            .build();
                    observer.onNext(response);
                    observer.onCompleted();
                }))
                .addMethod(downloadUntyped, ServerCalls.asyncUnaryCall((request, observer) -> {
                    DynamicMessage response = DynamicMessage.newBuilder(HTTP_BODY)
                            .setField(HTTP_BODY.findFieldByName("content_type"), "")
                            .setField(
                                    HTTP_BODY.findFieldByName("data"),
                                    com.google.protobuf.ByteString.copyFrom(FILE_BYTES))
                            .build();
                    observer.onNext(response);
                    observer.onCompleted();
                }))
                .addMethod(upload, ServerCalls.asyncUnaryCall((request, observer) -> {
                    com.google.protobuf.DynamicMessage fileMsg =
                            (DynamicMessage) request.getField(UPLOAD_REQUEST.findFieldByName("file"));
                    byte[] fileData = ((com.google.protobuf.ByteString)
                                    fileMsg.getField(HTTP_BODY.findFieldByName("data")))
                            .toByteArray();
                    RECEIVED_BYTES.set(fileData);
                    String contentType = (String) fileMsg.getField(HTTP_BODY.findFieldByName("content_type"));

                    DynamicMessage response = DynamicMessage.newBuilder(UPLOAD_REPLY)
                            .setField(UPLOAD_REPLY.findFieldByName("id"), (String)
                                    request.getField(UPLOAD_REQUEST.findFieldByName("id")))
                            .setField(UPLOAD_REPLY.findFieldByName("size"), fileData.length)
                            .setField(UPLOAD_REPLY.findFieldByName("content_type"), contentType)
                            .build();
                    observer.onNext(response);
                    observer.onCompleted();
                }))
                .addMethod(downloadWrapped, ServerCalls.asyncUnaryCall((request, observer) -> {
                    DynamicMessage content = DynamicMessage.newBuilder(HTTP_BODY)
                            .setField(HTTP_BODY.findFieldByName("content_type"), "text/csv")
                            .setField(
                                    HTTP_BODY.findFieldByName("data"),
                                    com.google.protobuf.ByteString.copyFrom(CSV_DATA.getBytes(StandardCharsets.UTF_8)))
                            .build();
                    DynamicMessage response = DynamicMessage.newBuilder(WRAPPED)
                            .setField(WRAPPED.findFieldByName("name"), "x")
                            .setField(WRAPPED.findFieldByName("content"), content)
                            .build();
                    observer.onNext(response);
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
