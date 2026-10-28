package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("Push delivery accounting")
class PushDeliveriesTest {

    private static final String ORDERS = "orders:1";
    private static final String TICKER = "ticker:1";

    private PushProperties properties;
    private MemoryPushStore store;
    private PushConnections connections;
    private PushDeliveries deliveries;

    @BeforeEach
    void setUp() {
        properties = new PushProperties();
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setDeliveryLog(true);
        properties.getNamespaces().put("orders", orders);
        properties.getNamespaces().put("ticker", new PushProperties.Namespace());
        store = new MemoryPushStore();
        connections = new PushConnections();
        deliveries = new PushDeliveries(properties, store);
        connections.setDeliveries(deliveries);
    }

    @AfterEach
    void tearDown() {
        deliveries.destroy();
    }

    @Test
    @DisplayName(
            "GIVEN a namespace without delivery-log WHEN an event is delivered twice and dropped once SHOULD report instance-scope counts and an empty log")
    void given_namespace_without_delivery_log_when_delivered_and_dropped_should_report_instance_counts() {
        PushEvent event = event(TICKER, "e1");
        PushConnections.Live first = connections.open("sse", null);
        PushConnections.Live second = connections.open("ws", null);

        first.delivered(event);
        second.delivered(event);
        second.dropped(event, "queue_full");

        Map<String, Object> report =
                deliveries.report(Topic.parse(TICKER), "e1", 100).block();
        assertThat(report)
                .containsEntry("delivered", 2L)
                .containsEntry("dropped", 1L)
                .containsEntry("logged", false)
                .containsEntry("scope", "instance")
                .containsEntry("log", List.of());
    }

    @Test
    @DisplayName(
            "GIVEN a logged namespace WHEN an event is delivered SHOULD write the delivery to the store and report cluster-scope counts with a log row")
    void given_logged_namespace_when_delivered_should_write_delivery_to_store() throws InterruptedException {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        live.delivered(event(ORDERS, "o1"));
        awaitRecorded(Topic.parse(ORDERS), "o1");

        Map<String, Object> report =
                deliveries.report(Topic.parse(ORDERS), "o1", 100).block();
        assertThat(report)
                .containsEntry("logged", true)
                .containsEntry("scope", "cluster")
                .containsEntry("delivered", 1L)
                .containsEntry("dropped", 0L);
        List<Map<String, Object>> rows = logRows(report);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("connectionId", live.id())
                .containsEntry("principal", "alice")
                .containsEntry("transport", "sse")
                .containsEntry("outcome", "delivered")
                .containsKey("time");
    }

    @Test
    @DisplayName(
            "GIVEN a logged namespace WHEN a drop with reason queue_full is recorded SHOULD count it as dropped and log the outcome dropped:queue_full")
    void given_logged_namespace_when_dropped_should_log_queue_full_outcome() throws InterruptedException {
        PushConnections.Live live = connections.open("ws", authenticated("bob"));

        live.dropped(event(ORDERS, "o2"), "queue_full");
        awaitRecorded(Topic.parse(ORDERS), "o2");

        Map<String, Object> report =
                deliveries.report(Topic.parse(ORDERS), "o2", 100).block();
        assertThat(report).containsEntry("delivered", 0L).containsEntry("dropped", 1L);
        List<Map<String, Object>> rows = logRows(report);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("outcome", "dropped:queue_full").containsEntry("principal", "bob");
    }

    @Test
    @DisplayName("GIVEN an event with a null id WHEN it is delivered or dropped SHOULD record nothing")
    void given_event_without_id_when_delivered_should_record_nothing() {
        PushConnections.Live live = connections.open("sse", null);

        live.delivered(event(TICKER, null));
        live.dropped(event(TICKER, null), "queue_full");

        assertThat(deliveries.recentEvents()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a namespace with delivery-log and a store that keeps no delivery log WHEN the delivery accounting starts SHOULD fail naming the namespace")
    void given_store_without_delivery_log_when_constructed_should_fail_naming_namespace() {
        PushStore noLog = new NoDeliveryLogStore();

        assertThatThrownBy(() -> new PushDeliveries(properties, noLog))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining("delivery-log");
    }

    @Test
    @DisplayName(
            "GIVEN an event nobody delivered WHEN its report is requested SHOULD return zero counts and logged false")
    void given_unknown_event_when_reported_should_return_zero_counts() {
        Map<String, Object> report =
                deliveries.report(Topic.parse(TICKER), "missing", 100).block();

        assertThat(report)
                .containsEntry("delivered", 0L)
                .containsEntry("dropped", 0L)
                .containsEntry("logged", false);
    }

    @Test
    @DisplayName(
            "GIVEN a connection whose registry has no delivery accounting WHEN it delivers or drops an event SHOULD not throw")
    void given_registry_without_deliveries_when_delivered_or_dropped_should_not_throw() {
        PushConnections bare = new PushConnections();
        PushConnections.Live live = bare.open("sse", null);
        PushEvent event = event(ORDERS, "o3");

        assertThatCode(() -> {
                    live.delivered(event);
                    live.dropped(event, "queue_full");
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "GIVEN deliveries with outcome delivered or dropped:queue_full WHEN asked whether delivered SHOULD be true only for delivered")
    void given_delivery_outcomes_when_asked_delivered_should_be_true_only_for_delivered() {
        assertThat(delivery("delivered").delivered()).isTrue();
        assertThat(delivery("dropped:queue_full").delivered()).isFalse();
    }

    private static PushDelivery delivery(String outcome) {
        return new PushDelivery(Topic.parse(ORDERS), "o1", "c1", "alice", "sse", Instant.now(), outcome);
    }

    private static PushEvent event(String topic, String id) {
        return new PushEvent(Topic.parse(topic), id, "{\"n\":1}".getBytes(StandardCharsets.UTF_8), null, null, null);
    }

    private static UsernamePasswordAuthenticationToken authenticated(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> logRows(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("log");
    }

    /** The writer batches with a 500 ms timeout; wait for the batch to reach the store. */
    private void awaitRecorded(Topic topic, String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (store.deliveryCounts(topic, id).block().isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no delivery recorded for " + topic + "|" + id);
            }
            Thread.sleep(20);
        }
    }

    /** A store that keeps no delivery log, as the default {@link PushStore} does. */
    private static final class NoDeliveryLogStore implements PushStore {
        @Override
        public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
            return Flux.empty();
        }

        @Override
        public int compareIds(String a, String b) {
            return a.compareTo(b);
        }

        @Override
        public long historyLength(Topic topic) {
            return 0;
        }

        @Override
        public PushStore.Capabilities capabilities() {
            return new PushStore.Capabilities(false, false);
        }
    }
}
