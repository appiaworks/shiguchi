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
import dev.appiaworks.shiguchi.proto.api.GraphQlFieldRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule.Op;
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
 * A {@code resolves_type} field resolved through its {@code loader_for} batch method, on the Boot
 * 3 GraphQL surface (DGS on spring-graphql): a nested selection over N parents costs one batch
 * call carrying the N keys, and answers promptly. The relationship fetcher gives up after ten
 * seconds and answers {@code null}, so a dispatch that never fires shows up as both a slow
 * response and missing data.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.client.forward-headers[0]=x-tenant"
        })
@DisplayName("GraphQL relationship fields through a DataLoader end to end (Boot 3, DGS)")
class GraphQlRelationshipDataLoaderEndToEndTest {

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
            "GIVEN five orders of five customers WHEN the query selects each order's customer SHOULD answer every customer from one batch call, promptly")
    void given_nestedSelection_when_queried_should_batchOnceAndAnswerPromptly() throws Exception {
        long started = System.nanoTime();

        Struct response = graphql("{ orders(count: 5) { items { id customer { id name } } } }");

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
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
            Struct order = items.get(i).getStructValue();
            Value customer = order.getFieldsOrThrow("customer");
            assertThat(customer.hasStructValue())
                    .as("order %d customer: %s", i + 1, customer)
                    .isTrue();
            assertThat(customer.getStructValue().getFieldsOrThrow("name").getStringValue())
                    .isEqualTo("Customer cust-" + (i + 1));
        }
        assertThat(BATCH_CALLS).as("one batch call carrying every key").hasSize(1);
        assertThat(BATCH_CALLS.get(0)).containsExactlyInAnyOrder("cust-1", "cust-2", "cust-3", "cust-4", "cust-5");
        assertThat(elapsedMillis)
                .as("the DataLoader was dispatched, not timed out")
                .isLessThan(5_000);
    }

    @Test
    @DisplayName(
            "GIVEN a caller with an Authorization header and a forwarded header WHEN the query selects a relationship SHOULD send both on the top-level call and on the batch call")
    void given_callerHeaders_when_queried_should_reachTopLevelAndBatchCalls() throws Exception {
        String body = client.post()
                .uri("/graphql")
                .header("Authorization", "Bearer caller-token")
                .header("X-Tenant", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", "{ orders(count: 2) { items { id customer { id } } } }"))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        assertThat(body).doesNotContain("errors");
        for (String method : List.of("rel.Shop/ListOrders", "rel.Shop/GetCustomers")) {
            Metadata seen = SEEN.get(method);
            assertThat(seen).as(method).isNotNull();
            assertThat(seen.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))
                    .as("%s authorization", method)
                    .isEqualTo("Bearer caller-token");
            assertThat(seen.get(Metadata.Key.of("x-tenant", Metadata.ASCII_STRING_MARSHALLER)))
                    .as("%s x-tenant", method)
                    .isEqualTo("acme");
        }
    }

    @Test
    @DisplayName(
            "GIVEN two orders of the same customer WHEN the query selects their customer SHOULD batch the key once")
    void given_sharedCustomer_when_queried_should_deduplicateKeys() throws Exception {
        Struct response = graphql("{ orders(count: 2, sameCustomer: true) { items { customer { id } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertThat(BATCH_CALLS).hasSize(1);
        assertThat(BATCH_CALLS.get(0)).containsExactly("cust-1");
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
                            GraphQlProto.graphqlField,
                            GraphQlFieldRule.newBuilder()
                                    .setId(true)
                                    .setBatchKey(true)
                                    .build())
                    .build();
            FieldOptions resolvesCustomer = FieldOptions.newBuilder()
                    .setExtension(
                            GraphQlProto.graphqlField,
                            GraphQlFieldRule.newBuilder()
                                    .setId(true)
                                    .addResolvesType("Customer")
                                    .build())
                    .build();
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rel/shop.proto")
                    .setSyntax("proto3")
                    .setPackage("rel")
                    .addDependency("shiguchi/api/graphql.proto")
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
