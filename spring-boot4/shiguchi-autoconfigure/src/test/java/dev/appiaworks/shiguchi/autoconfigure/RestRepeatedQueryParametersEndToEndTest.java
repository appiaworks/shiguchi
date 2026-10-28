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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Repeated fields filled from the query string, through the whole Boot 4 gateway against a real
 * gRPC server: a repeated key ({@code ?tags=a&tags=b}) appends one element per occurrence, for top-level and nested repeated fields and for numbers alike.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("Repeated query parameters end to end (Boot 4)")
class RestRepeatedQueryParametersEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor SEARCH = FILE.findMessageTypeByName("SearchRequest");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "search");
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
    @DisplayName("GIVEN repeated string tags WHEN ?tags=a&tags=b is sent SHOULD reach the backend as [a, b]")
    void given_repeatedString_when_keyRepeated_should_sendEveryValue() {
        client.get()
                .uri("/v1/search?tags=a&tags=b")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.tags.length()")
                .isEqualTo(2)
                .jsonPath("$.tags[0]")
                .isEqualTo("a")
                .jsonPath("$.tags[1]")
                .isEqualTo("b");
    }

    @Test
    @DisplayName("GIVEN repeated string tags WHEN one ?tags=a is sent SHOULD reach the backend as [a]")
    void given_repeatedString_when_keyOnce_should_sendOneElement() {
        client.get()
                .uri("/v1/search?tags=a")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.tags.length()")
                .isEqualTo(1)
                .jsonPath("$.tags[0]")
                .isEqualTo("a");
    }

    @Test
    @DisplayName("GIVEN repeated int32 years WHEN ?years=1965&years=1984 is sent SHOULD reach the backend as numbers")
    void given_repeatedNumber_when_keyRepeated_should_sendEveryValue() {
        client.get()
                .uri("/v1/search?years=1965&years=1984")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.years[0]")
                .isEqualTo(1965)
                .jsonPath("$.years[1]")
                .isEqualTo(1984);
    }

    @Test
    @DisplayName(
            "GIVEN a nested repeated field filter.authors WHEN the dotted key is repeated SHOULD fill the nested list")
    void given_nestedRepeated_when_dottedKeyRepeated_should_fillNestedList() {
        client.get()
                .uri("/v1/search?filter.authors=Le Guin&filter.authors=Herbert&query=dune")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.filter.authors[0]")
                .isEqualTo("Le Guin")
                .jsonPath("$.filter.authors[1]")
                .isEqualTo("Herbert")
                .jsonPath("$.query")
                .isEqualTo("dune");
    }

    @Test
    @DisplayName("GIVEN repeated string tags WHEN ?tags=a,b is sent SHOULD keep the comma inside one element")
    void given_repeatedString_when_commaInValue_should_notSplit() {
        client.get()
                .uri("/v1/search?tags=a,b")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.tags.length()")
                .isEqualTo(1)
                .jsonPath("$.tags[0]")
                .isEqualTo("a,b");
    }

    @Test
    @DisplayName("GIVEN a singular field WHEN its key is repeated SHOULD keep the first value")
    void given_singularField_when_keyRepeated_should_keepFirstValue() {
        client.get()
                .uri("/v1/search?query=first&query=second")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.query")
                .isEqualTo("first");
    }

    // ---------------------------------------------------------------- the gRPC backend

    /**
     * lib.Search: Find(SearchRequest{query, tags[], years[], filter{authors[]}}) at GET /v1/search,
     * answering the request itself so a test sees exactly what reached the backend.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/search.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Filter")
                            .addField(field("authors", 1, FieldDescriptorProto.Type.TYPE_STRING, true)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("SearchRequest")
                            .addField(field("query", 1, FieldDescriptorProto.Type.TYPE_STRING, false))
                            .addField(field("tags", 2, FieldDescriptorProto.Type.TYPE_STRING, true))
                            .addField(field("years", 3, FieldDescriptorProto.Type.TYPE_INT32, true))
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("filter")
                                    .setNumber(4)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".lib.Filter")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Search")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Find")
                                    .setInputType(".lib.SearchRequest")
                                    .setOutputType(".lib.SearchRequest")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/search")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(
            String name, int number, FieldDescriptorProto.Type type, boolean repeated) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(
                        repeated
                                ? FieldDescriptorProto.Label.LABEL_REPEATED
                                : FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-search", ".pb");
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
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> find =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("lib.Search/Find")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SEARCH))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(SEARCH))
                        .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Search")
                .addMethod(find, ServerCalls.asyncUnaryCall((request, observer) -> {
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
