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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@code response_body} for JSON callers end to end (Boot 4): when a transcoded unary RPC route
 * specifies a {@code response_body}, only the named field is returned in the response body to a
 * JSON caller. Routes without {@code response_body} return the entire response message. The field
 * type (repeated, scalar, etc.) is preserved in the JSON encoding.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("response_body for JSON callers end to end (Boot 4)")
class RestResponseBodyEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor LIST_BOOKS_REQUEST = FILE.findMessageTypeByName("ListBooksRequest");
    private static final Descriptor LIST_BOOKS_RESPONSE = FILE.findMessageTypeByName("ListBooksResponse");
    private static final Descriptor BOOK = FILE.findMessageTypeByName("Book");
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "rb");
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

    private record Reply(int status, String body, HttpHeaders headers) {}

    @Test
    @DisplayName(
            "GIVEN a route with response_body=\"books\" (a repeated field) WHEN querying via JSON SHOULD return only the books array as the root")
    void given_responseBodyIsRepeatedBooks_when_queriedAsJson_should_returnBooksArrayRoot() {
        Reply reply = doGet("/v1/shelves/s1/books");

        assertThat(reply.status()).isEqualTo(200);
        String body = reply.body().trim();
        assertThat(body).startsWith("[");
        assertThat(body).contains("\"id\":\"1\"");
        assertThat(body).contains("\"title\":\"Dune\"");
        assertThat(body).contains("\"id\":\"2\"");
        assertThat(body).contains("\"title\":\"Emma\"");
        assertThat(body).doesNotContain("\"nextPageToken\"");
        assertThat(body).doesNotContain("\"total\"");
        assertThat(body).doesNotContain("\"books\"");
    }

    @Test
    @DisplayName(
            "GIVEN a route with response_body=\"total\" (an int64 scalar) WHEN querying via JSON SHOULD return the scalar as a JSON string")
    void given_responseBodyIsScalarTotal_when_queriedAsJson_should_returnScalarString() {
        Reply reply = doGet("/v1/shelves/s1/count");

        assertThat(reply.status()).isEqualTo(200);
        String body = reply.body().trim();
        // proto3 int64 is encoded as a JSON string
        assertThat(body).isEqualTo("\"2\"");
    }

    @Test
    @DisplayName("GIVEN a route without response_body WHEN querying via JSON SHOULD return the entire message")
    void given_noResponseBody_when_queriedAsJson_should_returnEntireMessage() {
        Reply reply = doGet("/v1/shelves/s1/full");

        assertThat(reply.status()).isEqualTo(200);
        String body = reply.body().trim();
        assertThat(body).contains("\"books\"");
        assertThat(body).contains("\"nextPageToken\":\"abc\"");
        assertThat(body).contains("\"total\":\"2\"");
    }

    @Test
    @DisplayName(
            "GIVEN a route with response_body=\"books\" where books is empty WHEN querying via JSON SHOULD return an empty array")
    void given_responseBodyBooksEmpty_when_queriedAsJson_should_returnEmptyArray() {
        Reply reply = doGet("/v1/shelves/s1/empty");

        assertThat(reply.status()).isEqualTo(200);
        String body = reply.body().trim();
        assertThat(body).isEqualTo("[]");
    }

    @Test
    @DisplayName(
            "GIVEN a route with response_body and explicit Accept: application/json header WHEN querying SHOULD return the field as JSON with correct Content-Type")
    void given_responseBodyWithJsonAcceptHeader_when_queried_should_returnJsonWithCorrectContentType() {
        Reply reply = client.get()
                .uri("http://localhost:" + environment.getRequiredProperty("local.server.port")
                        + "/v1/shelves/s1/books")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b ->
                                new Reply(r.statusCode().value(), b, r.headers().asHttpHeaders())))
                .block(Duration.ofSeconds(10));

        assertThat(reply.status()).isEqualTo(200);
        String body = reply.body().trim();
        assertThat(body).startsWith("[");
        assertThat(body).contains("\"Dune\"");
        assertThat(body).contains("\"Emma\"");
        String contentType = reply.headers().getContentType().toString();
        assertThat(contentType).contains("application/json");
    }

    // ------------------------------------------------------------------ fixtures

    private Reply doGet(String path) {
        return client.get()
                .uri("http://localhost:" + environment.getRequiredProperty("local.server.port") + path)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b ->
                                new Reply(r.statusCode().value(), b, r.headers().asHttpHeaders())))
                .block(Duration.ofSeconds(10));
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rb/books.proto")
                    .setPackage("rb")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("title")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListBooksRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("shelf")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListBooksResponse")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("books")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".rb.Book")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("next_page_token")
                                    .setNumber(2)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("total")
                                    .setNumber(3)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Books")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListBooks")
                                    .setInputType(".rb.ListBooksRequest")
                                    .setOutputType(".rb.ListBooksResponse")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/shelves/{shelf}/books")
                                                            .setResponseBody("books")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("CountBooks")
                                    .setInputType(".rb.ListBooksRequest")
                                    .setOutputType(".rb.ListBooksResponse")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/shelves/{shelf}/count")
                                                            .setResponseBody("total")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListBooksFull")
                                    .setInputType(".rb.ListBooksRequest")
                                    .setOutputType(".rb.ListBooksResponse")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/shelves/{shelf}/full")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListEmpty")
                                    .setInputType(".rb.ListBooksRequest")
                                    .setOutputType(".rb.ListBooksResponse")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/shelves/{shelf}/empty")
                                                            .setResponseBody("books")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("rb-books", ".pb");
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
        var listBooks = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rb.Books/ListBooks")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_RESPONSE))
                .build();
        var countBooks = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rb.Books/CountBooks")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_RESPONSE))
                .build();
        var listBooksFull = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rb.Books/ListBooksFull")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_RESPONSE))
                .build();
        var listEmpty = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rb.Books/ListEmpty")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_BOOKS_RESPONSE))
                .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("rb.Books")
                .addMethod(listBooks, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    observer.onNext(buildBooksResponse());
                    observer.onCompleted();
                }))
                .addMethod(countBooks, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    observer.onNext(buildBooksResponse());
                    observer.onCompleted();
                }))
                .addMethod(listBooksFull, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    observer.onNext(buildBooksResponse());
                    observer.onCompleted();
                }))
                .addMethod(listEmpty, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    observer.onNext(buildEmptyBooksResponse());
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DynamicMessage buildBooksResponse() {
        DynamicMessage book1 = DynamicMessage.newBuilder(BOOK)
                .setField(BOOK.findFieldByName("id"), "1")
                .setField(BOOK.findFieldByName("title"), "Dune")
                .build();
        DynamicMessage book2 = DynamicMessage.newBuilder(BOOK)
                .setField(BOOK.findFieldByName("id"), "2")
                .setField(BOOK.findFieldByName("title"), "Emma")
                .build();
        return DynamicMessage.newBuilder(LIST_BOOKS_RESPONSE)
                .setField(LIST_BOOKS_RESPONSE.findFieldByName("books"), java.util.Arrays.asList(book1, book2))
                .setField(LIST_BOOKS_RESPONSE.findFieldByName("next_page_token"), "abc")
                .setField(LIST_BOOKS_RESPONSE.findFieldByName("total"), 2L)
                .build();
    }

    private static DynamicMessage buildEmptyBooksResponse() {
        return DynamicMessage.newBuilder(LIST_BOOKS_RESPONSE).build();
    }
}
