package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

@DisplayName("gRPC stream push sources: mapping a served server stream into events")
class GrpcStreamPushSourceTest {

    private static final FileDescriptor ORDERS = fileOf();
    private static final Descriptor ORDER_EVENT = ORDERS.findMessageTypeByName("OrderEvent");
    private static final Topic ORDERS_42 = Topic.parse("orders:42");

    private final PushDlq dlq = new PushDlq(100);
    private final GrpcRouteRegistry registry = registryOf(ORDERS);

    @Test
    @DisplayName(
            "GIVEN a stream message with order_id 42 WHEN the source maps events SHOULD emit it on orders:42 keyed by 42 as the typed OrderEvent")
    void given_orderMessage_when_eventsMapped_should_emitTopicKeyTypeAndProto() {
        DynamicMessage paid = orderEvent("42", "paid");
        GrpcStreamPushSource source = sourceOver(paid);

        List<SourcedEvent> events = source.events().collectList().block();

        assertThat(events).hasSize(1);
        SourcedEvent event = events.get(0);
        assertThat(event.topic()).isEqualTo(ORDERS_42);
        assertThat(event.key()).isEqualTo("42");
        assertThat(event.typeUrl()).isEqualTo("type.googleapis.com/ev.OrderEvent");
        assertThat(event.proto()).isEqualTo(paid.toByteArray());
    }

    @Test
    @DisplayName(
            "GIVEN a stream message WHEN the source maps it SHOULD tag it with the grpc_method attribute and carry its fields as JSON")
    void given_orderMessage_when_eventMapped_should_carryMethodAttributeAndJsonFields() {
        GrpcStreamPushSource source = sourceOver(orderEvent("42", "paid"));

        SourcedEvent event = source.events().blockFirst();

        assertThat(event.attributes()).containsEntry("grpc_method", "ev.Orders/Events");
        assertThat(new String(event.data(), StandardCharsets.UTF_8)).contains("paid");
    }

    @Test
    @DisplayName(
            "GIVEN a stream message with an empty order_id WHEN the source maps it SHOULD dead-letter it with a topic reason and emit nothing")
    void given_emptyOrderId_when_eventsMapped_should_deadLetterAndEmitNothing() {
        GrpcStreamPushSource source = sourceOver(orderEvent("", "paid"));

        List<SourcedEvent> events = source.events().collectList().block();

        assertThat(events).isEmpty();
        assertThat(dlq.size()).isEqualTo(1);
        PushDlq.Entry entry = dlq.list(null, 10).get(0);
        assertThat(entry.reason()).startsWith("topic:");
        assertThat(entry.source()).isEqualTo("orders-stream");
    }

    @Test
    @DisplayName(
            "GIVEN a served server-streaming method WHEN the payload type is asked SHOULD name the response message")
    void given_servedStreamingMethod_when_payloadTypeAsked_should_nameResponseMessage() {
        GrpcStreamPushSource source = sourceOver();

        assertThat(source.payloadType()).isEqualTo("ev.OrderEvent");
    }

    @Test
    @DisplayName("GIVEN a gRPC stream source WHEN described SHOULD name its kind and the method it reads")
    void given_grpcStreamSource_when_described_should_mentionKindAndMethod() {
        GrpcStreamPushSource source = sourceOver();

        assertThat(source.describe()).contains("GRPC_STREAM").contains("ev.Orders/Events");
    }

    @Test
    @DisplayName("GIVEN a service the gateway does not serve WHEN the source is built SHOULD refuse it")
    void given_unknownService_when_built_should_reject() {
        PushProperties.Source config = config("ev.Missing", "Events", "");

        assertThatThrownBy(() -> newSource(config, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ev.Missing");
    }

    @Test
    @DisplayName("GIVEN a unary method WHEN the source is built SHOULD refuse it, a unary call cannot feed a stream")
    void given_unaryMethod_when_built_should_rejectNamingServerStreaming() {
        PushProperties.Source config = config("ev.Orders", "Get", "");

        assertThatThrownBy(() -> newSource(config, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server-streaming");
    }

    @Test
    @DisplayName(
            "GIVEN a request that is not valid JSON for the input message WHEN the source is built SHOULD refuse it")
    void given_invalidRequestJson_when_built_should_reject() {
        PushProperties.Source config = config("ev.Orders", "Events", "{not json");

        assertThatThrownBy(() -> newSource(config, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grpc.request");
    }

    // ---- helpers ----

    private GrpcStreamPushSource sourceOver(DynamicMessage... messages) {
        return newSource(config("ev.Orders", "Events", ""), List.of(messages));
    }

    private GrpcStreamPushSource newSource(PushProperties.Source config, List<DynamicMessage> messages) {
        return new GrpcStreamPushSource(
                "orders-stream", config, registry, (ctx, service, method, request) -> Flux.fromIterable(messages), dlq);
    }

    private static PushProperties.Source config(String service, String method, String request) {
        PushProperties.Source config = new PushProperties.Source();
        config.setType(PushProperties.SourceType.GRPC_STREAM);
        config.setTopicTemplate("orders:{order_id}");
        config.setKeyField("order_id");
        config.getGrpc().setService(service);
        config.getGrpc().setMethod(method);
        config.getGrpc().setRequest(request);
        return config;
    }

    private static DynamicMessage orderEvent(String orderId, String status) {
        return DynamicMessage.newBuilder(ORDER_EVENT)
                .setField(ORDER_EVENT.findFieldByName("order_id"), orderId)
                .setField(ORDER_EVENT.findFieldByName("status"), status)
                .build();
    }

    private static GrpcRouteRegistry registryOf(FileDescriptor file) {
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("orders");
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);
        context.setFileDescriptors(Map.of("ev/orders.proto", file));
        java.util.Map<String, com.google.protobuf.Descriptors.ServiceDescriptor> services = new java.util.HashMap<>();
        java.util.Map<String, com.google.protobuf.Descriptors.MethodDescriptor> methods = new java.util.HashMap<>();
        for (com.google.protobuf.Descriptors.ServiceDescriptor s : file.getServices()) {
            services.put(s.getFullName(), s);
            for (com.google.protobuf.Descriptors.MethodDescriptor m : s.getMethods()) {
                methods.put(s.getFullName() + "/" + m.getName(), m);
            }
        }
        context.setServiceByFqn(services);
        context.setMethodByKey(methods);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context);
        return registry;
    }

    /** One server-streaming {@code Events} and one unary {@code Get}, both over the same messages. */
    private static FileDescriptor fileOf() {
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("Orders")
                .addMethod(method("Events", true))
                .addMethod(method("Get", false))
                .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("ev/orders.proto")
                .setSyntax("proto3")
                .setPackage("ev")
                .addMessageType(DescriptorProto.newBuilder().setName("EventsRequest"))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("OrderEvent")
                        .addField(field("order_id", 1))
                        .addField(field("status", 2)))
                .addService(service)
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {});
        } catch (Descriptors.DescriptorValidationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto method(String name, boolean serverStreaming) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".ev.EventsRequest")
                .setOutputType(".ev.OrderEvent")
                .setServerStreaming(serverStreaming)
                .build();
    }

    private static FieldDescriptorProto.Builder field(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
