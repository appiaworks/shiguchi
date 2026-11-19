package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.GraphQlRule.Op;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A one-to-many field declared in YAML only ({@code relationships[]} and {@code loaders[]}, the proto
 * carries no {@code resolves_many} or {@code loader_for}) on the Boot 4 GraphQL surface: {@code authors { books { title } }}
 * costs one call for the authors and one batch call for all their books, however many authors
 * there are. An author without books answers {@code []}, and a failed batch errors the field.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("GraphQL one-to-many fields through a grouped DataLoader end to end (Boot 4, DGS)")
class GraphQlYamlRelationshipEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor AUTHOR = FILE.findMessageTypeByName("Author");
    private static final Descriptor AUTHORS = FILE.findMessageTypeByName("Authors");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListRequest");
    private static final Descriptor BOOK = FILE.findMessageTypeByName("Book");
    private static final Descriptor BOOKS = FILE.findMessageTypeByName("Books");
    private static final Descriptor BOOKS_REQUEST = FILE.findMessageTypeByName("BooksRequest");
    private static final List<List<String>> BOOK_CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger AUTHOR_CALLS = new AtomicInteger();
    private static final AtomicBoolean FAIL_BOOKS = new AtomicBoolean();

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "library");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        // the relationship and its loader live in YAML only: the proto carries neither
        registry.add("shiguchi.services[0].relationships[0].from", () -> "Author.id");
        registry.add("shiguchi.services[0].relationships[0].to", () -> "Book");
        registry.add("shiguchi.services[0].relationships[0].many", () -> "true");
        registry.add("shiguchi.services[0].relationships[0].group-by", () -> "author_id");
        registry.add("shiguchi.services[0].loaders[0].type", () -> "Book");
        registry.add("shiguchi.services[0].loaders[0].rpc", () -> "lib.Library/BooksByAuthors");
        registry.add("shiguchi.services[0].loaders[0].ids-field", () -> "author_ids");
        registry.add("shiguchi.services[0].loaders[0].result-field", () -> "items");
        registry.add("shiguchi.services[0].loaders[0].group-by", () -> "author_id");
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
        BOOK_CALLS.clear();
        AUTHOR_CALLS.set(0);
        FAIL_BOOKS.set(false);
    }

    private Struct graphql(String query) throws Exception {
        String body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().merge(body, b);
        return b.build();
    }

    private static List<Value> authors(Struct response) {
        return response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("authors")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getListValue()
                .getValuesList();
    }

    @Test
    @DisplayName(
            "GIVEN four authors WHEN the query selects their books SHOULD make two upstream calls and answer [] for an author without books")
    void given_authorsWithBooks_when_queried_should_makeTwoCallsAndKeepEmptyLists() throws Exception {
        Struct response = graphql("{ authors(count: 4) { items { id books { title } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        List<Value> items = authors(response);
        assertThat(items).hasSize(4);
        // author n has n-1 books: the first has none
        for (int n = 1; n <= 4; n++) {
            List<Value> books = items.get(n - 1)
                    .getStructValue()
                    .getFieldsOrThrow("books")
                    .getListValue()
                    .getValuesList();
            assertThat(books).as("author %d", n).hasSize(n - 1);
            for (int k = 0; k < books.size(); k++) {
                assertThat(books.get(k)
                                .getStructValue()
                                .getFieldsOrThrow("title")
                                .getStringValue())
                        .isEqualTo("Book " + n + "-" + (k + 1));
            }
        }
        assertThat(AUTHOR_CALLS.get()).isEqualTo(1);
        assertThat(BOOK_CALLS).as("one batch call carrying every author").hasSize(1);
        assertThat(BOOK_CALLS.get(0)).containsExactlyInAnyOrder("author-1", "author-2", "author-3", "author-4");
    }

    @Test
    @DisplayName("GIVEN a hundred authors WHEN their books are selected SHOULD make one grouped upstream call")
    void given_hundredAuthors_when_queried_should_makeOneGroupedCall() throws Exception {
        Struct response = graphql("{ authors(count: 100) { items { id books { title } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertThat(authors(response)).hasSize(100);
        assertThat(BOOK_CALLS).hasSize(1);
        assertThat(BOOK_CALLS.get(0)).hasSize(100);
    }

    @Test
    @DisplayName("GIVEN a failing batch under on-error ERROR WHEN the books are selected SHOULD error the field")
    void given_failingBatch_when_queried_should_errorTheField() throws Exception {
        FAIL_BOOKS.set(true);

        Struct response = graphql("{ authors(count: 2) { items { id books { title } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * lib.Library/ListAuthors(count) returns Authors{items: Author{id, name}}; lib.Library/BooksByAuthors(author_ids) returns Books{items: Book{id,
     * author_id, title}}, the grouped loader of Book (declared in the YAML).
     */
    private static FileDescriptor buildFile() {
        try {
            FieldOptions authorId = FieldOptions.newBuilder()
                    .setExtension(
                            GraphQlProto.field,
                            FieldRule.newBuilder().setId(true).build())
                    .build();
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/library.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Author")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setOptions(authorId))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("author_id", 2, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("authorId"))
                            .addField(field("title", 3, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Authors")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".lib.Author")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("BooksRequest")
                            .addField(field("author_ids", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("authorIds")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Books")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".lib.Book")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Library")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListAuthors")
                                    .setInputType(".lib.ListRequest")
                                    .setOutputType(".lib.Authors")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("authors")
                                                            .setType(Op.QUERY)
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("BooksByAuthors")
                                    .setInputType(".lib.BooksRequest")
                                    .setOutputType(".lib.Books")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("booksByAuthors")
                                                            .setType(Op.QUERY)
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(
            String name, int number, FieldDescriptorProto.Type type, String typeName) {
        FieldDescriptorProto.Builder b = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
        if (typeName != null) b.setTypeName(typeName);
        return b;
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-library", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        var list = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Library/ListAuthors")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(AUTHORS))
                .build();
        var books = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Library/BooksByAuthors")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(BOOKS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(BOOKS))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Library")
                .addMethod(list, ServerCalls.asyncUnaryCall((request, observer) -> {
                    AUTHOR_CALLS.incrementAndGet();
                    int count = (Integer) request.getField(LIST_REQUEST.findFieldByName("count"));
                    DynamicMessage.Builder authors = DynamicMessage.newBuilder(AUTHORS);
                    for (int n = 1; n <= count; n++) {
                        authors.addRepeatedField(
                                AUTHORS.findFieldByName("items"),
                                DynamicMessage.newBuilder(AUTHOR)
                                        .setField(AUTHOR.findFieldByName("id"), "author-" + n)
                                        .setField(AUTHOR.findFieldByName("name"), "Author " + n)
                                        .build());
                    }
                    observer.onNext(authors.build());
                    observer.onCompleted();
                }))
                .addMethod(books, ServerCalls.asyncUnaryCall((request, observer) -> {
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) request.getField(BOOKS_REQUEST.findFieldByName("author_ids"));
                    BOOK_CALLS.add(List.copyOf(ids));
                    if (FAIL_BOOKS.get()) {
                        observer.onError(
                                Status.UNAVAILABLE.withDescription("books down").asRuntimeException());
                        return;
                    }
                    DynamicMessage.Builder reply = DynamicMessage.newBuilder(BOOKS);
                    for (String id : ids) {
                        int n = Integer.parseInt(id.substring("author-".length()));
                        // author n has n-1 books
                        for (int k = 1; k < n; k++) {
                            reply.addRepeatedField(
                                    BOOKS.findFieldByName("items"),
                                    DynamicMessage.newBuilder(BOOK)
                                            .setField(BOOK.findFieldByName("id"), "book-" + n + "-" + k)
                                            .setField(BOOK.findFieldByName("author_id"), id)
                                            .setField(BOOK.findFieldByName("title"), "Book " + n + "-" + k)
                                            .build());
                        }
                    }
                    observer.onNext(reply.build());
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
