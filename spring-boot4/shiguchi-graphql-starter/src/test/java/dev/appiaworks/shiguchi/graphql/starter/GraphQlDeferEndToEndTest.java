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
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.GraphQlRule.Op;
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
 * @defer over multipart/mixed: the initial part, then the deferred part, and plain JSON otherwise.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.incremental.enabled=true"
        })
@DisplayName("GraphQL @defer end to end (Boot 4, DGS)")
class GraphQlDeferEndToEndTest {

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

    private String query(String accept, String query) {
        return client.post()
                .uri("/graphql")
                .header("Accept", accept)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> r.headers().contentType().orElseThrow() + "\n" + b))
                .block(Duration.ofSeconds(30));
    }

    private static final String DEFER_QUERY = "{ item(id: \"7\") { id ... @defer(label: \"more\") { name } } }";

    @Test
    @DisplayName(
            "GIVEN Accept multipart/mixed WHEN a query defers a fragment SHOULD stream the initial part then the deferred part")
    void given_multipartAccept_when_queryDefers_should_streamInitialThenDeferred() {
        String answer = query("multipart/mixed;deferSpec=20220824, application/json", DEFER_QUERY);

        assertThat(answer).startsWith("multipart/mixed");
        assertThat(answer).contains("deferSpec=20220824");
        int initial = answer.indexOf("\"hasNext\":true");
        int deferred = answer.indexOf("\"label\":\"more\"");
        assertThat(initial).isPositive();
        assertThat(deferred).isGreaterThan(initial);
        assertThat(answer).contains("\"id\":\"7\"").contains("\"name\":\"n\"").contains("\"hasNext\":false");
        assertThat(answer).endsWith("\r\n-----\r\n");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN no multipart Accept WHEN a query defers a fragment SHOULD answer one complete JSON result")
    void given_jsonAccept_when_queryDefers_should_answerCompleteJson() {
        String answer = query("application/json", DEFER_QUERY);

        assertThat(answer).startsWith("application/json");
        assertThat(answer).contains("\"id\":\"7\"").contains("\"name\":\"n\"").doesNotContain("hasNext");
    }

    @Test
    @DisplayName("GIVEN Accept multipart/mixed WHEN the query defers nothing SHOULD answer plain JSON")
    void given_multipartAccept_when_nothingDeferred_should_answerPlainJson() {
        String answer = query("multipart/mixed;deferSpec=20220824", "{ item(id: \"7\") { id name } }");

        assertThat(answer).startsWith("application/json");
        assertThat(answer).contains("\"name\":\"n\"").doesNotContain("hasNext");
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("cg/store.proto")
                    .setSyntax("proto3")
                    .setPackage("cg")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Category")
                            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("payload", 2, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("sku", 3, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("name", 4, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("category", 5, FieldDescriptorProto.Type.TYPE_MESSAGE, ".cg.Category")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetItem")
                                    .setInputType(".cg.GetRequest")
                                    .setOutputType(".cg.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("item")
                                                            .setCost(2)
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
            Path path = Files.createTempFile("cg-store", ".pb");
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
                .setFullMethodName("cg.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("cg.Store")
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
