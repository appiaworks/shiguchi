package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("Push topic streams: the reset signal when a resume point is older than the history")
class PushStreamsGapTest {

    private static final Duration DEADLINE = Duration.ofSeconds(5);

    private final PushProperties properties = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final Topic topic = Topic.parse("orders:1");
    private final Topic inbox = Topic.parse("inbox:alice");
    private final PushPublisher publisher;
    private final PushStreams streams;

    PushStreamsGapTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(3);
        properties.getNamespaces().put("orders", orders);

        PushProperties.Namespace mostlyOnce = new PushProperties.Namespace();
        mostlyOnce.setQos(Qos.AT_MOST_ONCE);
        mostlyOnce.setHistorySize(0);
        properties.getNamespaces().put("live", mostlyOnce);

        PushProperties.Namespace sessions = new PushProperties.Namespace();
        sessions.setQos(Qos.AT_LEAST_ONCE);
        sessions.setHistorySize(3);
        sessions.setMaxQueued(2);
        sessions.setSessionExpiry(Duration.ofHours(1));
        sessions.setSessionKey(PushProperties.SessionKey.CLIENT);
        properties.getNamespaces().put("inbox", sessions);

        publisher = new PushPublisher(properties, store, broker, metrics);
        PushSessions persistent = new PushSessions(properties, store, metrics);
        streams = new PushStreams(broker, store, properties, new PushShutdown(), null, persistent);
    }

    @Test
    @DisplayName(
            "GIVEN a resume id older than the kept history WHEN subscribing SHOULD emit one reset first and then the kept events")
    void given_resume_id_older_than_history_when_subscribing_should_emit_reset_then_kept_events() {
        List<PushEvent> sent = publishOrders(6);

        StepVerifier.create(streams.topicStream(
                        topic, ns("orders"), Qos.AT_LEAST_ONCE, sent.get(0).id(), null, metrics))
                .assertNext(e -> {
                    assertThat(e.signal()).isTrue();
                    assertThat(e.attributes())
                            .containsEntry(PushEvent.SIGNAL, "reset")
                            .containsEntry("reason", "history_gap");
                    assertThat(e.id()).isEmpty();
                    assertThat(text(e)).contains("\"topic\":\"orders:1\"").contains("history_gap");
                })
                .expectNextMatches(e -> e.id().equals(sent.get(3).id()))
                .expectNextMatches(e -> e.id().equals(sent.get(4).id()))
                .expectNextMatches(e -> e.id().equals(sent.get(5).id()))
                .thenCancel()
                .verify(DEADLINE);

        assertThat(metrics.resetCount("orders", "history_gap")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("GIVEN no resume id WHEN subscribing SHOULD not emit a reset")
    void given_no_resume_id_when_subscribing_should_not_emit_reset() {
        publishOrders(6);

        StepVerifier.create(streams.topicStream(topic, ns("orders"), Qos.AT_LEAST_ONCE, null, null, metrics))
                .then(() -> publish(topic, "{\"n\":7}"))
                .assertNext(e -> assertThat(e.signal()).isFalse())
                .thenCancel()
                .verify(DEADLINE);

        assertThat(metrics.resetCount("orders", "history_gap")).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a resume id exactly at the last trimmed event WHEN subscribing SHOULD not emit a reset and replay all kept events")
    void given_resume_id_at_last_trimmed_event_when_subscribing_should_not_emit_reset() {
        List<PushEvent> sent = publishOrders(6);

        StepVerifier.create(streams.topicStream(
                        topic, ns("orders"), Qos.AT_LEAST_ONCE, sent.get(2).id(), null, metrics))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(3).id()))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(4).id()))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(5).id()))
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName("GIVEN a resume id inside the kept history WHEN subscribing SHOULD not emit a reset")
    void given_resume_id_inside_history_when_subscribing_should_not_emit_reset() {
        List<PushEvent> sent = publishOrders(6);

        StepVerifier.create(streams.topicStream(
                        topic, ns("orders"), Qos.AT_LEAST_ONCE, sent.get(4).id(), null, metrics))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(5).id()))
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName("GIVEN a topic that never trimmed WHEN resuming from before its first event SHOULD not emit a reset")
    void given_untrimmed_topic_when_resuming_from_start_should_not_emit_reset() {
        List<PushEvent> sent = publishOrders(2);

        StepVerifier.create(streams.topicStream(topic, ns("orders"), Qos.AT_LEAST_ONCE, "0", null, metrics))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(0).id()))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(1).id()))
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName(
            "GIVEN a namespace that keeps no history WHEN a subscriber resumes with an id SHOULD emit a reset with reason no_history, then go live")
    void given_namespace_without_history_when_resuming_with_id_should_emit_no_history_reset() {
        Topic live = Topic.parse("live:1");

        StepVerifier.create(streams.topicStream(live, ns("live"), Qos.AT_MOST_ONCE, "5", null, metrics))
                .assertNext(e -> {
                    assertThat(e.signal()).isTrue();
                    assertThat(e.attributes()).containsEntry("reason", "no_history");
                })
                .then(() -> publish(live, "{\"n\":1}"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":1}"))
                .thenCancel()
                .verify(DEADLINE);

        assertThat(metrics.resetCount("live", "no_history")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("GIVEN a namespace that keeps no history WHEN subscribing without an id SHOULD not emit a reset")
    void given_namespace_without_history_when_subscribing_without_id_should_not_emit_reset() {
        Topic live = Topic.parse("live:1");

        StepVerifier.create(streams.topicStream(live, ns("live"), Qos.AT_MOST_ONCE, null, null, metrics))
                .then(() -> publish(live, "{\"n\":1}"))
                .assertNext(e -> assertThat(e.signal()).isFalse())
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber with a declared origin WHEN the reset is emitted SHOULD deliver it, the origin filter not touching a signal")
    void given_declared_origin_when_reset_emitted_should_not_filter_it() {
        List<PushEvent> sent = publishOrders(6);

        StepVerifier.create(streams.topicStream(
                        topic, ns("orders"), Qos.AT_LEAST_ONCE, sent.get(0).id(), "A", metrics))
                .assertNext(e -> assertThat(e.attributes()).containsEntry(PushEvent.SIGNAL, "reset"))
                .expectNextMatches(e -> e.id().equals(sent.get(3).id()))
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName(
            "GIVEN a merged subscription WHEN one topic's resume id is stale SHOULD deliver the reset of that topic in front of its events")
    void given_merged_subscription_when_resume_id_stale_should_deliver_reset() {
        List<PushEvent> sent = publishOrders(6);

        PushStreams.Merged merged =
                streams.merged(List.of(topic), null, sent.get(0).id(), metrics);

        StepVerifier.create(merged.events())
                .assertNext(e -> assertThat(e.attributes()).containsEntry(PushEvent.SIGNAL, "reset"))
                .expectNextMatches(e -> e.id().equals(sent.get(3).id()))
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName(
            "GIVEN a session whose stored cursor is older than the kept history WHEN it resumes SHOULD emit the reset first, then the kept events")
    void given_session_with_stale_cursor_when_resuming_should_emit_reset_then_events() {
        List<PushEvent> sent = publishInbox(6);
        store.ack("c1@-", inbox, sent.get(0).id(), Duration.ofHours(1)).block();
        PushProperties.Namespace ns = ns("inbox");
        ns.setMaxQueued(3);

        StepVerifier.create(streams.topicStream(inbox, ns, Qos.AT_LEAST_ONCE, null, "c1@-", null, metrics))
                .assertNext(e -> assertThat(e.attributes()).containsEntry(PushEvent.SIGNAL, "reset"))
                .expectNextMatches(e -> e.id().equals(sent.get(3).id()))
                .expectNextMatches(e -> e.id().equals(sent.get(4).id()))
                .expectNextMatches(e -> e.id().equals(sent.get(5).id()))
                .thenCancel()
                .verify(DEADLINE);

        assertThat(metrics.resetCount("inbox", "history_gap")).isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "GIVEN a stale cursor and more pending events than max-queued WHEN the session resumes SHOULD emit reset, then overflow, then the newest events")
    void given_stale_cursor_and_overflow_when_session_resumes_should_order_reset_overflow_events() {
        List<PushEvent> sent = publishInbox(6);
        store.ack("c1@-", inbox, sent.get(0).id(), Duration.ofHours(1)).block();

        StepVerifier.create(streams.topicStream(inbox, ns("inbox"), Qos.AT_LEAST_ONCE, null, "c1@-"))
                .assertNext(e -> assertThat(e.attributes()).containsEntry(PushEvent.SIGNAL, "reset"))
                .assertNext(e -> assertThat(e.attributes()).containsEntry(PushEvent.SIGNAL, "overflow"))
                .expectNextMatches(e -> e.id().equals(sent.get(4).id()))
                .expectNextMatches(e -> e.id().equals(sent.get(5).id()))
                .thenCancel()
                .verify(DEADLINE);

        assertThat(store.cursor("c1@-", inbox).block(DEADLINE))
                .isEqualTo(sent.get(3).id());
    }

    @Test
    @DisplayName("GIVEN a session cursor at the last trimmed event WHEN it resumes SHOULD not emit a reset")
    void given_session_cursor_at_last_trimmed_event_when_resuming_should_not_emit_reset() {
        List<PushEvent> sent = publishInbox(6);
        store.ack("c1@-", inbox, sent.get(3).id(), Duration.ofHours(1)).block();

        StepVerifier.create(streams.topicStream(inbox, ns("inbox"), Qos.AT_LEAST_ONCE, null, "c1@-"))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(4).id()))
                .expectNextMatches(e -> !e.signal() && e.id().equals(sent.get(5).id()))
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName("GIVEN a new session with no cursor WHEN it subscribes SHOULD not emit a reset")
    void given_new_session_when_subscribing_should_not_emit_reset() {
        publishInbox(6);

        StepVerifier.create(streams.topicStream(inbox, ns("inbox"), Qos.AT_LEAST_ONCE, null, "fresh@-"))
                .then(() -> publish(inbox, "{\"n\":7}"))
                .assertNext(e -> assertThat(e.signal()).isFalse())
                .thenCancel()
                .verify(DEADLINE);
    }

    @Test
    @DisplayName(
            "GIVEN a reset signal WHEN built SHOULD carry no id and no origin, so it is neither a resume point nor filtered")
    void given_reset_signal_when_built_should_have_no_id_and_no_origin() {
        PushEvent reset = PushEvent.resetSignal(topic, "history_gap");

        assertThat(reset.id()).isEmpty();
        assertThat(reset.attributes()).doesNotContainKey(PushOrigin.ATTRIBUTE);
        assertThat(reset.signal()).isTrue();
        assertThat(PushOrigin.matches(reset, "A")).isFalse();
    }

    private List<PushEvent> publishOrders(int n) {
        List<PushEvent> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(publish(topic, "{\"n\":" + i + "}"));
        return out;
    }

    private List<PushEvent> publishInbox(int n) {
        List<PushEvent> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(publish(inbox, "{\"n\":" + i + "}"));
        return out;
    }

    private PushEvent publish(Topic target, String json) {
        return publisher
                .publish(target, json.getBytes(StandardCharsets.UTF_8), Map.of(), null)
                .block();
    }

    private PushProperties.Namespace ns(String name) {
        return properties.getNamespaces().get(name);
    }

    private static String text(PushEvent event) {
        return new String(event.data(), StandardCharsets.UTF_8);
    }
}
