package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushDelivery;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JdbcPushStoreDeliveryTest {

    private static final Topic ORDERS_1 = Topic.parse("orders:1");
    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");

    private JdbcTestDatabase database;
    private String table;
    private String deliveryTable;
    private JdbcTemplate jdbc;
    private JdbcPushStore store;

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        database = database();
        table = database.table("push_event_delivery_test");
        deliveryTable = JdbcPushSchema.deliveryTable(table);
        jdbc = new JdbcTemplate(database.dataSource(getClass().getSimpleName()));
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(table);
        config.setTrimEvery(1);
        config.setSweepInterval(Duration.ZERO);
        PushProperties.Archive archive = new PushProperties.Archive();
        archive.setRetention(Duration.ofHours(1));
        store = new JdbcPushStore(jdbc.getDataSource(), config, archive);
        store.afterPropertiesSet();
    }

    @AfterAll
    void stop() {
        if (store != null) store.destroy();
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM " + deliveryTable);
    }

    private static PushDelivery delivery(
            String eventId, String connectionId, String principal, String outcome, Instant time) {
        return new PushDelivery(ORDERS_1, eventId, connectionId, principal, "sse", time, outcome);
    }

    private static List<PushDelivery> threeForEvent42() {
        return List.of(
                delivery("42", "c1", null, "delivered", T0),
                delivery("42", "c2", "alice", "dropped:queue_full", T0.plusSeconds(1)),
                delivery("42", "c3", "bob", "delivered", T0.plusSeconds(2)));
    }

    @Test
    @DisplayName("GIVEN the JDBC store WHEN asked whether it keeps a delivery log SHOULD answer true")
    void given_jdbcStore_when_deliveryLogAsked_should_answerTrue() {
        assertThat(store.deliveryLog()).isTrue();
    }

    @Test
    @DisplayName("GIVEN three deliveries for event 42 WHEN listed "
            + "SHOULD return them ordered by time with null principal kept")
    void given_threeDeliveries_when_listed_should_returnOrderedByTimeWithNullPrincipal() {
        store.recordDeliveries(threeForEvent42()).block();

        List<PushDelivery> found =
                store.deliveries(ORDERS_1, "42", 10).collectList().block();

        assertThat(found).extracting(PushDelivery::connectionId).containsExactly("c1", "c2", "c3");
        assertThat(found).extracting(PushDelivery::time).containsExactly(T0, T0.plusSeconds(1), T0.plusSeconds(2));
        assertThat(found.get(0).principal()).isNull();
        assertThat(found.get(1).principal()).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN three deliveries for event 42 WHEN counted SHOULD group them per outcome")
    void given_threeDeliveries_when_counted_should_groupPerOutcome() {
        store.recordDeliveries(threeForEvent42()).block();

        Map<String, Long> counts = store.deliveryCounts(ORDERS_1, "42").block();

        assertThat(counts).containsOnly(Map.entry("delivered", 2L), Map.entry("dropped:queue_full", 1L));
    }

    @Test
    @DisplayName("GIVEN a non-numeric event id WHEN listed and counted SHOULD return nothing and not fail")
    void given_nonNumericEventId_when_queried_should_returnEmptyWithoutError() {
        assertThat(store.deliveries(ORDERS_1, "abc", 10).collectList().block()).isEmpty();
        assertThat(store.deliveryCounts(ORDERS_1, "abc").block()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an empty batch WHEN recorded SHOULD complete without writing anything")
    void given_emptyBatch_when_recorded_should_complete() {
        store.recordDeliveries(List.of()).block();

        assertThat(store.deliveryCounts(ORDERS_1, "42").block()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN 250 deliveries in one batch WHEN counted SHOULD keep all 250 across the 100-row chunks")
    void given_250Deliveries_when_recordedInOneCall_should_countAll250() {
        List<PushDelivery> batch = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            batch.add(delivery("42", "c" + i, null, "delivered", T0.plusMillis(i)));
        }

        store.recordDeliveries(batch).block();

        Map<String, Long> counts = store.deliveryCounts(ORDERS_1, "42").block();
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(250L);
    }

    @Test
    @DisplayName("GIVEN deliveries whose event row does not exist WHEN the sweep runs SHOULD remove them")
    void given_orphanDeliveries_when_swept_should_removeThem() {
        store.recordDeliveries(List.of(delivery("999", "c1", null, "delivered", T0)))
                .block();

        store.sweep().block();

        assertThat(store.deliveries(ORDERS_1, "999", 10).collectList().block()).isEmpty();
        assertThat(store.deliveryCounts(ORDERS_1, "999").block()).isEmpty();
    }
}
