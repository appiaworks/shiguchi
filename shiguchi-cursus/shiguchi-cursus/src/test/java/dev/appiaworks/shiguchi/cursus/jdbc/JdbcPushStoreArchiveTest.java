package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushArchive.Entry;
import dev.appiaworks.shiguchi.cursus.PushArchive.Query;
import dev.appiaworks.shiguchi.cursus.PushArchive.SearchCapabilities;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JdbcPushStoreArchiveTest {

    private static final Topic ORDERS_1 = Topic.parse("orders:1");

    private JdbcTestDatabase database;
    private String table;
    private JdbcTemplate jdbc;
    private JdbcPushStore store;

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        database = database();
        table = database.table("push_event_archive_test");
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
    }

    private static PushEvent event(String topic, String json) {
        return event(topic, json, null, Instant.now());
    }

    private static PushEvent event(String topic, String json, Map<String, String> attributes, Instant time) {
        return new PushEvent(Topic.parse(topic), null, json.getBytes(StandardCharsets.UTF_8), attributes, null, time);
    }

    private static Query query(String topic, int limit) {
        return new Query(topic, null, null, null, null, null, null, null, limit);
    }

    private List<Entry> search(Query query) {
        return store.search(query).collectList().block();
    }

    private List<PushEvent> range(Topic topic) {
        return store.range(topic, null, 10).collectList().block();
    }

    private static List<String> payloads(List<Entry> entries) {
        return entries.stream()
                .map(e -> new String(e.event().data(), StandardCharsets.UTF_8))
                .toList();
    }

    private static List<String> eventPayloads(List<PushEvent> events) {
        return events.stream()
                .map(e -> new String(e.data(), StandardCharsets.UTF_8))
                .toList();
    }

    private static List<String> topics(List<Entry> entries) {
        return entries.stream().map(e -> e.event().topic().toString()).toList();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    @Test
    @DisplayName(
            "GIVEN archive on and history size 0 WHEN appending SHOULD return a positive table id, find the row by id, and keep it out of history")
    void given_archiveOnAndNoHistory_when_appending_should_returnTableIdAndKeepOutOfHistory() {
        PushEvent stored = store.append(event("orders:1", "{\"n\":1}"), 0, null).block();

        assertThat(stored.id()).matches("\\d+");
        assertThat(store.get(ORDERS_1, stored.id()).block().event().id()).isEqualTo(stored.id());
        assertThat(range(ORDERS_1)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN events on two orders topics and one ticker topic WHEN searching by topic SHOULD match exact, wildcard, namespace and no topic, newest first")
    void given_eventsOnTopics_when_searchingByTopic_should_matchNewestFirst() {
        store.append(event("orders:1", "{\"n\":1}"), 100, null).block();
        store.append(event("orders:2", "{\"n\":2}"), 100, null).block();
        store.append(event("ticker:x", "{\"n\":3}"), 100, null).block();

        assertThat(topics(search(query("orders:1", 10)))).containsExactly("orders:1");
        assertThat(topics(search(query("orders:*", 10)))).containsExactly("orders:2", "orders:1");
        assertThat(topics(search(query("orders", 10)))).containsExactly("orders:2", "orders:1");
        assertThat(topics(search(query(null, 10)))).containsExactly("ticker:x", "orders:2", "orders:1");
    }

    @Test
    @DisplayName(
            "GIVEN events with region eu and region us WHEN searching by the region attribute SHOULD return only the eu event")
    void given_eventsWithAttributes_when_searchingByAttribute_should_returnMatchingOnly() {
        Instant now = Instant.now();
        store.append(event("orders:1", "{\"n\":1}", Map.of("region", "eu"), now), 100, null)
                .block();
        store.append(event("orders:1", "{\"n\":2}", Map.of("region", "us"), now), 100, null)
                .block();

        Query byRegion = new Query("orders:1", null, null, null, null, Map.of("region", "eu"), null, null, 10);

        assertThat(payloads(search(byRegion))).containsExactly("{\"n\":1}");
    }

    @Test
    @DisplayName(
            "GIVEN one typed event and one JSON event WHEN searching by type URL SHOULD return the typed event for its type and the JSON event for json")
    void given_typedAndJsonEvents_when_searchingByTypeUrl_should_returnMatchingOnly() {
        store.append(
                        new PushEvent(
                                ORDERS_1,
                                null,
                                "{\"n\":1}".getBytes(StandardCharsets.UTF_8),
                                null,
                                null,
                                Instant.now(),
                                "type.googleapis.com/x.Y",
                                new byte[] {1, 2, 3}),
                        100,
                        null)
                .block();
        store.append(event("orders:1", "{\"n\":2}"), 100, null).block();

        Query typed = new Query("orders:1", null, null, null, null, null, "type.googleapis.com/x.Y", null, 10);
        Query json = new Query("orders:1", null, null, null, null, null, "json", null, 10);

        assertThat(payloads(search(typed))).containsExactly("{\"n\":1}");
        assertThat(payloads(search(json))).containsExactly("{\"n\":2}");
    }

    @Test
    @DisplayName(
            "GIVEN a shipped and a cancelled payload WHEN searching the text shipped SHOULD return only the shipped event")
    void given_payloadsWithDifferentStatus_when_searchingText_should_returnMatchingOnly() {
        store.append(event("orders:1", "{\"status\":\"shipped\"}"), 100, null).block();
        store.append(event("orders:1", "{\"status\":\"cancelled\"}"), 100, null).block();

        Query text = new Query(null, null, null, null, null, null, null, "shipped", 10);

        assertThat(payloads(search(text))).containsExactly("{\"status\":\"shipped\"}");
    }

    @Test
    @DisplayName(
            "GIVEN three events WHEN searching with a limit, a before id or an after id SHOULD return the newest, the older side or the newer side only")
    void given_threeEvents_when_pagingSearch_should_honourLimitBeforeAndAfter() {
        store.append(event("orders:1", "{\"n\":1}"), 100, null).block();
        String middle =
                store.append(event("orders:1", "{\"n\":2}"), 100, null).block().id();
        store.append(event("orders:1", "{\"n\":3}"), 100, null).block();
        String oldest = search(query("orders:1", 10)).get(2).event().id();

        assertThat(payloads(search(query("orders:1", 2)))).containsExactly("{\"n\":3}", "{\"n\":2}");

        Query before = new Query("orders:1", null, null, null, middle, null, null, null, 10);
        assertThat(payloads(search(before))).containsExactly("{\"n\":1}");

        Query after = new Query("orders:1", null, null, oldest, null, null, null, null, 10);
        assertThat(payloads(search(after))).containsExactly("{\"n\":3}", "{\"n\":2}");
    }

    @Test
    @DisplayName(
            "GIVEN events three, two and one hour old WHEN searching a from and to window SHOULD return only the events inside it")
    void given_eventsAtDifferentTimes_when_searchingWindow_should_honourFromAndTo() {
        Instant now = Instant.now();
        store.append(event("orders:1", "{\"n\":1}", null, now.minus(Duration.ofHours(3))), 100, null)
                .block();
        store.append(event("orders:1", "{\"n\":2}", null, now.minus(Duration.ofHours(2))), 100, null)
                .block();
        store.append(event("orders:1", "{\"n\":3}", null, now.minus(Duration.ofHours(1))), 100, null)
                .block();

        Query window = new Query(
                "orders:1",
                now.minus(Duration.ofMinutes(150)),
                now.minus(Duration.ofMinutes(90)),
                null,
                null,
                null,
                null,
                null,
                10);
        Query sinceTwoAndAHalfHours =
                new Query("orders:1", now.minus(Duration.ofMinutes(150)), null, null, null, null, null, null, 10);

        assertThat(payloads(search(window))).containsExactly("{\"n\":2}");
        assertThat(payloads(search(sinceTwoAndAHalfHours))).containsExactly("{\"n\":3}", "{\"n\":2}");
    }

    @Test
    @DisplayName(
            "GIVEN a stored event WHEN tombstoned by ops SHOULD redact it once, keep its metadata and take it out of history")
    void given_storedEvent_when_tombstoned_should_redactAndLeaveHistory() {
        PushEvent stored = store.append(
                        event("orders:1", "{\"n\":1}", Map.of("region", "eu"), Instant.now()), 100, null)
                .block();
        store.append(event("orders:1", "{\"n\":2}"), 100, null).block();
        assertThat(store.historyLength(ORDERS_1)).isEqualTo(2);

        assertThat(store.tombstone(ORDERS_1, stored.id(), "ops").block()).isTrue();
        assertThat(store.tombstone(ORDERS_1, stored.id(), "ops").block()).isFalse();

        Entry redacted = store.get(ORDERS_1, stored.id()).block();
        assertThat(redacted.tombstoned()).isTrue();
        assertThat(redacted.tombstonedBy()).isEqualTo("ops");
        assertThat(redacted.event().data()).isEqualTo("null".getBytes(StandardCharsets.UTF_8));
        assertThat(redacted.event().attributes()).containsEntry("region", "eu");
        assertThat(eventPayloads(range(ORDERS_1))).containsExactly("{\"n\":2}");
        assertThat(store.historyLength(ORDERS_1)).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN history size 2 and trim every append WHEN appending three events SHOULD leave the newest two in history and all three in search")
    void given_historySizeTwo_when_appendingThree_should_keepArchiveWhole() throws InterruptedException {
        for (int i = 1; i <= 3; i++)
            store.append(event("orders:1", "{\"n\":" + i + "}"), 2, null).block();

        awaitTrue(() -> store.historyLength(ORDERS_1) == 2);

        assertThat(eventPayloads(range(ORDERS_1))).containsExactly("{\"n\":2}", "{\"n\":3}");
        assertThat(payloads(search(query("orders:1", 10)))).containsExactly("{\"n\":3}", "{\"n\":2}", "{\"n\":1}");
    }

    @Test
    @DisplayName("GIVEN a row past its history ttl and past its archive window WHEN sweeping SHOULD delete it")
    void given_rowOutOfBothWindows_when_sweeping_should_deleteIt() throws InterruptedException {
        PushEvent stored = store.append(event("orders:1", "{\"n\":1}"), 1, Duration.ofMillis(1))
                .block();
        jdbc.update(
                "UPDATE " + table + " SET archived_until = ? WHERE id = ?",
                System.currentTimeMillis() - 1000,
                Long.parseLong(stored.id()));
        Thread.sleep(20);

        store.sweep().block();

        assertThat(store.get(ORDERS_1, stored.id()).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a row past its history ttl but inside its archive window WHEN sweeping SHOULD keep it out of history yet still searchable")
    void given_rowPastHistoryButInsideArchive_when_sweeping_should_keepItSearchable() throws InterruptedException {
        PushEvent stored = store.append(event("orders:1", "{\"n\":1}"), 1, Duration.ofMillis(1))
                .block();
        Thread.sleep(20);

        store.sweep().block();

        assertThat(range(ORDERS_1)).isEmpty();
        assertThat(store.get(ORDERS_1, stored.id()).blockOptional()).isPresent();
        assertThat(payloads(search(query("orders:1", 10)))).containsExactly("{\"n\":1}");
    }

    @Test
    @DisplayName(
            "GIVEN a store with the archive on WHEN asking its capabilities SHOULD report search, attributes, full text and one hour of retention")
    void given_archiveOn_when_askingCapabilities_should_reportSearchAndRetention() {
        SearchCapabilities capabilities = store.searchCapabilities();

        assertThat(store.archiveOn()).isTrue();
        assertThat(capabilities.enabled()).isTrue();
        assertThat(capabilities.attributes()).isTrue();
        assertThat(capabilities.fullText()).isTrue();
        assertThat(capabilities.retention()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName(
            "GIVEN a store built without an archive WHEN asking its capabilities and searching SHOULD report it disabled and return nothing")
    void given_storeWithoutArchive_when_askingCapabilitiesAndSearching_should_reportDisabled() {
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(table);
        JdbcPushStore noArchive = new JdbcPushStore(jdbc.getDataSource(), config);

        assertThat(noArchive.archiveOn()).isFalse();
        assertThat(noArchive.searchCapabilities().enabled()).isFalse();
        assertThat(noArchive.search(query(null, 10)).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN topic globs and namespaces WHEN building the LIKE prefix SHOULD cut at the wildcard and escape LIKE metacharacters")
    void given_topicGlobs_when_buildingLikePrefix_should_cutAndEscape() {
        // the JDBC store escapes with '!' (portable across H2 and Postgres), not Postgres' backslash
        assertThat(JdbcPushStore.likePrefix("orders:eu/*")).isEqualTo("orders:eu/%");
        assertThat(JdbcPushStore.likePrefix("orders")).isEqualTo("orders:%");
        assertThat(JdbcPushStore.likePrefix("orders:**")).isEqualTo("orders:%");
        assertThat(JdbcPushStore.likePrefix("a_b:x%*")).isEqualTo("a!_b:x!%%");
    }
}
