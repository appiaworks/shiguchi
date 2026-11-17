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
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Relationship fields registered from the emitted schema: one inside a nested message, a chain of
 * two loaders (order, customer, region), and one type reached at two depths (customer, then the
 * customer's referrer). Each test counts the batch calls the backend receives per loader.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("GraphQL nested relationship fetchers end to end (Boot 4)")
class GraphQlNestedRelationshipEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ORDER = FILE.findMessageTypeByName("Order");
    private static final Descriptor ORDERS = FILE.findMessageTypeByName("Orders");
    private static final Descriptor CONTACT = FILE.findMessageTypeByName("Contact");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListRequest");
    private static final Descriptor CUSTOMER = FILE.findMessageTypeByName("Customer");
    private static final Descriptor CUSTOMERS = FILE.findMessageTypeByName("Customers");
    private static final Descriptor REGION = FILE.findMessageTypeByName("Region");
    private static final Descriptor REGIONS = FILE.findMessageTypeByName("Regions");
    private static final Descriptor IDS_REQUEST = FILE.findMessageTypeByName("IdsRequest");
    private static final List<List<String>> CUSTOMER_CALLS = new CopyOnWriteArrayList<>();
    private static final List<List<String>> REGION_CALLS = new CopyOnWriteArrayList<>();

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
        CUSTOMER_CALLS.clear();
        REGION_CALLS.clear();
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

    private static List<Value> items(Struct response) {
        return response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("orders")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getListValue()
                .getValuesList();
    }

    @Test
    @DisplayName(
            "GIVEN a relationship field inside a nested message WHEN queried SHOULD resolve it through one batch call")
    void given_nestedMessageRelationship_when_queried_should_resolveThroughOneBatch() throws Exception {
        Struct response = graphql("{ orders(count: 3) { items { id contact { customer { id name } } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        List<Value> items = items(response);
        assertThat(items).hasSize(3);
        for (Value item : items) {
            Value customer = item.getStructValue()
                    .getFieldsOrThrow("contact")
                    .getStructValue()
                    .getFieldsOrThrow("customer");
            assertThat(customer.getStructValue().getFieldsOrThrow("name").getStringValue())
                    .isEqualTo("Customer cust-1");
        }
        assertThat(CUSTOMER_CALLS).hasSize(1);
    }

    @Test
    @DisplayName(
            "GIVEN orders whose customers point at regions WHEN the query selects order, customer and region SHOULD make one batch call per loader")
    void given_twoLevelChain_when_queried_should_makeOneBatchCallPerLevel() throws Exception {
        Struct response = graphql("{ orders(count: 4) { items { id customer { id region { id name } } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        List<Value> items = items(response);
        assertThat(items).hasSize(4);
        for (int i = 0; i < 4; i++) {
            Value region = items.get(i)
                    .getStructValue()
                    .getFieldsOrThrow("customer")
                    .getStructValue()
                    .getFieldsOrThrow("region");
            assertThat(region.getStructValue().getFieldsOrThrow("id").getStringValue())
                    .isEqualTo("region-" + (i % 2 + 1));
        }
        assertThat(CUSTOMER_CALLS).as("GetCustomers calls").hasSize(1);
        assertThat(REGION_CALLS).as("GetRegions calls").hasSize(1);
    }

    @Test
    @DisplayName(
            "GIVEN a type reached at two depths WHEN queried SHOULD not refetch the keys the first level already loaded")
    void given_sameTypeAtTwoDepths_when_queried_should_notRefetchKeysAlreadyLoaded() throws Exception {
        Struct response = graphql("{ orders(count: 3) { items { customer { id referrer { id name } } } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        assertThat(items(response)).hasSize(3);
        assertThat(CUSTOMER_CALLS).as("GetCustomers calls: " + CUSTOMER_CALLS).hasSize(2);
        assertThat(CUSTOMER_CALLS.get(0)).containsExactlyInAnyOrder("cust-1", "cust-2", "cust-3");
        assertThat(CUSTOMER_CALLS.get(1)).containsExactly("cust-4");
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * rel.Shop/ListOrders returns Orders{items: Order{id, customer_id -> Customer, contact: Contact{
     * customer_id -> Customer}}}; Customer{id, name, region_id -> Region, referrer_id -> Customer}.
     * GetCustomers and GetRegions are the loaders.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rel/nested.proto")
                    .setSyntax("proto3")
                    .setPackage("rel")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Region")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setOptions(idKey()))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Customer")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setOptions(idKey()))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("region_id", 3, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("regionId")
                                    .setOptions(resolves("Region")))
                            .addField(field("referrer_id", 4, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("referrerId")
                                    .setOptions(resolves("Customer"))))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Contact")
                            .addField(field("customer_id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("customerId")
                                    .setOptions(resolves("Customer"))))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Order")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("customer_id", 2, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("customerId")
                                    .setOptions(resolves("Customer")))
                            .addField(field("contact", 3, FieldDescriptorProto.Type.TYPE_MESSAGE, ".rel.Contact")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Orders")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".rel.Order")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("IdsRequest")
                            .addField(field("ids", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Customers")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".rel.Customer")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Regions")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".rel.Region")
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
                            .addMethod(loaderMethod("GetCustomers", "Customers", "customers", "Customer"))
                            .addMethod(loaderMethod("GetRegions", "Regions", "regions", "Region")))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder loaderMethod(
            String method, String output, String queryName, String loaderFor) {
        return MethodDescriptorProto.newBuilder()
                .setName(method)
                .setInputType(".rel.IdsRequest")
                .setOutputType(".rel." + output)
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                GraphQlProto.graphql,
                                GraphQlRule.newBuilder()
                                        .setName(queryName)
                                        .setType(Op.QUERY)
                                        .setLoaderFor(loaderFor)
                                        .setLoaderIdsField("ids")
                                        .setLoaderResultField("items")
                                        .build()));
    }

    private static FieldOptions idKey() {
        return FieldOptions.newBuilder()
                .setExtension(
                        GraphQlProto.field,
                        FieldRule.newBuilder().setId(true).setBatchKey(true).build())
                .build();
    }

    private static FieldOptions resolves(String type) {
        return FieldOptions.newBuilder()
                .setExtension(
                        GraphQlProto.field,
                        FieldRule.newBuilder().setId(true).addResolvesType(type).build())
                .build();
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
            Path path = Files.createTempFile("rel-nested", ".pb");
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

    private static int number(String id) {
        return Integer.parseInt(id.substring(id.indexOf('-') + 1));
    }

    private static DynamicMessage customer(String id) {
        int n = number(id);
        return DynamicMessage.newBuilder(CUSTOMER)
                .setField(CUSTOMER.findFieldByName("id"), id)
                .setField(CUSTOMER.findFieldByName("name"), "Customer " + id)
                .setField(CUSTOMER.findFieldByName("region_id"), "region-" + (n % 2 == 1 ? 1 : 2))
                .setField(CUSTOMER.findFieldByName("referrer_id"), "cust-" + (n + 1))
                .build();
    }

    private static DynamicMessage region(String id) {
        return DynamicMessage.newBuilder(REGION)
                .setField(REGION.findFieldByName("id"), id)
                .setField(REGION.findFieldByName("name"), "Region " + id)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(DynamicMessage request) {
        return (List<String>) request.getField(IDS_REQUEST.findFieldByName("ids"));
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
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(IDS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(CUSTOMERS))
                .build();
        var regions = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rel.Shop/GetRegions")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(IDS_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(REGIONS))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("rel.Shop")
                .addMethod(list, ServerCalls.asyncUnaryCall((request, observer) -> {
                    int count = (Integer) request.getField(LIST_REQUEST.findFieldByName("count"));
                    DynamicMessage.Builder orders = DynamicMessage.newBuilder(ORDERS);
                    for (int n = 1; n <= count; n++) {
                        orders.addRepeatedField(
                                ORDERS.findFieldByName("items"),
                                DynamicMessage.newBuilder(ORDER)
                                        .setField(ORDER.findFieldByName("id"), "order-" + n)
                                        .setField(ORDER.findFieldByName("customer_id"), "cust-" + n)
                                        .setField(
                                                ORDER.findFieldByName("contact"),
                                                DynamicMessage.newBuilder(CONTACT)
                                                        .setField(CONTACT.findFieldByName("customer_id"), "cust-1")
                                                        .build())
                                        .build());
                    }
                    observer.onNext(orders.build());
                    observer.onCompleted();
                }))
                .addMethod(customers, ServerCalls.asyncUnaryCall((request, observer) -> {
                    List<String> ids = ids(request);
                    CUSTOMER_CALLS.add(List.copyOf(ids));
                    DynamicMessage.Builder reply = DynamicMessage.newBuilder(CUSTOMERS);
                    for (String id : ids) reply.addRepeatedField(CUSTOMERS.findFieldByName("items"), customer(id));
                    observer.onNext(reply.build());
                    observer.onCompleted();
                }))
                .addMethod(regions, ServerCalls.asyncUnaryCall((request, observer) -> {
                    List<String> ids = ids(request);
                    REGION_CALLS.add(List.copyOf(ids));
                    DynamicMessage.Builder reply = DynamicMessage.newBuilder(REGIONS);
                    for (String id : ids) reply.addRepeatedField(REGIONS.findFieldByName("items"), region(id));
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
