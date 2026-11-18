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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Apollo Federation on the Boot 4 GraphQL surface ({@code shiguchi.graphql.federation.enabled=true}):
 * the context starts (the {@code _Any} scalar and the {@code _Entity} union are wired), {@code
 * _entities} resolves the representations of one type through one backend batch call, and {@code
 * _service { sdl }} answers the schema.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.federation.enabled=true"
        })
@DisplayName("GraphQL federation end to end (Boot 4)")
class GraphQlFederationEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor CUSTOMER = FILE.findMessageTypeByName("Customer");
    private static final Descriptor CUSTOMERS = FILE.findMessageTypeByName("Customers");
    private static final Descriptor GET_CUSTOMERS_REQUEST = FILE.findMessageTypeByName("GetCustomersRequest");
    private static final List<List<String>> BATCH_CALLS = new CopyOnWriteArrayList<>();

    /** Ids whose batch call the backend fails with UNAVAILABLE; "*" fails every batch. */
    private static final Set<String> FAILING = ConcurrentHashMap.newKeySet();

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "shop");
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
        BATCH_CALLS.clear();
        FAILING.clear();
    }

    private Struct graphql(String query, Map<String, Object> variables) throws Exception {
        String body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query, "variables", variables))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().merge(body, b);
        return b.build();
    }

    private static List<Value> entities(Struct response) {
        return response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("_entities")
                .getListValue()
                .getValuesList();
    }

    @Test
    @DisplayName(
            "GIVEN federation enabled WHEN _entities gets three Customer representations SHOULD resolve them in order with one backend batch call")
    void given_federation_when_entitiesOfOneType_should_resolveWithOneBatchCall() throws Exception {
        Struct response = graphql(
                "query($reps: [_Any!]!) { _entities(representations: $reps) { __typename ... on Customer { id name } } }",
                Map.of(
                        "reps",
                        List.of(
                                Map.of("__typename", "Customer", "id", "3"),
                                Map.of("__typename", "Customer", "id", "1"),
                                Map.of("__typename", "Customer", "id", "2"))));

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        List<Value> entities = entities(response);
        assertThat(entities).hasSize(3);
        assertThat(entities)
                .extracting(e -> e.getStructValue().getFieldsOrThrow("name").getStringValue())
                .containsExactly("Customer 3", "Customer 1", "Customer 2");
        assertThat(entities)
                .extracting(
                        e -> e.getStructValue().getFieldsOrThrow("__typename").getStringValue())
                .containsOnly("Customer");
        assertThat(BATCH_CALLS).hasSize(1);
        assertThat(BATCH_CALLS.get(0)).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    @DisplayName("GIVEN literal representations WHEN _entities is queried inline SHOULD parse the _Any objects")
    void given_inlineRepresentations_when_entities_should_parseTheLiterals() throws Exception {
        Struct response = graphql(
                "{ _entities(representations: [{__typename: \"Customer\", id: \"7\"}]) { ... on Customer { name } } }",
                Map.of());

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertThat(entities(response)
                        .get(0)
                        .getStructValue()
                        .getFieldsOrThrow("name")
                        .getStringValue())
                .isEqualTo("Customer 7");
    }

    @Test
    @DisplayName(
            "GIVEN a representation of an unknown __typename WHEN _entities is queried SHOULD answer null for it and still resolve the known one")
    void given_unknownTypename_when_entities_should_answerNullForIt() throws Exception {
        Struct response = graphql(
                "query($reps: [_Any!]!) { _entities(representations: $reps) { ... on Customer { id name } } }",
                Map.of(
                        "reps",
                        List.of(
                                Map.of("__typename", "Ghost", "id", "1"),
                                Map.of("__typename", "Customer", "id", "2"))));

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        List<Value> entities = entities(response);
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).hasNullValue()).isTrue();
        assertThat(entities.get(1).getStructValue().getFieldsOrThrow("name").getStringValue())
                .isEqualTo("Customer 2");
    }

    @Test
    @DisplayName(
            "GIVEN the customers batch is UNAVAILABLE WHEN _entities is queried SHOULD answer a grpcCode UNAVAILABLE error and null entries")
    void given_batchUnavailable_when_entities_should_surfaceTheError() throws Exception {
        FAILING.add("*");

        Struct response = graphql(
                "query($reps: [_Any!]!) { _entities(representations: $reps) { ... on Customer { id } } }",
                Map.of(
                        "reps",
                        List.of(
                                Map.of("__typename", "Customer", "id", "1"),
                                Map.of("__typename", "Customer", "id", "2"))));

        List<Value> errors = response.getFieldsOrThrow("errors").getListValue().getValuesList();
        assertThat(errors).as(response.toString()).isNotEmpty();
        assertThat(errors.get(0)
                        .getStructValue()
                        .getFieldsOrThrow("extensions")
                        .getStructValue()
                        .getFieldsOrThrow("grpcCode")
                        .getStringValue())
                .isEqualTo("UNAVAILABLE");
        assertThat(entities(response)).hasSize(2).allMatch(Value::hasNullValue);
    }

    @Test
    @DisplayName(
            "GIVEN federation enabled WHEN _service { sdl } is queried SHOULD return the SDL with the Customer type")
    void given_federation_when_serviceSdl_should_returnTheSchema() throws Exception {
        Struct response = graphql("{ _service { sdl } }", Map.of());

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        String sdl = response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("_service")
                .getStructValue()
                .getFieldsOrThrow("sdl")
                .getStringValue();
        assertThat(sdl).contains("type Customer").contains("@key");
    }

    // ------------------------------------------------------------------ fixtures

    /** rel.Shop/GetCustomers(ids) returns Customers{items}: the loader (and so the @key) of Customer. */
    private static FileDescriptor buildFile() {
        try {
            FieldOptions idKey = FieldOptions.newBuilder()
                    .setExtension(
                            GraphQlProto.field,
                            FieldRule.newBuilder().setId(true).setBatchKey(true).build())
                    .build();
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rel/fed.proto")
                    .setSyntax("proto3")
                    .setPackage("rel")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Customer")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setOptions(idKey))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetCustomersRequest")
                            .addField(field("ids", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Customers")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".rel.Customer")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Shop")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetCustomers")
                                    .setInputType(".rel.GetCustomersRequest")
                                    .setOutputType(".rel.Customers")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("customers")
                                                            .setType(Op.QUERY)
                                                            .setLoaderFor("Customer")
                                                            .setLoaderIdsField("ids")
                                                            .setLoaderResultField("items")
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
            Path path = Files.createTempFile("rel-fed", ".pb");
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

    private static DynamicMessage customer(String id) {
        return DynamicMessage.newBuilder(CUSTOMER)
                .setField(CUSTOMER.findFieldByName("id"), id)
                .setField(CUSTOMER.findFieldByName("name"), "Customer " + id)
                .build();
    }

    private static Server startBackend() {
        var customers = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rel.Shop/GetCustomers")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_CUSTOMERS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(CUSTOMERS))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("rel.Shop")
                .addMethod(customers, ServerCalls.asyncUnaryCall((request, observer) -> {
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) request.getField(GET_CUSTOMERS_REQUEST.findFieldByName("ids"));
                    BATCH_CALLS.add(List.copyOf(ids));
                    if (FAILING.contains("*") || ids.stream().anyMatch(FAILING::contains)) {
                        observer.onError(
                                Status.UNAVAILABLE.withDescription("shop down").asRuntimeException());
                        return;
                    }
                    DynamicMessage.Builder reply = DynamicMessage.newBuilder(CUSTOMERS);
                    for (String id : ids) reply.addRepeatedField(CUSTOMERS.findFieldByName("items"), customer(id));
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
