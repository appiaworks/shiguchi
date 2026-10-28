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

@DisplayName("Push persistent sessions with echo suppression (origin)")
class PushStreamsOriginSessionTest {

    private static final Duration HOUR = Duration.ofHours(1);

    private final PushProperties properties = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final Topic topic = Topic.parse("inbox:alice");
    private final PushPublisher publisher;
    private final PushStreams streams;

    PushStreamsOriginSessionTest() {
        PushProperties.Namespace inbox = new PushProperties.Namespace();
        inbox.setQos(Qos.AT_LEAST_ONCE);
        inbox.setHistorySize(20);
        inbox.setSessionExpiry(HOUR);
        inbox.setSessionKey(PushProperties.SessionKey.PRINCIPAL);
        inbox.setMaxQueued(3);
        properties.getNamespaces().put("inbox", inbox);

        publisher = new PushPublisher(properties, store, broker, metrics);
        PushSessions sessions = new PushSessions(properties, store, metrics);
        streams = new PushStreams(broker, store, properties, new PushShutdown(), null, sessions);
    }

    @Test
    @DisplayName(
            "GIVEN more own-origin events than max-queued published while the session was away WHEN it reconnects SHOULD deliver nothing, send no overflow signal and leave the cursor on the last of them")
    void given_trailing_echoes_over_max_queued_when_session_reconnects_should_skip_them_without_overflow() {
        connectOnceAtHead();
        List<PushEvent> echoes = new ArrayList<>();
        for (int n = 1; n <= 5; n++) echoes.add(publish("{\"n\":" + n + "}", "dev1"));

        StepVerifier.create(streams.topicStream(topic, ns(), Qos.AT_LEAST_ONCE, null, "alice", "dev1", metrics))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(300))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(store.cursor("alice", topic).block()).isEqualTo(echoes.get(4).id());
        assertThat(metrics.suppressedCount("inbox")).isEqualTo(5.0);

        // And a later reconnect sees neither the echoes nor an overflow.
        StepVerifier.create(streams.topicStream(topic, ns(), Qos.AT_LEAST_ONCE, null, "alice", "dev1", metrics))
                .then(() -> publish("{\"n\":6}", "other"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":6}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN an unacknowledged foreign event followed by own-origin events WHEN the session reconnects twice SHOULD replay the foreign event each time, never the echoes, and keep the cursor before it")
    void given_unacked_foreign_event_then_echoes_when_session_reconnects_should_still_replay_the_foreign_event() {
        connectOnceAtHead();
        String head = store.cursor("alice", topic).block();
        PushEvent foreign = publish("{\"n\":1}", "other");
        publish("{\"n\":2}", "dev1");
        publish("{\"n\":3}", "dev1");

        for (int attempt = 1; attempt <= 2; attempt++) {
            StepVerifier.create(streams.topicStream(topic, ns(), Qos.AT_LEAST_ONCE, null, "alice", "dev1", metrics))
                    .assertNext(e -> assertThat(e.id()).isEqualTo(foreign.id()))
                    .expectNoEvent(Duration.ofMillis(200))
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));
            assertThat(store.cursor("alice", topic).block()).isEqualTo(head);
        }
    }

    @Test
    @DisplayName(
            "GIVEN own-origin events followed by a foreign one WHEN the session starts SHOULD move the cursor over the leading echoes only and deliver the foreign event")
    void given_leading_echoes_then_foreign_event_when_session_starts_should_ack_only_the_leading_run() {
        connectOnceAtHead();
        publish("{\"n\":1}", "dev1");
        PushEvent lastEcho = publish("{\"n\":2}", "dev1");
        PushEvent foreign = publish("{\"n\":3}", "other");

        StepVerifier.create(streams.topicStream(topic, ns(), Qos.AT_LEAST_ONCE, null, "alice", "dev1", metrics))
                .assertNext(e -> assertThat(e.id()).isEqualTo(foreign.id()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(store.cursor("alice", topic).block()).isEqualTo(lastEcho.id());
    }

    @Test
    @DisplayName(
            "GIVEN a session subscriber that declared no origin WHEN stamped events are pending SHOULD replay them all and move no cursor")
    void given_session_without_origin_when_stamped_events_pending_should_replay_all_of_them() {
        connectOnceAtHead();
        String head = store.cursor("alice", topic).block();
        PushEvent a = publish("{\"n\":1}", "dev1");
        PushEvent b = publish("{\"n\":2}", "other");

        StepVerifier.create(streams.topicStream(topic, ns(), Qos.AT_LEAST_ONCE, null, "alice", null, metrics))
                .assertNext(e -> assertThat(e.id()).isEqualTo(a.id()))
                .assertNext(e -> assertThat(e.id()).isEqualTo(b.id()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(store.cursor("alice", topic).block()).isEqualTo(head);
    }

    private void connectOnceAtHead() {
        publish("{\"n\":0}", null);
        StepVerifier.create(streams.topicStream(topic, ns(), Qos.AT_LEAST_ONCE, null, "alice", "dev1", metrics))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    private PushProperties.Namespace ns() {
        return properties.getNamespaces().get("inbox");
    }

    private PushEvent publish(String json, String origin) {
        return publisher
                .publishAsOperator(
                        topic, json.getBytes(StandardCharsets.UTF_8), Map.of(), null, null, null, origin, null)
                .block();
    }

    private static String text(PushEvent event) {
        return new String(event.data(), StandardCharsets.UTF_8);
    }
}
