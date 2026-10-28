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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@DisplayName("Upstream-backed topics: event_id_field")
class PushUpstreamsEventIdTest {

    private static final Topic TOPIC = Topic.parse("ticks:a");

    private final PushProperties properties = new PushProperties();
    private final MemoryPushStore store = new MemoryPushStore();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PushMetrics metrics = new PushMetrics(registry);
    private final List<Disposable> subscriptions = new ArrayList<>();
    private final Sink sink = new Sink();
    private PushUpstreams upstreams;

    PushUpstreamsEventIdTest() {
        PushProperties.Namespace ticks = new PushProperties.Namespace();
        ticks.setHistorySize(100);
        properties.getNamespaces().put("ticks", ticks);
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
            "GIVEN an int64 event_id_field WHEN ticks arrive SHOULD publish them under the field value as event id")
    void given_int64Field_when_ticksArrive_should_publishUnderFieldValue() throws Exception {
        List<PushEvent> received = start("seq");

        sink.emit(tick(5, null, 0));
        sink.emit(tick(6, null, 0));
        awaitTrue(() -> received.size() == 2);

        assertThat(received).extracting(PushEvent::id).containsExactly(id(5), id(6));
        assertThat(received.get(0).attributes()).containsEntry("upstream.id", "5");
    }

    @Test
    @DisplayName("GIVEN a string field holding a decimal WHEN a tick arrives SHOULD use the parsed value")
    void given_stringDecimalField_when_tickArrives_should_useParsedValue() throws Exception {
        List<PushEvent> received = start("sid");

        sink.emit(tick(0, "17", 0));
        awaitTrue(() -> received.size() == 1);

        assertThat(received.get(0).id()).isEqualTo(id(17));
    }

    @Test
    @DisplayName("GIVEN a nested path meta.sequence WHEN a tick arrives SHOULD read the nested uint64")
    void given_nestedPath_when_tickArrives_should_readNestedValue() throws Exception {
        List<PushEvent> received = start("meta.sequence");

        sink.emit(tick(0, null, 31));
        awaitTrue(() -> received.size() == 1);

        assertThat(received.get(0).id()).isEqualTo(id(31));
    }

    @Test
    @DisplayName(
            "GIVEN a tick whose id is zero WHEN published SHOULD get an engine id, no upstream.id attribute and a missing count")
    void given_zeroId_when_published_should_getEngineIdAndCountMissing() throws Exception {
        List<PushEvent> received = start("seq");

        sink.emit(tick(0, null, 0));
        awaitTrue(() -> received.size() == 1);

        assertThat(received.get(0).id()).isNotEqualTo(id(0));
        assertThat(received.get(0).attributes()).doesNotContainKey("upstream.id");
        assertThat(registry.find("shiguchi.cursus.upstream.ids")
                        .tag("outcome", "missing")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN the previous owner published up to 10 WHEN this owner's stream replays 9, 10 and 11 SHOULD publish only 11")
    void given_headAlreadyPublished_when_streamReplays_should_publishOnlyNewer() throws Exception {
        store.appendWithId(
                        new PushEvent(TOPIC, null, "{}".getBytes(), Map.of("upstream.id", "10"), null, null),
                        10,
                        100,
                        Duration.ofHours(1))
                .block();
        List<PushEvent> received = start("seq");

        sink.emit(tick(9, null, 0));
        sink.emit(tick(10, null, 0));
        sink.emit(tick(11, null, 0));
        awaitTrue(() -> received.size() >= 1);
        Thread.sleep(100);

        assertThat(received).extracting(PushEvent::id).containsExactly(id(11));
        assertThat(store.historyLength(TOPIC)).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN event_id_field naming a bool, an unknown field or a repeated path step WHEN built SHOULD refuse with a clear message")
    void given_invalidField_when_built_should_refuseWithMessage() {
        for (String bad : new String[] {"flag", "nope", "meta.nope", "seq.x"}) {
            assertThatThrownBy(() -> upstreamsOver(fileOf(bad)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("event_id_field '" + bad + "'");
        }
    }

    // ---- fixtures ----

    private static String id(long n) {
        return MemoryPushStore.formatId(n);
    }

    private List<PushEvent> start(String path) throws InterruptedException {
        upstreams = upstreamsOver(fileOf(path));
        List<PushEvent> received = new CopyOnWriteArrayList<>();
        subscriptions.add(upstreams.attach(TOPIC, broker.subscribe(TOPIC)).subscribe(received::add));
        awaitTrue(() -> sink.current.get() != null);
        return received;
    }

    private PushUpstreams upstreamsOver(FileDescriptor file) {
        ServiceDefinition config = new ServiceDefinition();
        config.setName("ticks");
        ServiceEndpointContext context = new ServiceEndpointContext(config, null);
        context.setFileDescriptors(Map.of("ev/ticks.proto", file));
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context);
        return new PushUpstreams(
                properties, registry, store, new PushPublisher(properties, store, broker, metrics), metrics, sink);
    }

    private DynamicMessage tick(long seq, String sid, long metaSequence) {
        Descriptor tick = upstreams.upstreams().get(0).output();
        Descriptor meta = tick.findFieldByName("meta").getMessageType();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(tick);
        if (seq != 0) b.setField(tick.findFieldByName("seq"), seq);
        if (sid != null) b.setField(tick.findFieldByName("sid"), sid);
        if (metaSequence != 0) {
            b.setField(
                    tick.findFieldByName("meta"),
                    DynamicMessage.newBuilder(meta)
                            .setField(meta.findFieldByName("sequence"), metaSequence)
                            .build());
        }
        return b.build();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    private static FileDescriptor fileOf(String eventIdField) {
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("Ticks")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("Watch")
                        .setInputType(".ev.WatchRequest")
                        .setOutputType(".ev.Tick")
                        .setServerStreaming(true)
                        .setOptions(MethodOptions.newBuilder()
                                .setExtension(
                                        TopicProto.topic,
                                        UpstreamTopic.newBuilder()
                                                .setPattern("ticks:{name}")
                                                .setEventIdField(eventIdField)
                                                .build())))
                .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("ev/ticks.proto")
                .setSyntax("proto3")
                .setPackage("ev")
                .addDependency(TopicProto.getDescriptor().getName())
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("WatchRequest")
                        .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Meta")
                        .addField(field("sequence", 1, FieldDescriptorProto.Type.TYPE_UINT64, null)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Tick")
                        .addField(field("seq", 1, FieldDescriptorProto.Type.TYPE_INT64, null))
                        .addField(field("sid", 2, FieldDescriptorProto.Type.TYPE_STRING, null))
                        .addField(field("meta", 3, FieldDescriptorProto.Type.TYPE_MESSAGE, ".ev.Meta"))
                        .addField(field("flag", 4, FieldDescriptorProto.Type.TYPE_BOOL, null)))
                .addService(service)
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {TopicProto.getDescriptor()});
        } catch (Descriptors.DescriptorValidationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(
            String name, int number, FieldDescriptorProto.Type type, String typeName) {
        FieldDescriptorProto.Builder f = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
        if (typeName != null) f.setTypeName(typeName);
        return f;
    }

    /** Hands out a flux the test feeds. */
    static final class Sink implements PushUpstreams.Opener {
        final AtomicReference<Sinks.Many<DynamicMessage>> current = new AtomicReference<>();

        @Override
        public Flux<DynamicMessage> open(PushUpstreams.Upstream upstream, DynamicMessage request) {
            Sinks.Many<DynamicMessage> sink = Sinks.many().multicast().onBackpressureBuffer();
            current.set(sink);
            return sink.asFlux();
        }

        void emit(DynamicMessage message) {
            current.get().tryEmitNext(message);
        }
    }
}
