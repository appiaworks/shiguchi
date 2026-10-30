package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.api.GraphQlFieldRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule.Op;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * GraphQL for a DYNAMIC service that loads after startup, on the Boot 3 GraphQL surface (DGS on
 * spring-graphql): the backend is down when the gateway boots, so the schema has no {@code orders}
 * field; once the background retry loads the service, the schema is rebuilt on the next request,
 * the query resolves, and the {@code loader_for} DataLoader registered late batches the customers
 * and honours the {@code shiguchi.graphql.data-loader.cache} TTL. Before the fix the schema,
 * fetchers and DataLoaders were built once at startup, so such a service never appeared in GraphQL.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.startup.retry-interval=300ms",
            "shiguchi.graphql.data-loader.cache.Customer.ttl=1h"
        })
@DisplayName("GraphQL for a service loaded after startup end to end (Boot 3, DGS)")
class GraphQlLateLoadedServiceEndToEndTest {

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
    private static final String CUSTOMERS_QUERY = "{ orders(count: 3) { items { id customer { id name } } } }";

    /** Reserved before the gateway starts; the backend binds it only later in the test. */
    private static final int BACKEND_PORT = freePort();

    private static volatile Server backend;

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "shop");
        registry.add("shiguchi.services[0].mode", () -> "DYNAMIC");
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND_PORT);
    }

    @AfterAll
    static void stop() {
        if (backend != null) backend.shutdownNow();
    }

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        BATCH_CALLS.clear();
    }

    private Struct graphql(String query) throws Exception {
        String body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("{}"))
                .block(Duration.ofSeconds(30));
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(body, b);
        return b.build();
    }

    @Test
    @DisplayName(
            "GIVEN the backend is down at startup WHEN it starts later SHOULD expose its query, resolve the relationship through the late-registered DataLoader, and serve a repeat from the Customer cache")
    void given_backendDownAtStartup_when_backendComesUp_should_serveGraphQlWithDataLoader() throws Exception {
        // Phase 1: started degraded, the field is not in the schema.
        Struct before = graphql("{ orders(count: 2) { items { id } } }");
        assertThat(before.containsFields("errors")).as(before.toString()).isTrue();

        // Phase 2: the backend appears; the background retry loads it and the schema is rebuilt.
        backend = startBackend(BACKEND_PORT);
        Instant deadline = Instant.now().plusSeconds(30);
        Struct response;
        while (true) {
            BATCH_CALLS.clear();
            response = graphql(CUSTOMERS_QUERY);
            if (!response.containsFields("errors") || Instant.now().isAfter(deadline)) break;
            Thread.sleep(200);
        }

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertCustomers(response);
        assertThat(BATCH_CALLS).as("one batch call carrying every key").hasSize(1);
        assertThat(BATCH_CALLS.get(0)).containsExactlyInAnyOrder("cust-1", "cust-2", "cust-3");

        // Phase 3: the same query again is served from the Customer DataLoader cache.
        Struct again = graphql(CUSTOMERS_QUERY);
        assertThat(again.containsFields("errors")).as(again.toString()).isFalse();
        assertCustomers(again);
        assertThat(BATCH_CALLS).as("no new batch call, answered from the cache").hasSize(1);
    }

    private static void assertCustomers(Struct response) {
        List<Value> items = response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("orders")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getListValue()
                .getValuesList();
        assertThat(items).hasSize(3);
        for (int i = 0; i < 3; i++) {
            Value customer = items.get(i).getStructValue().getFieldsOrThrow("customer");
            assertThat(customer.hasStructValue())
                    .as("order %d customer: %s", i + 1, customer)
                    .isTrue();
            assertThat(customer.getStructValue().getFieldsOrThrow("name").getStringValue())
                    .isEqualTo("Customer cust-" + (i + 1));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

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

    private static DynamicMessage customer(String id) {
        return DynamicMessage.newBuilder(CUSTOMER)
                .setField(CUSTOMER.findFieldByName("id"), id)
                .setField(CUSTOMER.findFieldByName("name"), "Customer " + id)
                .build();
    }

    /** The proto schema gRPC server reflection serves for the dynamic service. */
    private record Schema(FileDescriptor file, ServiceDescriptor service)
            implements ProtoFileDescriptorSupplier, ProtoServiceDescriptorSupplier {
        @Override
        public FileDescriptor getFileDescriptor() {
            return file;
        }

        @Override
        public ServiceDescriptor getServiceDescriptor() {
            return service;
        }
    }

    /** A real (socket) gRPC server with reflection, serving orders and the customer batch method. */
    private static Server startBackend(int port) {
        ServiceDescriptor service = FILE.findServiceByName("Shop");
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
        var descriptor = io.grpc.ServiceDescriptor.newBuilder("rel.Shop")
                .setSchemaDescriptor(new Schema(FILE, service))
                .addMethod(list)
                .addMethod(customers)
                .build();
        ServerServiceDefinition definition = ServerServiceDefinition.builder(descriptor)
                .addMethod(list, ServerCalls.asyncUnaryCall((request, observer) -> {
                    int count = (Integer) request.getField(LIST_REQUEST.findFieldByName("count"));
                    DynamicMessage.Builder orders = DynamicMessage.newBuilder(ORDERS);
                    for (int n = 1; n <= count; n++) {
                        orders.addRepeatedField(
                                ORDERS.findFieldByName("items"),
                                DynamicMessage.newBuilder(ORDER)
                                        .setField(ORDER.findFieldByName("id"), "order-" + n)
                                        .setField(ORDER.findFieldByName("customer_id"), "cust-" + n)
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
            return ServerBuilder.forPort(port)
                    .addService(definition)
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
