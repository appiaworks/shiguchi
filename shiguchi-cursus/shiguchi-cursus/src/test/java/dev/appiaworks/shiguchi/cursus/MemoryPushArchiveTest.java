package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("Memory push archive")
class MemoryPushArchiveTest {

    private static final String ID_1 = "0000000000000000001";
    private static final String ID_2 = "0000000000000000002";
    private static final String ID_3 = "0000000000000000003";
    private static final Instant T0 = Instant.parse("2026-10-09T10:00:00Z");

    private final MemoryPushArchive archive = new MemoryPushArchive(100, Duration.ZERO);

    @Test
    @DisplayName("GIVEN an event without an id WHEN appended SHOULD fail with IllegalArgumentException")
    void given_event_without_id_when_appended_should_error() {
        StepVerifier.create(archive.append(event("orders:1", null, "{}", Map.of(), T0)))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("GIVEN three archived events WHEN searched with limit two SHOULD return the two newest first")
    void given_three_events_when_searched_with_limit_should_return_newest_first() {
        appendAll(
                event("orders:1", ID_1, "{}", Map.of(), T0),
                event("orders:1", ID_2, "{}", Map.of(), T0),
                event("orders:1", ID_3, "{}", Map.of(), T0));

        StepVerifier.create(archive.search(query(null, Map.of(), null, null, 2))
                        .map(e -> e.event().id()))
                .expectNext(ID_3, ID_2)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN events on two topics WHEN searched by an exact topic SHOULD return only that topic")
    void given_events_on_two_topics_when_searched_by_exact_topic_should_return_only_that_topic() {
        appendAll(event("orders:1", ID_1, "{}", Map.of(), T0), event("orders:2", ID_2, "{}", Map.of(), T0));

        StepVerifier.create(archive.search(query("orders:1", Map.of(), null, null, 10))
                        .map(e -> e.event().id()))
                .expectNext(ID_1)
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN events in a namespace WHEN searched by the namespace or by a wildcard SHOULD match every topic in it")
    void given_events_in_namespace_when_searched_by_namespace_or_wildcard_should_match_all_topics() {
        appendAll(
                event("orders:1", ID_1, "{}", Map.of(), T0),
                event("orders:2", ID_2, "{}", Map.of(), T0),
                event("ticker:1", ID_3, "{}", Map.of(), T0));

        StepVerifier.create(archive.search(query("orders", Map.of(), null, null, 10))
                        .map(e -> e.event().id()))
                .expectNext(ID_2, ID_1)
                .verifyComplete();
        StepVerifier.create(archive.search(query("orders:*", Map.of(), null, null, 10))
                        .map(e -> e.event().id()))
                .expectNext(ID_2, ID_1)
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN events with various attributes WHEN searched by two attributes SHOULD return only events with all of them")
    void given_events_with_various_attributes_when_searched_by_attributes_should_require_all() {
        appendAll(
                event("orders:1", ID_1, "{}", Map.of("region", "eu", "tier", "gold"), T0),
                event("orders:1", ID_2, "{}", Map.of("region", "eu"), T0),
                event("orders:1", ID_3, "{}", Map.of("region", "us", "tier", "gold"), T0));

        Map<String, String> wanted = Map.of("region", "eu", "tier", "gold");
        StepVerifier.create(archive.search(query(null, wanted, null, null, 10))
                        .map(e -> e.event().id()))
                .expectNext(ID_1)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a typed and a JSON event WHEN searched with typeUrl json SHOULD return only the JSON event")
    void given_typed_and_json_events_when_searched_with_json_type_should_return_only_json_event() {
        appendAll(
                event("orders:1", ID_1, "{}", Map.of(), T0),
                new PushEvent(
                        Topic.parse("orders:1"),
                        ID_2,
                        "{}".getBytes(StandardCharsets.UTF_8),
                        Map.of(),
                        null,
                        T0,
                        "type.googleapis.com/demo.Order",
                        new byte[] {1, 2}));

        StepVerifier.create(archive.search(query(null, Map.of(), "json", null, 10))
                        .map(e -> e.event().id()))
                .expectNext(ID_1)
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN events with different payloads WHEN searched by upper-case text SHOULD match case-insensitively")
    void given_events_with_different_payloads_when_searched_by_text_should_match_case_insensitively() {
        appendAll(
                event("orders:1", ID_1, "{\"name\":\"Hello World\"}", Map.of(), T0),
                event("orders:1", ID_2, "{\"name\":\"bye\"}", Map.of(), T0));

        StepVerifier.create(archive.search(query(null, Map.of(), null, "WORLD", 10))
                        .map(e -> e.event().id()))
                .expectNext(ID_1)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN events across a time range WHEN searched from and to SHOULD include from and exclude to")
    void given_events_across_time_when_searched_by_window_should_include_from_and_exclude_to() {
        Instant oneHourLater = T0.plus(Duration.ofHours(1));
        Instant twoHoursLater = T0.plus(Duration.ofHours(2));
        appendAll(
                event("orders:1", ID_1, "{}", Map.of(), T0),
                event("orders:1", ID_2, "{}", Map.of(), oneHourLater),
                event("orders:1", ID_3, "{}", Map.of(), twoHoursLater));

        PushArchive.Query window =
                new PushArchive.Query(null, oneHourLater, twoHoursLater, null, null, null, null, null, 10);

        StepVerifier.create(archive.search(window).map(e -> e.event().id()))
                .expectNext(ID_2)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN three archived events WHEN searched between afterId and beforeId SHOULD exclude both bounds")
    void given_three_events_when_searched_between_ids_should_exclude_both_bounds() {
        appendAll(
                event("orders:1", ID_1, "{}", Map.of(), T0),
                event("orders:1", ID_2, "{}", Map.of(), T0),
                event("orders:1", ID_3, "{}", Map.of(), T0));

        PushArchive.Query between = new PushArchive.Query(null, null, null, ID_1, ID_3, null, null, null, 10);

        StepVerifier.create(archive.search(between).map(e -> e.event().id()))
                .expectNext(ID_2)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN tombstoned SHOULD redact its payload once and keep its metadata")
    void given_archived_event_when_tombstoned_should_redact_payload_and_keep_metadata() {
        Topic topic = Topic.parse("orders:1");
        appendAll(event("orders:1", ID_1, "{\"secret\":1}", Map.of("region", "eu"), T0));

        assertThat(archive.tombstone(topic, ID_1, "alice").block()).isTrue();
        assertThat(archive.tombstone(topic, ID_1, "bob").block()).isFalse();

        PushArchive.Entry entry = archive.get(topic, ID_1).block();
        assertThat(entry).isNotNull();
        assertThat(entry.tombstoned()).isTrue();
        assertThat(entry.tombstonedBy()).isEqualTo("alice");
        assertThat(entry.event().data()).isEqualTo("null".getBytes(StandardCharsets.UTF_8));
        assertThat(entry.event().attributes()).containsEntry("region", "eu");
    }

    private void appendAll(PushEvent... events) {
        for (PushEvent event : events) archive.append(event).block();
    }

    private static PushEvent event(String topic, String id, String json, Map<String, String> attributes, Instant time) {
        return new PushEvent(Topic.parse(topic), id, json.getBytes(StandardCharsets.UTF_8), attributes, null, time);
    }

    private static PushArchive.Query query(
            String topic, Map<String, String> attributes, String typeUrl, String text, int limit) {
        return new PushArchive.Query(topic, null, null, null, null, attributes, typeUrl, text, limit);
    }
}
