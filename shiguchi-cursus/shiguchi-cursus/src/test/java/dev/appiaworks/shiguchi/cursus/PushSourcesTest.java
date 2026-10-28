package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@DisplayName("Push sources: consuming, leasing, dead-lettering and retrying")
class PushSourcesTest {

    private static final Topic ORDERS_1 = Topic.parse("orders:1");

    private final PushProperties props = new PushProperties();
    private final MemoryPushStore store = new MemoryPushStore();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushPublisher publisher = new PushPublisher(props, store, broker, metrics);
    private final PushDlq dlq = new PushDlq(100);
    private final List<Disposable> subscriptions = new ArrayList<>();
    private final List<PushSources> built = new ArrayList<>();

    PushSourcesTest() {
        props.getNamespaces().put("orders", new PushProperties.Namespace());
        props.getUpstream().setLeaseTtl(Duration.ofSeconds(2));
        props.getUpstream().setRetryBackoff(Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() {
        built.forEach(PushSources::stop);
        subscriptions.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName(
            "GIVEN a running source WHEN it emits an event SHOULD publish it into the topic with source attributes and report this instance as owner")
    void given_runningSource_when_eventEmitted_should_publishIntoTopicAndReportOwner() {
        FakeSource source = new FakeSource("fake", true, false);
        PushSources sources = sourcesOf("gw-1", source);
        List<PushEvent> received = subscribe(ORDERS_1);

        sources.start();
        source.emit(SourcedEvent.json(ORDERS_1, bytes("{\"n\":1}"), Map.of(), "k"));

        await(() -> received.size() == 1 && publishedOf(sources) == 1);
        PushEvent event = received.get(0);
        assertThat(event.attributes()).containsEntry("from", "source").containsEntry("source", "fake");
        assertThat(event.key()).isEqualTo("k");
        Map<String, Object> row = sources.status().get(0);
        assertThat(row).containsEntry("name", "fake").containsEntry("state", "owner");
        assertThat(row).containsEntry("published", 1L);
        assertThat(sources.owned()).containsExactly("fake");
        assertThat(metrics.sources()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN an event on an undeclared namespace WHEN the source emits it SHOULD dead-letter it, count it as failed and keep consuming")
    void given_undeclaredNamespace_when_eventEmitted_should_deadLetterAndKeepRunning() {
        FakeSource source = new FakeSource("fake", true, false);
        PushSources sources = sourcesOf("gw-1", source);
        List<PushEvent> received = subscribe(ORDERS_1);
        sources.start();

        source.emit(SourcedEvent.json(Topic.parse("nope:1"), bytes("{}"), Map.of(), null));
        await(() -> dlq.size() == 1);

        PushDlq.Entry entry = dlq.list(null, 10).get(0);
        assertThat(entry.source()).isEqualTo("fake");
        assertThat(entry.reason()).startsWith("unknown_namespace");
        assertThat(sources.status().get(0)).containsEntry("failed", 1L);

        source.emit(SourcedEvent.json(ORDERS_1, bytes("{}"), Map.of(), null));
        await(() -> received.size() == 1);
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a running owner WHEN the gateway stops SHOULD drop ownership, the source metric and the lease")
    void given_runningOwner_when_stopped_should_releaseLeaseAndDropOwnership() {
        PushSources sources = sourcesOf("gw-1", new FakeSource("fake", true, false));
        sources.start();
        assertThat(sources.owned()).containsExactly("fake");

        sources.stop();

        assertThat(sources.owned()).isEmpty();
        assertThat(metrics.sources()).isZero();
        assertThat(store.lease("source:fake", "other", Duration.ofSeconds(1)).block())
                .isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN another instance owns a shared source WHEN this instance starts SHOULD wait, then take over after the owner stops")
    void given_sourceOwnedElsewhere_when_started_should_waitThenTakeOverAfterOwnerStops() throws InterruptedException {
        PushSources owner = sourcesOf("gw-1", new FakeSource("fake", true, false));
        owner.start();
        FakeSource standbySource = new FakeSource("fake", true, false);
        PushSources standby = sourcesOf("gw-2", standbySource);
        standby.start();

        Thread.sleep(300);
        assertThat(standby.status().get(0)).containsEntry("state", "waiting");
        assertThat(standbySource.subscriptions.get()).isZero();

        owner.stop();
        await(() -> standby.owned().contains("fake"), 5000);
        assertThat(standby.status().get(0)).containsEntry("state", "owner");
    }

    @Test
    @DisplayName(
            "GIVEN a source that is not shared WHEN two instances start SHOULD have both consume it instead of one")
    void given_notSharedSource_when_twoInstancesStart_should_bothConsume() {
        PushSources first = sourcesOf("gw-1", new FakeSource("fake", false, false));
        PushSources second = sourcesOf("gw-2", new FakeSource("fake", false, false));

        first.start();
        second.start();

        await(() -> first.owned().contains("fake") && second.owned().contains("fake"));
        assertThat(first.status().get(0)).containsEntry("shared", false).containsEntry("state", "owner");
        assertThat(second.status().get(0)).containsEntry("shared", false).containsEntry("state", "owner");
    }

    @Test
    @DisplayName(
            "GIVEN a source whose first stream fails WHEN it is reopened and emits SHOULD retry after the backoff, keep publishing and report the last error")
    void given_firstStreamFails_when_reopened_should_retryPublishAndReportLastError() {
        FakeSource source = new FakeSource("fake", true, true);
        PushSources sources = sourcesOf("gw-1", source);
        List<PushEvent> received = subscribe(ORDERS_1);
        sources.start();

        await(() -> source.subscriptions.get() >= 2, 2000);
        source.emit(SourcedEvent.json(ORDERS_1, bytes("{}"), Map.of(), null));

        await(() -> received.size() == 1 && publishedOf(sources) == 1, 2000);
        assertThat(received).hasSize(1);
        assertThat(sources.status().get(0).get("lastError")).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN two sources with the same name WHEN the sources are built SHOULD refuse them naming the duplicate")
    void given_twoSourcesWithSameName_when_built_should_reject() {
        List<PushSource> sources = List.of(new FakeSource("fake", true, false), new FakeSource("fake", true, false));

        assertThatThrownBy(() -> new PushSources(props, store, publisher, metrics, dlq, sources, "gw-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'fake'");
    }

    // ---- helpers ----

    private PushSources sourcesOf(String holder, PushSource source) {
        PushSources sources = new PushSources(props, store, publisher, metrics, dlq, List.of(source), holder);
        built.add(sources);
        return sources;
    }

    private List<PushEvent> subscribe(Topic topic) {
        List<PushEvent> received = new CopyOnWriteArrayList<>();
        subscriptions.add(broker.subscribe(topic).subscribe(received::add));
        return received;
    }

    private static long publishedOf(PushSources sources) {
        return (Long) sources.status().get(0).get("published");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition) {
        await(condition, 5000);
    }

    private static void await(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** A source the test feeds; its first subscription fails when asked to. */
    static final class FakeSource implements PushSource {
        private final String name;
        private final boolean shared;
        private final boolean failFirst;
        final AtomicInteger subscriptions = new AtomicInteger();
        private final Sinks.Many<SourcedEvent> stream = Sinks.many().multicast().onBackpressureBuffer();

        FakeSource(String name, boolean shared, boolean failFirst) {
            this.name = name;
            this.shared = shared;
            this.failFirst = failFirst;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean shared() {
            return shared;
        }

        @Override
        public Flux<SourcedEvent> events() {
            if (subscriptions.incrementAndGet() == 1 && failFirst) {
                return Flux.error(new IllegalStateException("stream unavailable"));
            }
            return stream.asFlux();
        }

        void emit(SourcedEvent event) {
            stream.tryEmitNext(event);
        }
    }
}
