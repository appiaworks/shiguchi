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
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule.Op;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@code shiguchi.graphql.document-cache} and {@code shiguchi.graphql.persisted-queries} on
 * the Boot 4 GraphQL surface (DGS on spring-graphql): the Apollo automatic persisted-query exchange
 * over HTTP — a hash the gateway has not seen is {@code PersistedQueryNotFound}, the hash with its
 * text registers it, the hash alone then runs it — and repeated queries keep answering from the
 * cached document.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.document-cache.enabled=true",
            "shiguchi.graphql.persisted-queries.enabled=true"
        })
@DisplayName("GraphQL document cache and automatic persisted queries end to end (Boot 4, DGS)")
class GraphQlDocumentCacheEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor CATEGORY = FILE.findMessageTypeByName("Category");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
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
    }

    private Struct graphql(String query) throws Exception {
        return post(Map.of("query", query));
    }

    private Struct post(Map<String, Object> request) throws Exception {
        String body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().merge(body, b);
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

    private static String sha256(String text) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static Map<String, Object> persisted(String hash) {
        return Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash));
    }

    private static String itemId(Struct response) {
        return response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("item")
                .getStructValue()
                .getFieldsOrThrow("id")
                .getStringValue();
    }

    @Test
    @DisplayName(
            "GIVEN persisted queries WHEN a client sends an unknown hash, then hash and text, then the hash alone SHOULD ask for the text once and then run it by hash")
    void given_persistedQueries_when_apqExchange_should_registerThenRunByHash() throws Exception {
        String query = "{ item(id: \"42\") { id name } }";
        String hash = sha256(query);

        Struct unknown = post(Map.of("extensions", persisted(hash)));
        assertThat(firstError(unknown)).containsIgnoringCase("PersistedQueryNotFound");
        assertThat(BACKEND_CALLS.get()).isZero();

        Struct registered = post(Map.of("query", query, "extensions", persisted(hash)));
        assertThat(firstError(registered)).isNull();
        assertThat(itemId(registered)).isEqualTo("42");

        Struct byHash = post(Map.of("extensions", persisted(hash)));
        assertThat(firstError(byHash)).isNull();
        assertThat(itemId(byHash)).isEqualTo("42");
        assertThat(BACKEND_CALLS.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN persisted queries WHEN the hash does not match the text SHOULD refuse to register it")
    void given_persistedQueries_when_hashMismatch_should_refuse() throws Exception {
        Struct response =
                post(Map.of("query", "{ item(id: \"1\") { id } }", "extensions", persisted(sha256("something else"))));

        assertThat(firstError(response)).isNotNull();
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName("GIVEN the document cache WHEN the same query is sent repeatedly SHOULD answer every time")
    void given_documentCache_when_sameQueryRepeated_should_answerEachTime() throws Exception {
        for (int i = 0; i < 3; i++) {
            Struct response = graphql("{ item(id: \"7\") { id } }");
            assertThat(firstError(response)).isNull();
            assertThat(itemId(response)).isEqualTo("7");
        }
        assertThat(BACKEND_CALLS.get()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN the document cache WHEN an invalid query is sent twice SHOULD report the validation error both times")
    void given_documentCache_when_invalidQueryRepeated_should_reportErrorEachTime() throws Exception {
        for (int i = 0; i < 2; i++) {
            Struct response = graphql("{ item(id: \"7\") { nope } }");
            assertThat(firstError(response)).contains("nope");
        }
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    /** errs.Store/GetItem(GetRequest{id}) returns Item{id,payload,sku,name,category{name}}, bound as query item. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lim/store.proto")
                    .setSyntax("proto3")
                    .setPackage("lim")
                    .addDependency("shiguchi/api/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Category")
                            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("payload", 2, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("sku", 3, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("name", 4, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("category", 5, FieldDescriptorProto.Type.TYPE_MESSAGE, ".lim.Category")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
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
            Path path = Files.createTempFile("lim-store", ".pb");
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
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lim.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("lim.Store")
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    String id = (String) request.getField(GET_REQUEST.findFieldByName("id"));
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), id)
                            .setField(ITEM.findFieldByName("payload"), "p")
                            .setField(ITEM.findFieldByName("sku"), "s")
                            .setField(ITEM.findFieldByName("name"), "n")
                            .setField(
                                    ITEM.findFieldByName("category"),
                                    DynamicMessage.newBuilder(CATEGORY)
                                            .setField(CATEGORY.findFieldByName("name"), "tools")
                                            .build())
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
