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
 * {@code shiguchi.graphql.limits.*} on the Boot 4 GraphQL surface (DGS on spring-graphql): a
 * query deeper or more complex than allowed is rejected during analysis with an error naming the
 * limit, and the backend is never called; a query within both limits works.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.limits.max-depth=2",
            "shiguchi.graphql.limits.max-complexity=4"
        })
@DisplayName("GraphQL depth and complexity limits end to end (Boot 4, DGS)")
class GraphQlLimitsEndToEndTest {

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

    private static String firstError(Struct response) {
        Value errors = response.getFieldsOrDefault("errors", null);
        if (errors == null || errors.getListValue().getValuesCount() == 0) return null;
        return errors.getListValue()
                .getValues(0)
                .getStructValue()
                .getFieldsOrThrow("message")
                .getStringValue();
    }

    @Test
    @DisplayName("GIVEN a query within both limits WHEN sent SHOULD answer data")
    void given_queryWithinLimits_when_sent_should_answerData() throws Exception {
        Struct response = graphql("{ item(id: \"7\") { id } }");

        assertThat(response.containsFields("errors")).isFalse();
        assertThat(response.getFieldsOrThrow("data")
                        .getStructValue()
                        .getFieldsOrThrow("item")
                        .getStructValue()
                        .getFieldsOrThrow("id")
                        .getStringValue())
                .isEqualTo("7");
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a query deeper than max-depth WHEN sent SHOULD be rejected naming the depth limit and never call the backend")
    void given_tooDeepQuery_when_sent_should_rejectWithoutCallingBackend() throws Exception {
        Struct response = graphql("{ item(id: \"7\") { category { name } } }");

        assertThat(firstError(response)).contains("maximum query depth exceeded");
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a query more complex than max-complexity WHEN sent SHOULD be rejected naming the complexity limit and never call the backend")
    void given_tooComplexQuery_when_sent_should_rejectWithoutCallingBackend() throws Exception {
        // item, id, payload, sku, name: 5 fields at depth 2 — over complexity 4, within depth 2.
        Struct response = graphql("{ item(id: \"7\") { id payload sku name } }");

        assertThat(firstError(response)).contains("maximum query complexity exceeded");
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
