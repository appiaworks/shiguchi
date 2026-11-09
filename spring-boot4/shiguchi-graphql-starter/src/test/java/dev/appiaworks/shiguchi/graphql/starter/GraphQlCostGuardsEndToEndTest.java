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
 * The cost budget ({@code limits.budget.per-minute}, keyed by client IP here), {@code report-cost},
 * {@code introspection: AUTHENTICATED} and {@code batch.max-operations} on the Boot 4 GraphQL
 * surface. Each test uses its own {@code X-Forwarded-For} address so the buckets stay apart.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.limits.report-cost=true",
            "shiguchi.graphql.limits.budget.per-minute=12",
            "shiguchi.graphql.introspection=AUTHENTICATED",
            "shiguchi.graphql.batch.max-operations=3"
        })
@DisplayName("GraphQL cost reporting, per-caller budget, introspection switch and batching end to end (Boot 4, DGS)")
class GraphQlCostGuardsEndToEndTest {

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

    private static final String ITEM_QUERY = "{ item(id: \"7\") { id } }";

    private Struct post(String ip, Object body) throws Exception {
        String json = client.post()
                .uri("/graphql")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        Struct.Builder b = Struct.newBuilder();
        // arrays are wrapped so one parser handles both shapes
        JsonFormat.parser().merge("{\"r\":" + json + "}", b);
        return b.getFieldsOrThrow("r").getKindCase() == Value.KindCase.STRUCT_VALUE
                ? b.getFieldsOrThrow("r").getStructValue()
                : b.build();
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
    @DisplayName("GIVEN report-cost WHEN a query runs SHOULD answer its cost in extensions.cost")
    void given_reportCost_when_queryRuns_should_answerCostInExtensions() throws Exception {
        Struct response = post("10.0.0.1", Map.of("query", ITEM_QUERY));

        assertThat(firstError(response)).isNull();
        // item (cost 2 from the proto option) + id (1)
        assertThat(response.getFieldsOrThrow("extensions")
                        .getStructValue()
                        .getFieldsOrThrow("cost")
                        .getNumberValue())
                .isEqualTo(3.0);
        assertThat(BACKEND_CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a spent budget WHEN the caller sends another query SHOULD get RATE_LIMITED with retryAfter and Retry-After, and no backend call")
    void given_spentBudget_when_anotherQuery_should_beRateLimited() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(firstError(post("10.0.0.2", Map.of("query", ITEM_QUERY))))
                    .isNull();
        }
        assertThat(BACKEND_CALLS.get()).isEqualTo(4);

        var refused = client.post()
                .uri("/graphql")
                .header("X-Forwarded-For", "10.0.0.2")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", ITEM_QUERY))
                .exchangeToMono(r -> r.bodyToMono(String.class).map(b -> Map.entry(r.headers(), b)))
                .block(Duration.ofSeconds(30));

        assertThat(refused.getKey().header("Retry-After")).isNotEmpty();
        Struct.Builder body = Struct.newBuilder();
        JsonFormat.parser().merge(refused.getValue(), body);
        Struct error =
                body.getFieldsOrThrow("errors").getListValue().getValues(0).getStructValue();
        assertThat(error.getFieldsOrThrow("extensions")
                        .getStructValue()
                        .getFieldsOrThrow("classification")
                        .getStringValue())
                .isEqualTo("RATE_LIMITED");
        assertThat(error.getFieldsOrThrow("extensions")
                        .getStructValue()
                        .getFieldsOrThrow("retryAfter")
                        .getNumberValue())
                .isPositive();
        assertThat(BACKEND_CALLS.get()).isEqualTo(4);
    }

    @Test
    @DisplayName(
            "GIVEN introspection AUTHENTICATED WHEN an anonymous caller introspects SHOULD get an error and no schema")
    void given_authenticatedIntrospection_when_anonymousIntrospects_should_beRefused() throws Exception {
        Struct response = post("10.0.0.3", Map.of("query", "{ __schema { queryType { name } } }"));

        assertThat(firstError(response)).isNotNull();
        assertThat(response.containsFields("data")
                        && response.getFieldsOrThrow("data").hasStructValue())
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a batch within max-operations WHEN posted SHOULD answer one result per operation, in order")
    void given_batchWithinLimit_when_posted_should_answerOneResultEach() throws Exception {
        Struct response = post(
                "10.0.0.4",
                java.util.List.of(
                        Map.of("query", "{ item(id: \"1\") { id } }"), Map.of("query", "{ item(id: \"2\") { id } }")));

        var results = response.getFieldsOrThrow("r").getListValue();
        assertThat(results.getValuesCount()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(results.getValues(i)
                            .getStructValue()
                            .getFieldsOrThrow("data")
                            .getStructValue()
                            .getFieldsOrThrow("item")
                            .getStructValue()
                            .getFieldsOrThrow("id")
                            .getStringValue())
                    .isEqualTo(String.valueOf(i + 1));
        }
        assertThat(BACKEND_CALLS.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a batch over max-operations WHEN posted SHOULD be refused with 400 and no backend call")
    void given_batchOverLimit_when_posted_should_beRefused() {
        var status = client.post()
                .uri("/graphql")
                .header("X-Forwarded-For", "10.0.0.5")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.List.of(
                        Map.of("query", ITEM_QUERY),
                        Map.of("query", ITEM_QUERY),
                        Map.of("query", ITEM_QUERY),
                        Map.of("query", ITEM_QUERY)))
                .exchangeToMono(
                        r -> r.bodyToMono(String.class).map(b -> r.statusCode().value() + " " + b))
                .block(Duration.ofSeconds(30));

        assertThat(status).startsWith("400").contains("exceeds shiguchi.graphql.batch.max-operations (3)");
        assertThat(BACKEND_CALLS.get()).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    /** errs.Store/GetItem(GetRequest{id}) returns Item{id,payload,sku,name,category{name}}, bound as query item. */
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
