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
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * {@code shiguchi.graphql.data-loader.max-batch-size} on the Boot 4 GraphQL surface: five customers
 * resolved through their {@code loader_for} batch method with a limit of two reach the backend as
 * three calls of two, two and one key, and every customer still answers.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.data-loader.max-batch-size=2"
        })
@DisplayName("GraphQL DataLoader max-batch-size end to end (Boot 4)")
class GraphQlDataLoaderBatchSizeEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ORDER = FILE.findMessageTypeByName("Order");
    private static final Descriptor ORDERS = FILE.findMessageTypeByName("Orders");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListRequest");
    private static final Descriptor CUSTOMER = FILE.findMessageTypeByName("Customer");
    private static final Descriptor CUSTOMERS = FILE.findMessageTypeByName("Customers");
    private static final Descriptor GET_CUSTOMERS_REQUEST = FILE.findMessageTypeByName("GetCustomersRequest");
    private static final List<List<String>> BATCH_CALLS = new CopyOnWriteArrayList<>();
    /** The metadata each backend method last received, by full method name. */
    private static final Map<String, Metadata> SEEN = new ConcurrentHashMap<>();

    /** Ids whose batch call the backend fails with UNAVAILABLE; "*" fails every batch. */
    private static final java.util.Set<String> FAILING = ConcurrentHashMap.newKeySet();

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
        SEEN.clear();
        FAILING.clear();
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

    @Test
    @DisplayName(
            "GIVEN max-batch-size 2 and five customers WHEN the query selects each order's customer SHOULD call the backend three times with 2, 2 and 1 keys")
    void given_maxBatchSize2_when_fiveCustomersSelected_should_callBackendInChunks() throws Exception {
        Struct response = graphql("{ orders(count: 5) { items { id customer { id name } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        List<Value> items = response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("orders")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getListValue()
                .getValuesList();
        assertThat(items).hasSize(5);
        for (int i = 0; i < 5; i++) {
            Value customer = items.get(i).getStructValue().getFieldsOrThrow("customer");
            assertThat(customer.getStructValue().getFieldsOrThrow("name").getStringValue())
                    .isEqualTo("Customer cust-" + (i + 1));
        }
        assertThat(BATCH_CALLS).extracting(List::size).containsExactlyInAnyOrder(2, 2, 1);
    }

    @Test
    @DisplayName(
            "GIVEN the customers batch is UNAVAILABLE WHEN the query selects each order's customer SHOULD answer the orders fast with a grpcCode UNAVAILABLE error and a null customer")
    void given_batchUnavailable_when_customersSelected_should_surfaceTheErrorAndKeepTheOrders() throws Exception {
        FAILING.add("*");

        long start = System.nanoTime();
        Struct response = graphql("{ orders(count: 3) { items { id customer { id name } } } }");
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.containsFields("errors")).as(response.toString()).isTrue();
        List<Value> errors = response.getFieldsOrThrow("errors").getListValue().getValuesList();
        assertThat(errors).isNotEmpty();
        for (Value error : errors) {
            assertThat(error.getStructValue()
                            .getFieldsOrThrow("extensions")
                            .getStructValue()
                            .getFieldsOrThrow("grpcCode")
                            .getStringValue())
                    .isEqualTo("UNAVAILABLE");
        }
        List<Value> items = response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("orders")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getListValue()
                .getValuesList();
        assertThat(items).hasSize(3);
        for (Value item : items) {
            assertThat(item.getStructValue().getFieldsOrThrow("id").getStringValue())
                    .startsWith("order-");
            assertThat(item.getStructValue().getFieldsOrThrow("customer").hasNullValue())
                    .isTrue();
        }
        assertThat(millis).as("the failure must not wait for a timeout").isLessThan(10_000);
    }

    @Test
    @DisplayName(
            "GIVEN one of three chunks fails WHEN five customers are selected SHOULD error only the two customers of that chunk and resolve the other three")
    void given_oneChunkFails_when_fiveCustomersSelected_should_isolateTheFailure() throws Exception {
        FAILING.add("cust-3");

        Struct response = graphql("{ orders(count: 5) { items { id customer { id name } } } }");

        List<Value> errors = response.getFieldsOrThrow("errors").getListValue().getValuesList();
        assertThat(errors).as(response.toString()).hasSize(2);
        List<Value> items = response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("orders")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getListValue()
                .getValuesList();
        assertThat(items).hasSize(5);
        List<String> failedChunk = BATCH_CALLS.stream()
                .filter(call -> call.contains("cust-3"))
                .findFirst()
                .orElseThrow();
        assertThat(failedChunk).hasSize(2);
        for (int i = 0; i < 5; i++) {
            Value customer = items.get(i).getStructValue().getFieldsOrThrow("customer");
            boolean inFailedChunk = failedChunk.contains("cust-" + (i + 1));
            assertThat(customer.hasNullValue()).as("customer %d", i + 1).isEqualTo(inFailedChunk);
        }
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * rel.Shop/ListOrders(ListRequest{count, same_customer}) returns Orders{items: Order{id, customer_id
     * -> Customer}}, bound as query orders; rel.Shop/GetCustomers(ids) returns Customers{items}, the
     * loader for Customer.
     */
    private static FileDescriptor buildFile() {
        try {
            FieldOptions idKey = FieldOptions.newBuilder()
                    .setExtension(
                            GraphQlProto.field,
                            FieldRule.newBuilder().setId(true).setBatchKey(true).build())
                    .build();
            FieldOptions resolvesCustomer = FieldOptions.newBuilder()
                    .setExtension(
                            GraphQlProto.field,
                            FieldRule.newBuilder()
                                    .setId(true)
                                    .addResolvesType("Customer")
                                    .build())
                    .build();
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rel/shop2.proto")
                    .setSyntax("proto3")
                    .setPackage("rel")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Customer")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setOptions(idKey))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Order")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("customer_id", 2, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("customerId")
                                    .setOptions(resolvesCustomer)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32, null))
                            .addField(field("same_customer", 2, FieldDescriptorProto.Type.TYPE_BOOL, null)
                                    .setJsonName("sameCustomer")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Orders")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".rel.Order")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
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
                                    .setName("ListOrders")
                                    .setInputType(".rel.ListRequest")
                                    .setOutputType(".rel.Orders")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("orders")
                                                            .setType(Op.QUERY)
                                                            .build())))
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
            Path path = Files.createTempFile("rel-shop", ".pb");
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
        var list = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rel.Shop/ListOrders")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ORDERS))
                .build();
        var customers = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rel.Shop/GetCustomers")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_CUSTOMERS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(CUSTOMERS))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("rel.Shop")
                .addMethod(list, ServerCalls.asyncUnaryCall((request, observer) -> {
                    int count = (Integer) request.getField(LIST_REQUEST.findFieldByName("count"));
                    boolean same = (Boolean) request.getField(LIST_REQUEST.findFieldByName("same_customer"));
                    DynamicMessage.Builder orders = DynamicMessage.newBuilder(ORDERS);
                    for (int n = 1; n <= count; n++) {
                        orders.addRepeatedField(
                                ORDERS.findFieldByName("items"),
                                DynamicMessage.newBuilder(ORDER)
                                        .setField(ORDER.findFieldByName("id"), "order-" + n)
                                        .setField(ORDER.findFieldByName("customer_id"), "cust-" + (same ? 1 : n))
                                        .build());
                    }
                    observer.onNext(orders.build());
                    observer.onCompleted();
                }))
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
            ServerInterceptor capture = new ServerInterceptor() {
                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                    SEEN.put(call.getMethodDescriptor().getFullMethodName(), headers);
                    return next.startCall(call, headers);
                }
            };
            return ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(service, capture))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
