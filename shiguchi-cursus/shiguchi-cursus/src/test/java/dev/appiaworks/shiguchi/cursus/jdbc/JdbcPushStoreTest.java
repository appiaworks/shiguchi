package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.test.StepVerifier;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JdbcPushStoreTest {

    private JdbcTestDatabase database;
    private String table;
    private JdbcTemplate jdbc;
    private JdbcPushStore store;

    private final Topic topic = Topic.parse("orders:123");

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        database = database();
        table = database.table("push_event_store_test");
        jdbc = new JdbcTemplate(database.dataSource(getClass().getSimpleName()));
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(table);
        config.setTrimEvery(1);
        config.setSweepInterval(Duration.ZERO);
        store = new JdbcPushStore(jdbc.getDataSource(), config);
        store.afterPropertiesSet();
    }

    @AfterAll
    void stop() {
        if (store != null) store.destroy();
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM " + table);
    }

    private PushEvent event(String json) {
        return new PushEvent(topic, null, json.getBytes(), null, null, Instant.now());
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    @Test
    @DisplayName(
            "GIVEN history size 0 WHEN appending SHOULD return a negative local id that names no row and write nothing")
    void given_noHistory_when_appending_should_returnIdAndWriteNothing() {
        PushEvent stored = store.append(event("{}"), 0, null).block();

        assertThat(stored.id()).matches("-\\d+");
        assertThat(JdbcPushStore.isStored(stored.id())).isFalse();
        assertThat(store.historyLength(topic)).isZero();
    }

    @Test
    @DisplayName("GIVEN history WHEN appending twice SHOULD return increasing ids and count both")
    void given_history_when_appendingTwice_should_returnIncreasingIds() {
        PushEvent first = store.append(event("{}"), 100, null).block();
        PushEvent second = store.append(event("{}"), 100, null).block();

        assertThat(Long.parseLong(second.id())).isGreaterThan(Long.parseLong(first.id()));
        assertThat(store.historyLength(topic)).isEqualTo(2);
        assertThat(store.capabilities().history()).isTrue();
        assertThat(store.capabilities().persistentSessions()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN three stored events WHEN reading ranges SHOULD return them oldest first honoring after and limit")
    void given_threeEvents_when_readingRanges_should_honorAfterAndLimit() {
        Instant time = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PushEvent first = store.append(
                        new PushEvent(topic, null, "{\"n\":1}".getBytes(), Map.of("k", "v"), "key-1", time), 100, null)
                .block();
        store.append(event("{\"n\":2}"), 100, null).block();
        store.append(event("{\"n\":3}"), 100, null).block();

        List<PushEvent> all = store.range(topic, null, 10).collectList().block();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).id()).isEqualTo(first.id());
        assertThat(new String(all.get(0).data())).isEqualTo("{\"n\":1}");
        assertThat(all.get(0).attributes()).isEqualTo(Map.of("k", "v"));
        assertThat(all.get(0).key()).isEqualTo("key-1");
        assertThat(all.get(0).time().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(time);
        assertThat(all).extracting(e -> new String(e.data())).containsExactly("{\"n\":1}", "{\"n\":2}", "{\"n\":3}");

        assertThat(store.range(topic, first.id(), 10).collectList().block())
                .extracting(e -> new String(e.data()))
                .containsExactly("{\"n\":2}", "{\"n\":3}");
        assertThat(store.range(topic, null, 1).collectList().block()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN numeric ids WHEN comparing SHOULD order them numerically and reject non-numeric ids")
    void given_ids_when_comparing_should_orderNumerically() {
        assertThat(store.compareIds("5", "10")).isNegative();
        assertThat(store.compareIds("10", "5")).isPositive();
        assertThat(store.compareIds("7", "7")).isZero();
        assertThatThrownBy(() -> store.compareIds("abc", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GIVEN history size 2 WHEN appending four events SHOULD trim to the newest two")
    void given_historySizeTwo_when_appendingFour_should_trimToNewestTwo() throws InterruptedException {
        for (int i = 1; i <= 4; i++)
            store.append(event("{\"n\":" + i + "}"), 2, null).block();

        awaitTrue(() -> store.historyLength(topic) == 2);

        assertThat(store.historyLength(topic)).isEqualTo(2);
        assertThat(store.range(topic, null, 10).collectList().block())
                .extracting(e -> new String(e.data()))
                .containsExactly("{\"n\":3}", "{\"n\":4}");
    }

    @Test
    @DisplayName("GIVEN a short ttl WHEN it elapses SHOULD hide the row at once and delete it on sweep")
    void given_shortTtl_when_elapsed_should_hideThenSweep() throws InterruptedException {
        store.append(event("{}"), 100, Duration.ofMillis(200)).block();
        assertThat(store.range(topic, null, 10).collectList().block()).hasSize(1);

        Thread.sleep(400);

        assertThat(store.range(topic, null, 10).collectList().block()).isEmpty();
        assertThat(store.historyLength(topic)).isZero();
        assertThat(store.sweep().block()).isEqualTo(1L);
        assertThat(store.sweep().block()).isZero();
    }

    @Test
    @DisplayName("GIVEN a typed event WHEN read back SHOULD keep type url, proto bytes and data")
    void given_typedEvent_when_readBack_should_keepTypedPayload() {
        PushEvent typed = new PushEvent(
                topic,
                null,
                "{\"@type\":\"type.googleapis.com/x.Y\"}".getBytes(),
                null,
                null,
                Instant.now(),
                "type.googleapis.com/x.Y",
                new byte[] {1, 2, 3});
        store.append(typed, 100, null).block();

        PushEvent read = store.range(topic, null, 10).blockFirst();

        assertThat(read.typed()).isTrue();
        assertThat(read.typeUrl()).isEqualTo("type.googleapis.com/x.Y");
        assertThat(read.proto()).containsExactly(1, 2, 3);
        assertThat(read.data()).isEqualTo(typed.data());
    }

    @Test
    @DisplayName("GIVEN stored and unknown ids WHEN reading by id SHOULD return the event or complete empty")
    void given_storedAndUnknownIds_when_readingById_should_returnOrComplete() {
        PushEvent stored = store.append(event("{\"n\":1}"), 100, null).block();

        StepVerifier.create(store.byId(stored.id()))
                .assertNext(e -> {
                    assertThat(e.id()).isEqualTo(stored.id());
                    assertThat(new String(e.data())).isEqualTo("{\"n\":1}");
                })
                .verifyComplete();
        StepVerifier.create(store.byId("999999999")).verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN an event with no key and no attributes WHEN read back SHOULD have a null key and empty attributes")
    void given_noKeyNoAttributes_when_readBack_should_beNullAndEmpty() {
        store.append(event("{}"), 100, null).block();

        PushEvent read = store.range(topic, null, 10).blockFirst();

        assertThat(read.key()).isNull();
        assertThat(read.attributes()).isEmpty();
        assertThat(read.typed()).isFalse();
    }

    // Lease rows outlive a test (only the event table is cleared), so each test leases its own key.
    private String leaseKey;

    @BeforeEach
    void newLeaseKey() {
        leaseKey = "upstream:orders:" + UUID.randomUUID();
    }

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
}
