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
import org.junit.jupiter.api.AfterAll;
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
 * Two endpoints, {@code east} and {@code west}, both declare a loader for {@code Customer}. With
 * {@code data-loader.types.Customer.endpoint=west} an order's {@code customer} field is loaded
 * through west's loader, not east's. East serves the {@code orders} query: the pin decides the
 * loader only.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.data-loader.types.Customer.endpoint=west"
        })
@DisplayName("GraphQL loader ownership end to end (Boot 4)")
class GraphQlLoaderOwnershipEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor EAST = buildFile("east", true);
    private static final FileDescriptor WEST = buildFile("west", false);
    private static final Server BACKEND = startBackend();
    private static final Path PB_EAST = writeDescriptorSet(EAST);
    private static final Path PB_WEST = writeDescriptorSet(WEST);

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "east");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_EAST.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[1].name", () -> "west");
        registry.add("shiguchi.services[1].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[1].pb-descriptor-path", () -> PB_WEST.toString());
        registry.add("shiguchi.services[1].host", () -> "localhost");
        registry.add("shiguchi.services[1].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_EAST);
        Files.deleteIfExists(PB_WEST);
    }

    @Autowired
    private Environment environment;

    @Test
    @DisplayName(
            "GIVEN two endpoints with a Customer loader and a pin to west WHEN an order selects its customer SHOULD load it from west")
    void GIVEN_a_pin_to_west_WHEN_an_order_selects_its_customer_SHOULD_load_it_from_west() throws Exception {
        WebClient client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        Map<String, Object> body = Map.of("query", "{ orders { items { id customer { id name } } } }");

        String json = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        Struct.Builder response = Struct.newBuilder();
        JsonFormat.parser().merge(json, response);

        assertThat(response.containsFields("errors")).as(json).isFalse();
        List<Value> entities = response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("orders")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getListValue()
                .getValuesList();
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0)
                        .getStructValue()
                        .getFieldsOrThrow("customer")
                        .getStructValue()
                        .getFieldsOrThrow("name")
                        .getStringValue())
                .isEqualTo("west c1");
    }

    // ------------------------------------------------------------------ fixtures

    /** {@code <pkg>.Shop/GetCustomers(ids)} returns {@code Customers{items}}, the Customer loader, bound as query {@code <pkg>Customers}. */
    private static FileDescriptor buildFile(String pkg, boolean orders) {
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
            FileDescriptorProto.Builder builder = FileDescriptorProto.newBuilder()
                    .setName(pkg + "/shop.proto")
                    .setSyntax("proto3")
                    .setPackage(pkg)
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
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, "." + pkg + ".Customer")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Shop")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetCustomers")
                                    .setInputType("." + pkg + ".GetCustomersRequest")
                                    .setOutputType("." + pkg + ".Customers")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName(pkg + "Customers")
                                                            .setType(Op.QUERY)
                                                            .setLoaderFor("Customer")
                                                            .setLoaderIdsField("ids")
                                                            .setLoaderResultField("items")
                                                            .build()))));
            if (orders) {
                builder.addMessageType(DescriptorProto.newBuilder()
                                .setName("Order")
                                .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                                .addField(field("customer_id", 2, FieldDescriptorProto.Type.TYPE_STRING, null)
                                        .setJsonName("customerId")
                                        .setOptions(resolvesCustomer)))
                        .addMessageType(DescriptorProto.newBuilder().setName("ListRequest"))
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("Orders")
                                .addField(
                                        field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, "." + pkg + ".Order")
                                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)));
                builder.getServiceBuilder(0)
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("ListOrders")
                                .setInputType("." + pkg + ".ListRequest")
                                .setOutputType("." + pkg + ".Orders")
                                .setOptions(MethodOptions.newBuilder()
                                        .setExtension(
                                                GraphQlProto.graphql,
                                                GraphQlRule.newBuilder()
                                                        .setName("orders")
                                                        .setType(Op.QUERY)
                                                        .build())));
            }
            FileDescriptorProto proto = builder.build();
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

    private static Path writeDescriptorSet(FileDescriptor file) {
        try {
            Path path = Files.createTempFile("owner-shop", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(file.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Both services on one server; each answers with its own name so the test sees which one was called. */
    private static Server startBackend() {
        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(0);
            for (FileDescriptor file : List.of(EAST, WEST)) {
                String pkg = file.getPackage();
                Descriptor request = file.findMessageTypeByName("GetCustomersRequest");
                Descriptor customers = file.findMessageTypeByName("Customers");
                Descriptor customer = file.findMessageTypeByName("Customer");
                var method = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName(pkg + ".Shop/GetCustomers")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(request))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(customers))
                        .build();
                ServerServiceDefinition.Builder shop = ServerServiceDefinition.builder(pkg + ".Shop");
                if (pkg.equals("east")) {
                    Descriptor listRequest = file.findMessageTypeByName("ListRequest");
                    Descriptor orders = file.findMessageTypeByName("Orders");
                    Descriptor order = file.findMessageTypeByName("Order");
                    var listOrders = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                            .setType(MethodType.UNARY)
                            .setFullMethodName("east.Shop/ListOrders")
                            .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(listRequest))
                            .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(orders))
                            .build();
                    shop.addMethod(listOrders, ServerCalls.asyncUnaryCall((req, observer) -> {
                        observer.onNext(DynamicMessage.newBuilder(orders)
                                .addRepeatedField(
                                        orders.findFieldByName("items"),
                                        DynamicMessage.newBuilder(order)
                                                .setField(order.findFieldByName("id"), "o1")
                                                .setField(order.findFieldByName("customer_id"), "c1")
                                                .build())
                                .build());
                        observer.onCompleted();
                    }));
                }
                builder.addService(shop.addMethod(method, ServerCalls.asyncUnaryCall((req, observer) -> {
                            @SuppressWarnings("unchecked")
                            List<String> ids = (List<String>) req.getField(request.findFieldByName("ids"));
                            DynamicMessage.Builder reply = DynamicMessage.newBuilder(customers);
                            for (String id : ids) {
                                reply.addRepeatedField(
                                        customers.findFieldByName("items"),
                                        DynamicMessage.newBuilder(customer)
                                                .setField(customer.findFieldByName("id"), id)
                                                .setField(customer.findFieldByName("name"), pkg + " " + id)
                                                .build());
                            }
                            observer.onNext(reply.build());
                            observer.onCompleted();
                        }))
                        .build());
            }
            return builder.build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
