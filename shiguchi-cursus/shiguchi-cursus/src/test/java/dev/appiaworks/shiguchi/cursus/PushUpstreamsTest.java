package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.api.TopicProto;
import dev.appiaworks.shiguchi.proto.api.UpstreamTopic;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@DisplayName("Upstream-backed topics")
class PushUpstreamsTest {

    private static final FileDescriptor ORDERS =
            fileOf(method("Watch", true, "orders:{order_id}"), method("Get", false, null));
    private static final Descriptor WATCH_REQUEST = ORDERS.findMessageTypeByName("WatchRequest");
    private static final Descriptor ORDER_EVENT = ORDERS.findMessageTypeByName("OrderEvent");
    private static final Topic TOPIC = Topic.parse("orders:42");

    private final PushProperties properties = new PushProperties();
    private final MemoryPushStore store = new MemoryPushStore();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final FakeOpener opener = new FakeOpener(false);
    private final List<Disposable> subscriptions = new ArrayList<>();
    private PushUpstreams upstreams;

    PushUpstreamsTest() {
        properties.getUpstream().setIdleLinger(Duration.ofMillis(100));
        properties.getUpstream().setLeaseTtl(Duration.ofSeconds(1));
        properties.getUpstream().setRetryBackoff(Duration.ofMillis(50));
        properties.getUpstream().setInstanceId("gw-a");
    }

    @AfterEach
    void tearDown() {
        subscriptions.forEach(Disposable::dispose);
        if (upstreams != null) upstreams.destroy();
    }

    @Test
    @DisplayName(
            "GIVEN a method declared on orders:{order_id} WHEN upstreams are built SHOULD declare the orders namespace with the response as payload type")
    void given_declaredMethod_when_built_should_declareNamespaceWithResponseType() {
        upstreams = upstreamsOver(ORDERS, opener);

        PushProperties.Namespace orders = properties.getNamespaces().get("orders");
        assertThat(orders).isNotNull();
        assertThat(orders.getPayloadType()).isEqualTo("ev.OrderEvent");
    }

    @Test
    @DisplayName("GIVEN a declared pattern WHEN looking up orders:42 and ticker:x SHOULD match the orders method only")
    void given_declaredPattern_when_lookingUpTopics_should_matchOnlyItsTopics() {
        upstreams = upstreamsOver(ORDERS, opener);

        assertThat(upstreams.upstreamFor(TOPIC)).isNotNull();
        assertThat(upstreams.upstreamFor(TOPIC).method()).isEqualTo("ev.Orders/Watch");
        assertThat(upstreams.upstreamFor(Topic.parse("ticker:x"))).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN an int32 variable WHEN the request is built from the topic SHOULD parse the value into the field")
    void given_intVariable_when_requestBuilt_should_parseValueIntoField() {
        PushUpstreams.Upstream byShard = new PushUpstreams.Upstream(
                TopicTemplate.parse("orders:{shard}"), null, "ev.Orders", "WatchShard", WATCH_REQUEST, ORDER_EVENT);

        DynamicMessage request = PushUpstreams.request(byShard, Topic.parse("orders:7"));

        assertThat(request.getField(WATCH_REQUEST.findFieldByName("shard"))).isEqualTo(7);
    }

    @Test
    @DisplayName(
            "GIVEN two subscribers of orders:42 WHEN the first attaches SHOULD open the stream once with order_id 42 and both receive the typed events")
    void given_twoSubscribers_when_upstreamEmits_should_openOnceAndDeliverTypedEvents() throws InterruptedException {
        upstreams = upstreamsOver(ORDERS, opener);
        List<PushEvent> first = new ArrayList<>();
        List<PushEvent> second = new ArrayList<>();

        subscribeTo(TOPIC, first);
        subscribeTo(TOPIC, second);
        awaitTrue(() -> opener.requests.size() >= 1);

        assertThat(opener.requests).hasSize(1);
        assertThat(opener.requests.get(0).getField(WATCH_REQUEST.findFieldByName("order_id")))
                .isEqualTo("42");
        assertThat(upstreams.status(TOPIC)).isEqualTo("owner");
        assertThat(metrics.upstreams()).isEqualTo(1);

        opener.emit(orderEvent("42", "PAID"));
        awaitTrue(() -> first.size() == 1 && second.size() == 1);

        for (List<PushEvent> received : List.of(first, second)) {
            PushEvent event = received.get(0);
            assertThat(event.typed()).isTrue();
            assertThat(event.typeUrl()).isEqualTo("type.googleapis.com/ev.OrderEvent");
            assertThat(event.attributes()).containsEntry("from", "upstream");
            assertThat(new String(event.data(), StandardCharsets.UTF_8)).contains("\"orderId\":\"42\"");
        }
    }

    @Test
    @DisplayName(
            "GIVEN the last subscriber of an owned topic WHEN it leaves SHOULD close the stream after idle-linger and release the metric")
    void given_lastSubscriberLeaves_when_idleLingerPasses_should_closeStream() throws InterruptedException {
        upstreams = upstreamsOver(ORDERS, opener);
        Disposable first = subscribeTo(TOPIC, new ArrayList<>());
        Disposable second = subscribeTo(TOPIC, new ArrayList<>());
        awaitTrue(() -> opener.requests.size() >= 1);

        first.dispose();
        second.dispose();

        awaitTrue(() -> opener.cancelled.get() == 1 && metrics.upstreams() == 0 && upstreams.status(TOPIC) == null);
        assertThat(opener.cancelled.get()).isEqualTo(1);
        assertThat(upstreams.status(TOPIC)).isNull();
        assertThat(metrics.upstreams()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN another gateway holds the topic's lease WHEN a subscriber attaches SHOULD wait and never open the upstream")
    void given_leaseHeldElsewhere_when_subscriberAttaches_should_waitWithoutOpening() throws InterruptedException {
        store.lease("upstream:orders:42", "other-gateway", Duration.ofMinutes(1))
                .block();
        upstreams = upstreamsOver(ORDERS, opener);

        subscribeTo(TOPIC, new ArrayList<>());

        assertThat(upstreams.status(TOPIC)).isEqualTo("waiting");
        Thread.sleep(200); // the lease is held for a minute: no retry may open the stream meanwhile
        assertThat(opener.requests).isEmpty();
        assertThat(metrics.upstreams()).isZero();
    }

    @Test
    @DisplayName("GIVEN the upstream fails on open WHEN a subscriber is attached SHOULD reopen it after the backoff")
    void given_upstreamFailsOnOpen_when_subscriberAttached_should_reopenAfterBackoff() throws InterruptedException {
        FakeOpener failing = new FakeOpener(true);
        upstreams = upstreamsOver(ORDERS, failing);

        subscribeTo(TOPIC, new ArrayList<>());

        awaitTrue(() -> failing.requests.size() >= 2);
        assertThat(failing.requests).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN a unary method carrying the topic option WHEN upstreams are built SHOULD reject it, a unary call cannot feed a topic")
    void given_unaryMethodWithTopic_when_built_should_rejectNamingServerStreaming() {
        assertThatThrownBy(() -> upstreamsOver(fileOf(method("Watch", false, "orders:{order_id}")), opener))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server-streaming");
    }

    @Test
    @DisplayName("GIVEN a pattern naming an unknown field or a pattern declared twice WHEN built SHOULD reject it")
    void given_unknownFieldOrDuplicatePattern_when_built_should_reject() {
        assertThatThrownBy(() -> upstreamsOver(fileOf(method("Watch", true, "orders:{nope}")), opener))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{nope}");
        assertThatThrownBy(() -> upstreamsOver(
                        fileOf(method("Watch", true, "orders:{order_id}"), method("Again", true, "orders:{order_id}")),
                        opener))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twice");
    }

    // ---- helpers ----

    private PushUpstreams upstreamsOver(FileDescriptor file, FakeOpener fake) {
        ServiceDefinition config = new ServiceDefinition();
        config.setName("orders");
        ServiceEndpointContext context = new ServiceEndpointContext(config, null);
        context.setFileDescriptors(Map.of("ev/orders.proto", file));
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context);
        return new PushUpstreams(
                properties, registry, store, new PushPublisher(properties, store, broker, metrics), metrics, fake);
    }

    private Disposable subscribeTo(Topic topic, List<PushEvent> into) {
        Disposable subscription =
                upstreams.attach(topic, broker.subscribe(topic)).subscribe(into::add);
        subscriptions.add(subscription);
        return subscription;
    }

    private static DynamicMessage orderEvent(String orderId, String status) {
        return DynamicMessage.newBuilder(ORDER_EVENT)
                .setField(ORDER_EVENT.findFieldByName("order_id"), orderId)
                .setField(ORDER_EVENT.findFieldByName("status"), status)
                .build();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    private static MethodDescriptorProto method(String name, boolean serverStreaming, String pattern) {
        MethodDescriptorProto.Builder method = MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".ev.WatchRequest")
                .setOutputType(".ev.OrderEvent")
                .setServerStreaming(serverStreaming);
        if (pattern != null) {
            method.setOptions(MethodOptions.newBuilder()
                    .setExtension(
                            TopicProto.topic,
                            UpstreamTopic.newBuilder().setPattern(pattern).build()));
        }
        return method.build();
    }

    private static FileDescriptor fileOf(MethodDescriptorProto... methods) {
        ServiceDescriptorProto.Builder service =
                ServiceDescriptorProto.newBuilder().setName("Orders");
        for (MethodDescriptorProto method : methods) service.addMethod(method);
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("ev/orders.proto")
                .setSyntax("proto3")
                .setPackage("ev")
                .addDependency(TopicProto.getDescriptor().getName())
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("WatchRequest")
                        .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("shard", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("OrderEvent")
                        .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("status", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                .addService(service)
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {TopicProto.getDescriptor()});
        } catch (Descriptors.DescriptorValidationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    /** Records each open and hands out a flux the test feeds; cancellations are counted. */
    static final class FakeOpener implements PushUpstreams.Opener {
        private final boolean failFirst;
        final List<DynamicMessage> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger cancelled = new AtomicInteger();
        private final AtomicReference<Sinks.Many<DynamicMessage>> current = new AtomicReference<>();

        FakeOpener(boolean failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public Flux<DynamicMessage> open(PushUpstreams.Upstream upstream, DynamicMessage request) {
            requests.add(request);
            if (failFirst && requests.size() == 1) {
                return Flux.error(new IllegalStateException("upstream unavailable"));
            }
            Sinks.Many<DynamicMessage> sink = Sinks.many().multicast().onBackpressureBuffer();
            current.set(sink);
            return sink.asFlux().doOnCancel(cancelled::incrementAndGet);
        }

        void emit(DynamicMessage message) {
            current.get().tryEmitNext(message);
        }
    }
}
