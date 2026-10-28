package dev.appiaworks.shiguchi.autoconfigure;

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
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The parts of a google.api.http rule beyond method + path, through the whole Boot 3 gateway
 * against a real gRPC server: {@code body} naming a request field, {@code additional_bindings}
 * giving one RPC several routes, and dotted path variables reaching into a nested message.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("google.api.http body, additional_bindings and dotted variables end to end (Boot 3)")
class HttpBindingsEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor BOOK = FILE.findMessageTypeByName("Book");
    private static final Descriptor CREATE_REQUEST = FILE.findMessageTypeByName("CreateBookRequest");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetBookRequest");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "library");
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

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN post: /v1/{parent}/books body: book WHEN a Book is posted SHOULD reach the backend under the book field with parent from the path")
    void given_bodyField_when_posted_should_mapBodyOntoFieldAndParentFromPath() {
        client.post()
                .uri("/v1/shelf-1/books")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"Dune\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("shelf-1/Dune")
                .jsonPath("$.title")
                .isEqualTo("Dune");
    }

    @Test
    @DisplayName(
            "GIVEN the additional binding post: /v1/books body: * WHEN the whole request is posted SHOULD reach the same RPC")
    void given_additionalBindingWithStarBody_when_posted_should_reachSameRpc() {
        client.post()
                .uri("/v1/books")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"parent\":\"shelf-2\",\"book\":{\"title\":\"Emma\"}}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("shelf-2/Emma");
    }

    @Test
    @DisplayName("GIVEN get: /v1/books/{book.id} WHEN called SHOULD nest the dotted path variable into the book field")
    void given_dottedPathVariable_when_called_should_nestIntoBookField() {
        client.get()
                .uri("/v1/books/42")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("42")
                .jsonPath("$.title")
                .isEqualTo("shelf=");
    }

    @Test
    @DisplayName(
            "GIVEN the additional binding get: /v1/shelves/{shelf}/books/{book.id} WHEN called SHOULD bind both variables")
    void given_additionalGetBinding_when_called_should_bindBothVariables() {
        client.get()
                .uri("/v1/shelves/A/books/42")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("42")
                .jsonPath("$.title")
                .isEqualTo("shelf=A");
    }

    // ---------------------------------------------------------------- the gRPC backend

    /**
     * lib.Library: CreateBook(CreateBookRequest{parent, book}) at POST /v1/{parent}/books body:book,
     * plus POST /v1/books body:*; GetBook(GetBookRequest{book, shelf}) at GET /v1/books/{book.id},
     * plus GET /v1/shelves/{shelf}/books/{book.id}.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/library.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(scalar("id", 1))
                            .addField(scalar("title", 2)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("CreateBookRequest")
                            .addField(scalar("parent", 1))
                            .addField(message("book", 2, ".lib.Book")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetBookRequest")
                            .addField(message("book", 1, ".lib.Book"))
                            .addField(scalar("shelf", 2)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Library")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("CreateBook")
                                    .setInputType(".lib.CreateBookRequest")
                                    .setOutputType(".lib.Book")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/{parent}/books")
                                                            .setBody("book")
                                                            .addAdditionalBindings(HttpRule.newBuilder()
                                                                    .setPost("/v1/books")
                                                                    .setBody("*"))
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetBook")
                                    .setInputType(".lib.GetBookRequest")
                                    .setOutputType(".lib.Book")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/books/{book.id}")
                                                            .addAdditionalBindings(HttpRule.newBuilder()
                                                                    .setGet("/v1/shelves/{shelf}/books/{book.id}"))
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static FieldDescriptorProto.Builder message(String name, int number, String type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-library", ".pb");
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
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> create =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("lib.Library/CreateBook")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(CREATE_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BOOK))
                        .build();
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> get =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("lib.Library/GetBook")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BOOK))
                        .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Library")
                .addMethod(create, ServerCalls.asyncUnaryCall((request, observer) -> {
                    // Echo where each part came from: id = parent (path) + "/" + book.title (body).
                    String parent = (String) request.getField(CREATE_REQUEST.findFieldByName("parent"));
                    DynamicMessage book = (DynamicMessage) request.getField(CREATE_REQUEST.findFieldByName("book"));
                    String title = (String) book.getField(BOOK.findFieldByName("title"));
                    observer.onNext(book(parent + "/" + title, title));
                    observer.onCompleted();
                }))
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    // id from the nested {book.id} variable, title records the shelf variable.
                    DynamicMessage book = (DynamicMessage) request.getField(GET_REQUEST.findFieldByName("book"));
                    String id = (String) book.getField(BOOK.findFieldByName("id"));
                    String shelf = (String) request.getField(GET_REQUEST.findFieldByName("shelf"));
                    observer.onNext(book(id, "shelf=" + shelf));
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DynamicMessage book(String id, String title) {
        return DynamicMessage.newBuilder(BOOK)
                .setField(BOOK.findFieldByName("id"), id)
                .setField(BOOK.findFieldByName("title"), title)
                .build();
    }
}
