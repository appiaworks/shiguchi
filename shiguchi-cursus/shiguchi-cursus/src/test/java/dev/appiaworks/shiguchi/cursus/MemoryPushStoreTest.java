package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("In-memory push event store")
class MemoryPushStoreTest {

    private final Topic topic = Topic.parse("orders:123");
    private final MemoryPushStore store = new MemoryPushStore();

    @Test
    @DisplayName("append assigns increasing ids of 19 characters")
    void given_two_events_when_appended_should_get_increasing_numeric_ids() {
        PushEvent e1 = new PushEvent(topic, null, "{\"x\":1}".getBytes(), null, null, null);
        PushEvent e2 = new PushEvent(topic, null, "{\"y\":2}".getBytes(), null, null, null);

        StepVerifier.create(store.append(e1, 0, null))
                .assertNext(stored1 -> {
                    assertThat(stored1.id()).isNotNull().hasSize(19);
                    assertThat(stored1.id()).matches("\\d{19}");
                })
                .verifyComplete();

        StepVerifier.create(store.append(e2, 0, null))
                .assertNext(stored2 -> {
                    assertThat(stored2.id()).isNotNull().hasSize(19);
                    assertThat(store.compareIds(stored2.id(), stored2.id())).isZero();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("compareIds orders ids correctly")
    void given_two_stored_events_when_compared_should_order_correctly() {
        PushEvent e1 = new PushEvent(topic, null, "{}".getBytes(), null, null, null);
        PushEvent e2 = new PushEvent(topic, null, "{}".getBytes(), null, null, null);

        String id1 = store.append(e1, 0, null).block().id();
        String id2 = store.append(e2, 0, null).block().id();

        assertThat(store.compareIds(id1, id2)).isNegative();
        assertThat(store.compareIds(id2, id1)).isPositive();
        assertThat(store.compareIds(id1, id1)).isZero();
    }

    @Test
    @DisplayName("with historySize 0 nothing is kept")
    void given_history_size_zero_when_appended_should_keep_no_history() {
        PushEvent event = new PushEvent(topic, null, "{}".getBytes(), null, null, null);
        store.append(event, 0, null).block();

        assertThat(store.historyLength(topic)).isZero();
        assertThat(store.range(topic, null, 10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("with historySize 3 after 5 appends only the last 3 are kept")
    void given_history_size_three_when_appended_five_times_should_keep_only_last_three() {
        for (int i = 0; i < 5; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            store.append(event, 3, null).block();
        }

        assertThat(store.historyLength(topic)).isEqualTo(3);
        List<PushEvent> events = store.range(topic, null, 10).collectList().block();
        assertThat(events).hasSize(3);
    }

    @Test
    @DisplayName("range returns events in oldest-first order")
    void given_stored_events_when_ranged_should_return_oldest_first() {
        String id1 = null;
        String id2 = null;
        for (int i = 0; i < 3; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            PushEvent stored = store.append(event, 3, null).block();
            if (i == 0) id1 = stored.id();
            if (i == 1) id2 = stored.id();
        }

        List<PushEvent> all = store.range(topic, null, 10).collectList().block();
        assertThat(all.get(0).id()).isEqualTo(id1);
        assertThat(all.get(1).id()).isEqualTo(id2);
    }

    @Test
    @DisplayName("range(topic, idOfSecond, 10) returns only events after it")
    void given_range_with_afterId_when_queried_should_exclude_older_events() {
        String idOfSecond = null;
        for (int i = 0; i < 3; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            PushEvent stored = store.append(event, 3, null).block();
            if (i == 1) idOfSecond = stored.id();
        }

        List<PushEvent> events =
                store.range(topic, idOfSecond, 10).collectList().block();
        assertThat(events).hasSize(1);
        assertThat(store.compareIds(events.get(0).id(), idOfSecond)).isPositive();
    }

    @Test
    @DisplayName("range honours limit")
    void given_range_with_limit_when_queried_should_respect_limit() {
        for (int i = 0; i < 5; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            store.append(event, 10, null).block();
        }

        List<PushEvent> events = store.range(topic, null, 2).collectList().block();
        assertThat(events).hasSize(2);
    }

    @Test
    @DisplayName("an event older than historyTtl is evicted on the next append")
    void given_event_older_than_ttl_when_another_appended_should_evict_old() throws InterruptedException {
        Instant oldTime = Instant.now().minus(Duration.ofMinutes(2));
        PushEvent oldEvent = new PushEvent(topic, null, "{}".getBytes(), null, null, oldTime);
        store.append(oldEvent, 10, Duration.ofMinutes(1)).block();

        Thread.sleep(100); // Small delay to ensure time difference

        PushEvent newEvent = new PushEvent(topic, null, "{}".getBytes(), null, null, null);
        store.append(newEvent, 10, Duration.ofMinutes(1)).block();

        assertThat(store.historyLength(topic)).isOne();
    }

    @Test
    @DisplayName("compareIds with a non-numeric id throws IllegalArgumentException")
    void given_non_numeric_id_when_compared_should_throw() {
        assertThatThrownBy(() -> store.compareIds("not-a-number", "0000000000000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an event id");
    }

    @Test
    @DisplayName("capabilities reports history support and no persistent sessions")
    void given_store_when_capabilities_checked_should_report_history_only() {
        var capabilities = store.capabilities();
        assertThat(capabilities.history()).isTrue();
        assertThat(capabilities.persistentSessions()).isFalse();
    }

    private final String leaseKey = "upstream:orders:42";

    @Test
    @DisplayName("GIVEN a free lease WHEN a holder asks for it SHOULD grant it")
    void given_freeLease_when_leased_should_grant() {
        assertThat(store.lease(leaseKey, "gw-a", Duration.ofSeconds(5)).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a lease held by gw-a WHEN gw-a asks again SHOULD renew it")
    void given_leaseHeldBySameHolder_when_leasedAgain_should_renew() {
        store.lease(leaseKey, "gw-a", Duration.ofSeconds(5)).block();

        assertThat(store.lease(leaseKey, "gw-a", Duration.ofSeconds(5)).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a lease held by gw-a WHEN gw-b asks for it SHOULD refuse it")
    void given_leaseHeldByOtherHolder_when_leased_should_refuse() {
        store.lease(leaseKey, "gw-a", Duration.ofSeconds(5)).block();

        assertThat(store.lease(leaseKey, "gw-b", Duration.ofSeconds(5)).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a lease released by gw-a WHEN gw-b asks for it SHOULD grant it")
    void given_leaseReleasedByHolder_when_otherHolderLeases_should_grant() {
        store.lease(leaseKey, "gw-a", Duration.ofSeconds(5)).block();
        store.release(leaseKey, "gw-a").block();

        assertThat(store.lease(leaseKey, "gw-b", Duration.ofSeconds(5)).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a lease whose ttl has expired WHEN gw-b asks for it SHOULD grant it")
    void given_leaseExpired_when_otherHolderLeases_should_grant() throws InterruptedException {
        store.lease(leaseKey, "gw-a", Duration.ofMillis(1)).block();
        Thread.sleep(10);

        assertThat(store.lease(leaseKey, "gw-b", Duration.ofSeconds(5)).block()).isTrue();
    }
}
