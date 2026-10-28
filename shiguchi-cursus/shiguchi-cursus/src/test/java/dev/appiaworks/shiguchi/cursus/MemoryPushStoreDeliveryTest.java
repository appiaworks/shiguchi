package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory push store delivery log")
class MemoryPushStoreDeliveryTest {

    private static final Topic ORDERS = Topic.parse("orders:1");
    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

    private final MemoryPushStore store = new MemoryPushStore();

    private static PushDelivery delivery(String eventId, String connectionId, String outcome) {
        return new PushDelivery(ORDERS, eventId, connectionId, null, "sse", T0, outcome);
    }

    private List<PushDelivery> listed(String eventId, int limit) {
        return store.deliveries(ORDERS, eventId, limit).collectList().block();
    }

    private Map<String, Long> counted(String eventId) {
        return store.deliveryCounts(ORDERS, eventId).block();
    }

    @Test
    @DisplayName("GIVEN the in-memory store WHEN asked whether it keeps a delivery log SHOULD answer true")
    void given_memoryStore_when_deliveryLogAsked_should_answerTrue() {
        assertThat(store.deliveryLog()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN three deliveries for one event and one for another WHEN listed SHOULD return the three oldest first")
    void given_threeDeliveriesForEvent_when_listed_should_returnInInsertionOrder() {
        store.recordDeliveries(List.of(
                        delivery("7", "c1", "delivered"),
                        delivery("7", "c2", "delivered"),
                        delivery("7", "c3", "dropped:queue_full")))
                .block();
        store.recordDeliveries(List.of(delivery("8", "other", "delivered"))).block();

        List<PushDelivery> found = listed("7", 10);

        assertThat(found).extracting(PushDelivery::connectionId).containsExactly("c1", "c2", "c3");
        assertThat(found).allMatch(d -> d.eventId().equals("7"));
    }

    @Test
    @DisplayName("GIVEN three deliveries for one event WHEN listed with limit 2 SHOULD return the two oldest")
    void given_threeDeliveriesForEvent_when_listedWithLimitTwo_should_returnTwo() {
        store.recordDeliveries(List.of(
                        delivery("7", "c1", "delivered"),
                        delivery("7", "c2", "delivered"),
                        delivery("7", "c3", "dropped:queue_full")))
                .block();

        assertThat(listed("7", 2)).extracting(PushDelivery::connectionId).containsExactly("c1", "c2");
    }

    @Test
    @DisplayName("GIVEN two delivered and one dropped outcome WHEN counted SHOULD group them per outcome")
    void given_mixedOutcomes_when_counted_should_groupPerOutcome() {
        store.recordDeliveries(List.of(
                        delivery("7", "c1", "delivered"),
                        delivery("7", "c2", "delivered"),
                        delivery("7", "c3", "dropped:queue_full")))
                .block();

        assertThat(counted("7")).containsOnly(Map.entry("delivered", 2L), Map.entry("dropped:queue_full", 1L));
    }

    @Test
    @DisplayName("GIVEN no deliveries for an event WHEN listed SHOULD return nothing")
    void given_unknownEvent_when_listed_should_beEmpty() {
        assertThat(listed("404", 10)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN no deliveries for an event WHEN counted SHOULD return an empty map")
    void given_unknownEvent_when_counted_should_beEmpty() {
        assertThat(counted("404")).isEmpty();
    }
}
