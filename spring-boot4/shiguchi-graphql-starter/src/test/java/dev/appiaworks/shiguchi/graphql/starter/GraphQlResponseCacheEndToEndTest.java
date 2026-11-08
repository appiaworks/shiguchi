package dev.appiaworks.shiguchi.graphql.starter;

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
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.GraphQlRule.Op;
import dev.appiaworks.shiguchi.proto.MethodProto;
import dev.appiaworks.shiguchi.proto.MethodRule;
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
import java.util.Map;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@code shiguchi.graphql.response-cache} on the Boot 4 GraphQL surface (DGS on spring-graphql):
 * a query whose root field's method carries {@code (shiguchi.method).cache_ttl_seconds} reaches
 * the backend once and is then answered from the cache with {@code Cache-Status} and {@code Age},
 * and a mutation on the same service evicts it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.response-cache.enabled=true"
        })
@DisplayName("GraphQL response cache end to end (Boot 4, DGS)")
class GraphQlResponseCacheEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");
    private static final Descriptor UPDATE_REQUEST = FILE.findMessageTypeByName("UpdateRequest");
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final AtomicInteger BACKEND_UPDATES = new AtomicInteger();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "store");
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
        BACKEND_UPDATES.set(0);
    }

    private ResponseEntity<String> graphql(String query) {
        return client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(30));
    }

    private static Struct body(ResponseEntity<String> response) throws Exception {
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().merge(response.getBody(), b);
        return b.build();
    }

    private static String firstError(Struct response) {
        Value errors = response.getFieldsOrDefault("errors", null);
        if (errors == null || errors.getListValue().getValuesCount() == 0) return null;
        return errors.getListValue()
                .getValues(0)
                .getStructValue()
                .getFieldsOrThrow("message")
                .getStringValue();
    }

    private static String itemName(Struct response, String field) {
        return response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow(field)
                .getStructValue()
                .getFieldsOrThrow("name")
                .getStringValue();
    }

    private static String cacheStatus(ResponseEntity<String> response) {
        return response.getHeaders().getFirst("Cache-Status");
    }

    @Test
    @DisplayName(
            "GIVEN a root field whose method has cache_ttl_seconds WHEN the same query is sent three times SHOULD call the backend once and mark the hits")
    void given_cacheTtl_when_sameQueryRepeated_should_callBackendOnceAndMarkHits() throws Exception {
        ResponseEntity<String> first = graphql("{ item(id: \"1\") { id name } }");
        assertThat(firstError(body(first))).isNull();
        assertThat(itemName(body(first), "item")).isEqualTo("n1");
        assertThat(cacheStatus(first)).isEqualTo("shiguchi; fwd=miss; stored");
        assertThat(first.getHeaders().getFirst("Age")).isNull();

        for (int i = 0; i < 2; i++) {
            ResponseEntity<String> hit = graphql("{ item(id: \"1\") { id name } }");
            assertThat(firstError(body(hit))).isNull();
            assertThat(itemName(body(hit), "item")).isEqualTo("n1");
            assertThat(cacheStatus(hit)).isEqualTo("shiguchi; hit");
            assertThat(hit.getHeaders().getFirst("Age")).isNotNull();
        }
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a cached query WHEN a mutation on the same service runs SHOULD serve the next query from the backend")
    void given_cachedQuery_when_mutationOnSameService_should_refetch() throws Exception {
        ResponseEntity<String> first = graphql("{ item(id: \"2\") { id name } }");
        assertThat(cacheStatus(first)).isEqualTo("shiguchi; fwd=miss; stored");
        assertThat(itemName(body(first), "item")).isEqualTo("n1");

        ResponseEntity<String> mutation =
                graphql("mutation { updateItem(input: {id: \"2\", name: \"renamed\"}) { id name } }");
        assertThat(firstError(body(mutation))).isNull();
        assertThat(itemName(body(mutation), "updateItem")).isEqualTo("renamed");
        assertThat(cacheStatus(mutation)).isNull();
        assertThat(BACKEND_UPDATES.get()).isEqualTo(1);

        ResponseEntity<String> after = graphql("{ item(id: \"2\") { id name } }");
        assertThat(cacheStatus(after)).isEqualTo("shiguchi; fwd=miss; stored");
        assertThat(itemName(body(after), "item")).isEqualTo("n2");
        assertThat(BACKEND_CALLS.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a cached query WHEN the same query comes with another argument SHOULD be a separate entry")
    void given_cachedQuery_when_otherArgument_should_beSeparateEntry() throws Exception {
        graphql("{ item(id: \"3\") { id } }");
        ResponseEntity<String> other = graphql("{ item(id: \"4\") { id } }");

        assertThat(cacheStatus(other)).isEqualTo("shiguchi; fwd=miss; stored");
        assertThat(BACKEND_CALLS.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * lim.Store: GetItem(GetRequest{id}) bound as query item with cache_ttl_seconds 30, and
     * UpdateItem(UpdateRequest{id,name}) bound as mutation updateItem.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lim/store.proto")
                    .setSyntax("proto3")
                    .setPackage("lim")
                    .addDependency("shiguchi/graphql.proto")
                    .addDependency("shiguchi/method.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("UpdateRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetItem")
                                    .setInputType(".lim.GetRequest")
                                    .setOutputType(".lim.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("item")
                                                            .setType(Op.QUERY)
                                                            .build())
                                            .setExtension(
                                                    MethodProto.method,
                                                    MethodRule.newBuilder()
                                                            .setCacheTtlSeconds(30)
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("UpdateItem")
                                    .setInputType(".lim.UpdateRequest")
                                    .setOutputType(".lim.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("updateItem")
                                                            .setType(Op.MUTATION)
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(
                    proto, new FileDescriptor[] {GraphQlProto.getDescriptor(), MethodProto.getDescriptor()});
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
            Path path = Files.createTempFile("lim-store-cache", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(MethodProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** GetItem answers "n" plus the number of reads so far, so a refetch after a mutation is visible. */
    private static Server startBackend() {
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lim.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        var update = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lim.Store/UpdateItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(UPDATE_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("lim.Store")
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    int n = BACKEND_CALLS.incrementAndGet();
                    String id = (String) request.getField(GET_REQUEST.findFieldByName("id"));
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), id)
                            .setField(ITEM.findFieldByName("name"), "n" + n)
                            .build());
                    observer.onCompleted();
                }))
                .addMethod(update, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_UPDATES.incrementAndGet();
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(
                                    ITEM.findFieldByName("id"), request.getField(UPDATE_REQUEST.findFieldByName("id")))
                            .setField(
                                    ITEM.findFieldByName("name"),
                                    request.getField(UPDATE_REQUEST.findFieldByName("name")))
                            .build());
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
