package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.cursus.PushArchive;
import dev.appiaworks.shiguchi.cursus.PushArchive.Query;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

@DisplayName("Redis push archive (real Redis)")
class RedisPushArchiveTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final long BASE = System.currentTimeMillis();

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private String prefix;
    private RedisPushArchive archive;

    @BeforeAll
    static void startRedis() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(port).build();
        redis.start();
        connections = new LettuceConnectionFactory("localhost", port);
        connections.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(connections);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (connections != null) connections.destroy();
        if (redis != null) redis.stop();
    }

    @BeforeEach
    void setup(TestInfo test) {
        prefix = "test:" + test.getTestMethod().orElseThrow().getName() + ":";
        archive = new RedisPushArchive(template, prefix, HOUR, 10_000);
    }

    private static String id(int n) {
        return (BASE + n) + "-0";
    }

    private static PushEvent event(String topic, int n, String json, Map<String, String> attrs) {
        return new PushEvent(
                Topic.parse(topic), id(n), json.getBytes(StandardCharsets.UTF_8), attrs, null, Instant.now());
    }

    private void append(PushEvent... events) {
        for (PushEvent e : events) archive.append(e).block();
    }

    private static Query query(String topic, Map<String, String> attrs, String after, String before, int limit) {
        return new Query(topic, null, null, after, before, attrs, null, null, limit);
    }

    private List<String> ids(Query q) {
        return archive.search(q).map(e -> e.event().id()).collectList().block();
    }

    @Test
    @DisplayName("GIVEN an event without an id WHEN appended SHOULD fail with IllegalArgumentException")
    void given_event_without_id_when_appended_should_error() {
        PushEvent noId = new PushEvent(Topic.parse("orders:1"), null, "{}".getBytes(), null, null, Instant.now());

        assertThatThrownBy(() -> archive.append(noId).block()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("GIVEN three archived events WHEN searched with limit two SHOULD return the two newest first")
    void given_three_events_when_searched_with_limit_should_return_newest_first() {
        append(
                event("orders:1", 1, "{}", Map.of()),
                event("orders:1", 2, "{}", Map.of()),
                event("orders:1", 3, "{}", Map.of()));

        assertThat(ids(query("orders:1", Map.of(), null, null, 2))).containsExactly(id(3), id(2));
    }

    @Test
    @DisplayName("GIVEN events on two topics WHEN searched by an exact topic SHOULD return only that topic")
    void given_events_on_two_topics_when_searched_by_exact_topic_should_return_only_that_topic() {
        append(event("orders:1", 1, "{}", Map.of()), event("orders:2", 2, "{}", Map.of()));

        assertThat(ids(query("orders:1", Map.of(), null, null, 10))).containsExactly(id(1));
    }

    @Test
    @DisplayName(
            "GIVEN events in two namespaces WHEN searched by namespace or wildcard SHOULD merge topics newest first")
    void given_events_in_namespaces_when_searched_by_pattern_should_merge_topics_newest_first() {
        append(
                event("orders:1", 1, "{}", Map.of()),
                event("orders:2", 3, "{}", Map.of()),
                event("billing:1", 2, "{}", Map.of()));

        assertThat(ids(query("orders", Map.of(), null, null, 10))).containsExactly(id(3), id(1));
        assertThat(ids(query("orders:*", Map.of(), null, null, 10))).containsExactly(id(3), id(1));
        assertThat(ids(query(null, Map.of(), null, null, 10))).containsExactly(id(3), id(2), id(1));
    }

    @Test
    @DisplayName("GIVEN events with attributes WHEN searched by attribute SHOULD return only the matching ones")
    void given_events_with_attributes_when_searched_by_attribute_should_filter() {
        append(
                event("orders:1", 1, "{}", Map.of("tenant", "acme")),
                event("orders:1", 2, "{}", Map.of("tenant", "globex")));

        assertThat(ids(query("orders:1", Map.of("tenant", "acme"), null, null, 10)))
                .containsExactly(id(1));
    }

    @Test
    @DisplayName("GIVEN four events WHEN paged with beforeId and afterId SHOULD respect both exclusive bounds")
    void given_events_when_paged_by_id_should_respect_exclusive_bounds() {
        append(
                event("orders:1", 1, "{}", Map.of()),
                event("orders:1", 2, "{}", Map.of()),
                event("orders:1", 3, "{}", Map.of()),
                event("orders:1", 4, "{}", Map.of()));

        assertThat(ids(query("orders:1", Map.of(), null, id(3), 10))).containsExactly(id(2), id(1));
        assertThat(ids(query("orders:1", Map.of(), id(1), id(4), 10))).containsExactly(id(3), id(2));
    }

    @Test
    @DisplayName("GIVEN a typed and a JSON event WHEN searched by type URL SHOULD separate them")
    void given_typed_and_json_events_when_searched_by_type_should_separate_them() {
        PushEvent typed = new PushEvent(
                Topic.parse("orders:1"),
                id(2),
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                Map.of(),
                null,
                Instant.now(),
                "type.googleapis.com/acme.Order",
                new byte[] {1, 2});
        append(event("orders:1", 1, "{}", Map.of()), typed);

        Query typedQuery =
                new Query("orders:1", null, null, null, null, null, "type.googleapis.com/acme.Order", null, 10);
        Query jsonQuery = new Query("orders:1", null, null, null, null, null, "json", null, 10);
        assertThat(ids(typedQuery)).containsExactly(id(2));
        assertThat(ids(jsonQuery)).containsExactly(id(1));
        assertThat(archive.get(Topic.parse("orders:1"), id(2)).block().event().typeUrl())
                .isEqualTo("type.googleapis.com/acme.Order");
    }

    @Test
    @DisplayName("GIVEN events at different times WHEN searched by from and to SHOULD keep the window, to exclusive")
    void given_events_at_different_times_when_searched_by_window_should_filter() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        append(
                new PushEvent(Topic.parse("orders:1"), id(1), "{}".getBytes(), null, null, now.minusSeconds(30)),
                new PushEvent(Topic.parse("orders:1"), id(2), "{}".getBytes(), null, null, now.minusSeconds(20)),
                new PushEvent(Topic.parse("orders:1"), id(3), "{}".getBytes(), null, null, now.minusSeconds(10)));

        Query window =
                new Query("orders:1", now.minusSeconds(25), now.minusSeconds(10), null, null, null, null, null, 10);

        assertThat(ids(window)).containsExactly(id(2));
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN fetched by topic and id SHOULD return it, and empty for unknown ids")
    void given_archived_event_when_fetched_should_return_it() {
        append(event("orders:1", 1, "{\"n\":1}", Map.of("k", "v")));

        PushArchive.Entry found = archive.get(Topic.parse("orders:1"), id(1)).block();

        assertThat(found.event().attributes()).containsEntry("k", "v");
        assertThat(new String(found.event().data(), StandardCharsets.UTF_8)).isEqualTo("{\"n\":1}");
        assertThat(found.tombstoned()).isFalse();
        assertThat(archive.get(Topic.parse("orders:1"), id(9)).block()).isNull();
        assertThat(archive.get(Topic.parse("orders:2"), id(1)).block()).isNull();
        assertThat(archive.get(Topic.parse("orders:1"), "not-an-id").block()).isNull();
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN tombstoned SHOULD mask the payload in get and search, once")
    void given_archived_event_when_tombstoned_should_mask_payload_once() {
        Topic topic = Topic.parse("orders:1");
        append(event("orders:1", 1, "{\"secret\":1}", Map.of("k", "v")), event("orders:1", 2, "{}", Map.of()));

        assertThat(archive.tombstone(topic, id(1), "ops").block()).isTrue();
        assertThat(archive.tombstone(topic, id(1), "ops").block()).isFalse();
        assertThat(archive.tombstone(topic, id(9), "ops").block()).isFalse();

        PushArchive.Entry masked = archive.get(topic, id(1)).block();
        assertThat(masked.tombstoned()).isTrue();
        assertThat(masked.tombstonedBy()).isEqualTo("ops");
        assertThat(new String(masked.event().data(), StandardCharsets.UTF_8)).isEqualTo("null");
        assertThat(masked.event().attributes()).containsEntry("k", "v");
        List<PushArchive.Entry> found = archive.search(query("orders:1", Map.of(), null, null, 10))
                .collectList()
                .block();
        assertThat(found).extracting(e -> e.event().id()).containsExactly(id(2), id(1));
        assertThat(found.get(1).tombstoned()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a tombstone WHEN the live history stream held the event SHOULD delete it there")
    void given_tombstone_when_live_history_held_event_should_delete_it_there() {
        RedisPushStore store = new RedisPushStore(template, prefix);
        Topic topic = Topic.parse("orders:1");
        PushEvent kept = store.append(event("orders:1", 1, "{}", Map.of()).withId(null), 10, HOUR)
                .block();
        archive.append(kept).block();

        archive.tombstone(topic, kept.id(), "ops").block();

        assertThat(store.range(topic, null, 10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN events older than the retention WHEN a new one is appended SHOULD drop them from the topic")
    void given_events_older_than_retention_when_appended_should_drop_them() {
        RedisPushArchive shortLived = new RedisPushArchive(template, prefix, Duration.ofSeconds(30), 10_000);
        Instant now = Instant.now();
        shortLived
                .append(new PushEvent(
                        Topic.parse("orders:1"), id(1), "{}".getBytes(), null, null, now.minusSeconds(120)))
                .block();
        shortLived
                .append(new PushEvent(Topic.parse("orders:1"), id(2), "{}".getBytes(), null, null, now))
                .block();

        assertThat(shortLived
                        .search(query("orders:1", Map.of(), null, null, 10))
                        .map(e -> e.event().id())
                        .collectList()
                        .block())
                .containsExactly(id(2));
        assertThat(shortLived.get(Topic.parse("orders:1"), id(1)).block()).isNull();
    }

    @Test
    @DisplayName("GIVEN more matching events than max-scan WHEN searched SHOULD read only the newest max-scan entries")
    void given_more_events_than_max_scan_when_searched_should_stop_reading() {
        RedisPushArchive bounded = new RedisPushArchive(template, prefix, HOUR, 3);
        for (int n = 1; n <= 6; n++)
            bounded.append(event("orders:1", n, "{}", Map.of("n", "" + n))).block();

        List<String> all = bounded.search(query("orders:1", Map.of(), null, null, 10))
                .map(e -> e.event().id())
                .collectList()
                .block();
        List<String> selective = bounded.search(query("orders:1", Map.of("n", "1"), null, null, 10))
                .map(e -> e.event().id())
                .collectList()
                .block();

        assertThat(all).containsExactly(id(6), id(5), id(4));
        assertThat(selective).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an event id below the newest WHEN appended SHOULD still be kept, under its original id")
    void given_event_id_below_newest_when_appended_should_keep_it_with_original_id() {
        append(event("orders:1", 5, "{}", Map.of()), event("orders:1", 2, "{}", Map.of()));

        List<String> found = ids(query("orders:1", Map.of(), null, null, 10));

        assertThat(found).containsExactlyInAnyOrder(id(5), id(2));
    }

    @Test
    @DisplayName("GIVEN the archive WHEN asked its capabilities SHOULD report attributes but no full text")
    void given_archive_when_asked_capabilities_should_report_attributes_without_full_text() {
        PushArchive.SearchCapabilities caps = archive.searchCapabilities();

        assertThat(caps.enabled()).isTrue();
        assertThat(caps.attributes()).isTrue();
        assertThat(caps.fullText()).isFalse();
        assertThat(caps.retention()).isEqualTo(HOUR);
    }
}
