package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.google.protobuf.InvalidProtocolBufferException;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.MethodRule;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
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
import reactor.core.publisher.Mono;

/**
 * End-to-end tests for gRPC-Web on the Boot 4 gateway: unary, server-streaming, and error cases
 * over a real gRPC backend.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.grpc-web.enabled=true",
            "shiguchi.access-log.enabled=true"
        })
@DisplayName("gRPC-Web end to end (Boot 4)")
class GrpcWebEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /**
         * Gateway-side validation, as the protovalidate starter would install it: an id of
         * "invalid" is refused with a google.rpc.BadRequest, anything else passes.
         */
        @org.springframework.context.annotation.Bean
        dev.appiaworks.shiguchi.validation.RequestValidationHook idValidator() {
            return (request, method) -> {
                var idField = request.getDescriptorForType().findFieldByName("id");
                if (idField != null && "invalid".equals(request.getField(idField))) {
                    var badRequest = com.google.rpc.BadRequest.newBuilder()
                            .addFieldViolations(com.google.rpc.BadRequest.FieldViolation.newBuilder()
                                    .setField("id")
                                    .setDescription("id must not be 'invalid'"));
                    throw io.grpc.protobuf.StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                            .setCode(io.grpc.Status.Code.INVALID_ARGUMENT.value())
                            .setMessage("id: must not be 'invalid'")
                            .addDetails(com.google.protobuf.Any.pack(badRequest.build()))
                            .build());
                }
            };
        }
    }

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor BOOK = FILE.findMessageTypeByName("Book");
    private static final Descriptor GET_BOOK_REQUEST = FILE.findMessageTypeByName("GetBookRequest");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListRequest");

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "web");
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

    @Test
    @DisplayName(
            "GIVEN the access log enabled WHEN a gRPC-Web call completes SHOULD log it with its route and protocol")
    void given_accessLogEnabled_when_grpcWebCallCompletes_should_logIt() {
        var context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var accessLog = context.getLogger("dev.appiaworks.shiguchi.access");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        accessLog.addAppender(appender);
        try {
            DynamicMessage request = DynamicMessage.newBuilder(GET_BOOK_REQUEST)
                    .setField(GET_BOOK_REQUEST.findFieldByName("id"), "7")
                    .build();
            client.post()
                    .uri("/web.Library/GetBook")
                    .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                    .bodyValue(dataFrame(request.toByteArray()))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(30));

            // The line is written as the exchange terminates, just after the body has gone out.
            long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("proto=grpc-web"))
                    && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(appender.list)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anySatisfy(line -> assertThat(line)
                            .contains("POST /web.Library/GetBook route=web.Library/GetBook status=200 grpc=OK")
                            .endsWith("proto=grpc-web"));
        } finally {
            accessLog.detachAppender(appender);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a unary gRPC-Web call with binary frame WHEN sent SHOULD answer 200 with binary data frame and trailers")
    void given_unaryBinaryCall_when_sent_should_answerWithBinaryFrame() {
        DynamicMessage request = DynamicMessage.newBuilder(GET_BOOK_REQUEST)
                .setField(GET_BOOK_REQUEST.findFieldByName("id"), "42")
                .build();
        byte[] frameBody = dataFrame(request.toByteArray());

        byte[] response = client.post()
                .uri("/web.Library/GetBook")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(frameBody)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();
        // Parse frames from response
        FrameParser parser = new FrameParser(response);
        Frame dataFrame = parser.nextFrame();
        assertThat(dataFrame).isNotNull();
        assertThat(dataFrame.flag).isEqualTo((byte) 0x00); // data frame
        try {
            DynamicMessage book = DynamicMessage.parseFrom(BOOK, dataFrame.payload);
            assertThat(book.getField(BOOK.findFieldByName("title"))).isEqualTo("T-42");
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("Failed to parse book message", e);
        }

        Frame trailerFrame = parser.nextFrame();
        assertThat(trailerFrame).isNotNull();
        assertThat(trailerFrame.flag).isEqualTo((byte) 0x80); // trailers
        String trailers = new String(trailerFrame.payload, StandardCharsets.US_ASCII);
        assertThat(trailers).contains("grpc-status:0");
    }

    @Test
    @DisplayName(
            "GIVEN a unary gRPC-Web call with text-encoded frame WHEN sent SHOULD answer 200 with text data frame and trailers")
    void given_unaryTextCall_when_sent_should_answerWithTextFrame() {
        DynamicMessage request = DynamicMessage.newBuilder(GET_BOOK_REQUEST)
                .setField(GET_BOOK_REQUEST.findFieldByName("id"), "99")
                .build();
        byte[] frameBody = dataFrame(request.toByteArray());
        byte[] textBody = textFrame(frameBody);

        var entity = client.post()
                .uri("/web.Library/GetBook")
                .contentType(MediaType.parseMediaType("application/grpc-web-text+proto"))
                .bodyValue(textBody)
                .retrieve()
                .toEntity(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(entity).isNotNull();
        assertThat(entity.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("application/grpc-web-text+proto"));
        byte[] response = entity.getBody();
        assertThat(response).isNotNull();
        // Response is base64-encoded; decode it
        byte[] decoded = decodeBase64Response(response);
        FrameParser parser = new FrameParser(decoded);
        Frame dataFrame = parser.nextFrame();
        assertThat(dataFrame).isNotNull();
        try {
            DynamicMessage book = DynamicMessage.parseFrom(BOOK, dataFrame.payload);
            assertThat(book.getField(BOOK.findFieldByName("title"))).isEqualTo("T-99");
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("Failed to parse book message", e);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a server-streaming gRPC-Web call WHEN sent SHOULD answer 200 with multiple data frames and trailers")
    void given_serverStreamingCall_when_sent_should_answerWithMultipleFrames() {
        DynamicMessage request = DynamicMessage.newBuilder(LIST_REQUEST)
                .setField(LIST_REQUEST.findFieldByName("count"), 3)
                .build();
        byte[] frameBody = dataFrame(request.toByteArray());

        byte[] response = client.post()
                .uri("/web.Library/ListBooks")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(frameBody)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();
        FrameParser parser = new FrameParser(response);

        // Three data frames
        for (int i = 0; i < 3; i++) {
            Frame frame = parser.nextFrame();
            assertThat(frame).isNotNull();
            assertThat(frame.flag).isEqualTo((byte) 0x00);
            try {
                DynamicMessage book = DynamicMessage.parseFrom(BOOK, frame.payload);
                assertThat(book.getField(BOOK.findFieldByName("id"))).isEqualTo(String.valueOf(i));
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError("Failed to parse book message", e);
            }
        }

        // Trailers
        Frame trailerFrame = parser.nextFrame();
        assertThat(trailerFrame).isNotNull();
        assertThat(trailerFrame.flag).isEqualTo((byte) 0x80);
        String trailers = new String(trailerFrame.payload, StandardCharsets.US_ASCII);
        assertThat(trailers).contains("grpc-status:0");
    }

    @Test
    @DisplayName(
            "GIVEN a unary call with unknown book id WHEN sent SHOULD answer 200 with no data frame and error trailers")
    void given_notFoundBook_when_sent_should_answerWithErrorTrailers() {
        DynamicMessage request = DynamicMessage.newBuilder(GET_BOOK_REQUEST)
                .setField(GET_BOOK_REQUEST.findFieldByName("id"), "missing")
                .build();
        byte[] frameBody = dataFrame(request.toByteArray());

        byte[] response = client.post()
                .uri("/web.Library/GetBook")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(frameBody)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();
        FrameParser parser = new FrameParser(response);

        // Only trailers, no data frame
        Frame frame = parser.nextFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.flag).isEqualTo((byte) 0x80); // trailers
        String trailers = new String(frame.payload, StandardCharsets.US_ASCII);
        assertThat(trailers).contains("grpc-status:5"); // NOT_FOUND
        assertThat(trailers).contains("grpc-message:no book missing");
    }

    @Test
    @DisplayName("GIVEN a method with auth_scopes, no HTTP rule and GraphQL off WHEN an anonymous caller calls it "
            + "over gRPC-Web SHOULD answer PERMISSION_DENIED trailers")
    void GIVEN_scopedMethodWithoutRoute_WHEN_anonymousGrpcWebCall_SHOULD_answerPermissionDenied() {
        DynamicMessage request = DynamicMessage.newBuilder(GET_BOOK_REQUEST)
                .setField(GET_BOOK_REQUEST.findFieldByName("id"), "42")
                .build();

        byte[] response = client.post()
                .uri("/web.Library/DeleteBook")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(dataFrame(request.toByteArray()))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();
        Frame frame = new FrameParser(response).nextFrame();
        assertThat(frame).isNotNull();
        assertThat(frame.flag).isEqualTo((byte) 0x80); // trailers only: the backend was never called
        String trailers = new String(frame.payload, StandardCharsets.US_ASCII);
        assertThat(trailers).contains("grpc-status:7"); // PERMISSION_DENIED
    }

    @Test
    @DisplayName("GIVEN a client-streaming call WHEN sent SHOULD answer 200 with UNIMPLEMENTED status trailers")
    void given_clientStreamingCall_when_sent_should_answerWithUnimplementedStatus() {
        DynamicMessage request = DynamicMessage.newBuilder(BOOK)
                .setField(BOOK.findFieldByName("id"), "1")
                .setField(BOOK.findFieldByName("title"), "Test")
                .build();
        byte[] frameBody = dataFrame(request.toByteArray());

        byte[] response = client.post()
                .uri("/web.Library/Upload")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(frameBody)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();
        FrameParser parser = new FrameParser(response);
        Frame trailerFrame = parser.nextFrame();
        assertThat(trailerFrame).isNotNull();
        assertThat(trailerFrame.flag).isEqualTo((byte) 0x80);
        String trailers = new String(trailerFrame.payload, StandardCharsets.US_ASCII);
        assertThat(trailers).contains("grpc-status:12"); // UNIMPLEMENTED
    }

    @Test
    @DisplayName(
            "GIVEN gateway-side validation WHEN a gRPC-Web request violates it SHOULD answer trailers-only INVALID_ARGUMENT with the BadRequest, backend not called")
    void given_validationHook_when_requestInvalid_should_answerInvalidArgumentWithDetails() throws Exception {
        DynamicMessage request = DynamicMessage.newBuilder(GET_BOOK_REQUEST)
                .setField(GET_BOOK_REQUEST.findFieldByName("id"), "invalid")
                .build();

        var response = client.post()
                .uri("/web.Library/GetBook")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(dataFrame(request.toByteArray()))
                .retrieve()
                .toEntity(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(response.getHeaders().getFirst("grpc-status")).isEqualTo("3");
        FrameParser parser = new FrameParser(response.getBody());
        Frame only = parser.nextFrame();
        assertThat(only.flag).isEqualTo((byte) 0x80); // trailers, no data frame: the backend never answered
        String trailers = new String(only.payload, StandardCharsets.US_ASCII);
        assertThat(trailers).contains("grpc-status:3");
        String details = trailers.lines()
                .filter(l -> l.startsWith("grpc-status-details-bin:"))
                .findFirst()
                .orElseThrow()
                .substring("grpc-status-details-bin:".length())
                .trim();
        var status =
                com.google.rpc.Status.parseFrom(java.util.Base64.getDecoder().decode(details));
        assertThat(status.getDetails(0)
                        .unpack(com.google.rpc.BadRequest.class)
                        .getFieldViolations(0)
                        .getField())
                .isEqualTo("id");
    }

    @Test
    @DisplayName("GIVEN gateway-side validation WHEN a gRPC-Web request passes it SHOULD reach the backend unchanged")
    void given_validationHook_when_requestValid_should_reachBackend() throws Exception {
        DynamicMessage request = DynamicMessage.newBuilder(GET_BOOK_REQUEST)
                .setField(GET_BOOK_REQUEST.findFieldByName("id"), "9")
                .build();

        byte[] response = client.post()
                .uri("/web.Library/GetBook")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(dataFrame(request.toByteArray()))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));

        Frame data = new FrameParser(response).nextFrame();
        assertThat(data.flag).isEqualTo((byte) 0x00);
        assertThat(DynamicMessage.parseFrom(BOOK, data.payload).getField(BOOK.findFieldByName("title")))
                .isEqualTo("T-9");
    }

    @Test
    @DisplayName("GIVEN a call to an unknown method WHEN sent SHOULD answer 200 with UNIMPLEMENTED status")
    void given_unknownMethod_when_sent_should_answerWithUnimplemented() {
        byte[] response = client.post()
                .uri("/web.Library/Nope")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(new byte[0])
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();
        FrameParser parser = new FrameParser(response);
        Frame trailerFrame = parser.nextFrame();
        assertThat(trailerFrame).isNotNull();
        String trailers = new String(trailerFrame.payload, StandardCharsets.US_ASCII);
        assertThat(trailers).contains("grpc-status:12"); // UNIMPLEMENTED
    }

    @Test
    @DisplayName("GIVEN a plain JSON POST to a gRPC-Web path WHEN sent SHOULD NOT be handled as gRPC-Web")
    void given_jsonContentType_when_sent_should_notHandleAsGrpcWeb() {
        // A JSON POST should not be treated as gRPC-Web and should fall through to normal routing
        // This will likely result in 404 or other non-200 status
        int status = client.post()
                .uri("/web.Library/GetBook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchangeToMono(response -> {
                    // Don't throw on non-2xx; just return the status code
                    return Mono.just(response.statusCode().value());
                })
                .block(Duration.ofSeconds(30));

        // Should not be 200 (gRPC-Web always returns 200 for successful frame parsing)
        assertThat(status).isNotEqualTo(200);
    }

    // ---------------------------------------------------------------- helpers

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

    private static byte[] textFrame(byte[] frame) {
        // Base64 encode the frame for -text variant
        return Base64.getEncoder().encode(frame);
    }

    private byte[] decodeBase64Response(byte[] response) {
        // Response is base64 chunks; decode by splitting on padding boundaries
        String encoded = new String(response, StandardCharsets.US_ASCII);
        return Base64.getDecoder().decode(encoded.replaceAll("\\s+", ""));
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

    // ---------------------------------------------------------------- the gRPC backend

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("web/library.proto")
                    .setSyntax("proto3")
                    .setPackage("web")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("title", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetBookRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Library")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetBook")
                                    .setInputType(".web.GetBookRequest")
                                    .setOutputType(".web.Book"))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListBooks")
                                    .setInputType(".web.ListRequest")
                                    .setOutputType(".web.Book")
                                    .setServerStreaming(true))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("DeleteBook")
                                    .setInputType(".web.GetBookRequest")
                                    .setOutputType(".web.Book")
                                    // Scoped, but no google.api.http rule: no REST route, and
                                    // GraphQL is off, so only gRPC-Web reaches it.
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    MethodProto.method,
                                                    MethodRule.newBuilder()
                                                            .addAuthScopes("books:write")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Upload")
                                    .setInputType(".web.Book")
                                    .setOutputType(".web.Book")
                                    .setClientStreaming(true)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
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
            Path path = Files.createTempFile("web-library", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> getBook =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("web.Library/GetBook")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_BOOK_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BOOK))
                        .build();
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> listBooks =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("web.Library/ListBooks")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BOOK))
                        .build();
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> upload =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.CLIENT_STREAMING)
                        .setFullMethodName("web.Library/Upload")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(BOOK))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BOOK))
                        .build();

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> deleteBook =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("web.Library/DeleteBook")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_BOOK_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BOOK))
                        .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("web.Library")
                .addMethod(deleteBook, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(DynamicMessage.newBuilder(BOOK)
                            .setField(BOOK.findFieldByName("id"), "deleted")
                            .build());
                    observer.onCompleted();
                }))
                .addMethod(getBook, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String id = (String) request.getField(GET_BOOK_REQUEST.findFieldByName("id"));
                    if ("missing".equals(id)) {
                        observer.onError(Status.NOT_FOUND
                                .withDescription("no book missing")
                                .asRuntimeException());
                    } else {
                        DynamicMessage book = DynamicMessage.newBuilder(BOOK)
                                .setField(BOOK.findFieldByName("id"), id)
                                .setField(BOOK.findFieldByName("title"), "T-" + id)
                                .build();
                        observer.onNext(book);
                        observer.onCompleted();
                    }
                }))
                .addMethod(listBooks, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int count = (int) request.getField(LIST_REQUEST.findFieldByName("count"));
                    for (int i = 0; i < count; i++) {
                        DynamicMessage book = DynamicMessage.newBuilder(BOOK)
                                .setField(BOOK.findFieldByName("id"), String.valueOf(i))
                                .setField(BOOK.findFieldByName("title"), "Book-" + i)
                                .build();
                        observer.onNext(book);
                    }
                    observer.onCompleted();
                }))
                .addMethod(upload, ServerCalls.asyncClientStreamingCall(observer -> {
                    observer.onError(Status.UNIMPLEMENTED.asRuntimeException());
                    return new io.grpc.stub.StreamObserver<DynamicMessage>() {
                        @Override
                        public void onNext(DynamicMessage value) {}

                        @Override
                        public void onError(Throwable t) {}

                        @Override
                        public void onCompleted() {}
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
