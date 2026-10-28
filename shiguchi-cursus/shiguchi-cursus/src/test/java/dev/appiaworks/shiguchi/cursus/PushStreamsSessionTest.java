package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

@DisplayName("Push topic streams with persistent sessions")
class PushStreamsSessionTest {

    private static final String NS = "inbox";

    private final PushProperties properties = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushShutdown shutdown = new PushShutdown();
    private final Topic topic = Topic.parse("inbox:alice");
    private final Authentication alice = authenticated("alice");
    private final PushPublisher publisher;
    private final PushStreams streams;

    PushStreamsSessionTest() {
        // A persistent namespace: AT_LEAST_ONCE with history, sessions keyed by the principal.
        PushProperties.Namespace inbox = new PushProperties.Namespace();
        inbox.setQos(Qos.AT_LEAST_ONCE);
        inbox.setHistorySize(10);
        inbox.setSessionExpiry(Duration.ofHours(1));
        inbox.setSessionKey(PushProperties.SessionKey.PRINCIPAL);
        inbox.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);
        inbox.setMaxQueued(3);
        properties.getNamespaces().put(NS, inbox);

        this.publisher = new PushPublisher(properties, store, broker, metrics);
        PushSessions sessions = new PushSessions(properties, store, metrics);
        this.streams = new PushStreams(broker, store, properties, shutdown, null, sessions);
    }

    @Test
    @DisplayName(
            "GIVEN a new session with no history WHEN an event is published after subscribing SHOULD deliver exactly that event and start the cursor at the head")
    void
            given_new_session_without_history_when_event_published_after_subscribe_should_deliver_it_and_start_cursor_at_head() {
        StepVerifier.create(streams.topicStream(topic, namespace(), Qos.AT_LEAST_ONCE, null, "alice"))
                .then(() -> publish("{\"n\":1}"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":1}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(store.cursor("alice", topic).blockOptional()).contains(store.firstId());
    }

    @Test
    @DisplayName(
            "GIVEN events published before a new session subscribes WHEN it subscribes SHOULD not replay them and SHOULD deliver only later events")
    void given_events_before_new_session_when_session_subscribes_should_not_replay_them() throws InterruptedException {
        publish("{\"n\":1}");
        publish("{\"n\":2}");

        List<PushEvent> received = new CopyOnWriteArrayList<>();
        Disposable subscription = streams.topicStream(topic, namespace(), Qos.AT_LEAST_ONCE, null, "alice")
                .subscribe(received::add);
        try {
            Thread.sleep(300);
            assertThat(received).isEmpty();

            publish("{\"n\":3}");
            awaitSize(received, 1);

            assertThat(received)
                    .singleElement()
                    .satisfies(e -> assertThat(text(e)).isEqualTo("{\"n\":3}"));
        } finally {
            subscription.dispose();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a session acknowledged past its first event WHEN it resumes SHOULD replay the later events oldest first, then deliver live events")
    void given_session_acknowledged_past_first_event_when_resumed_should_replay_later_events_then_stream_live() {
        PushEvent e1 = publish("{\"n\":1}");
        PushEvent e2 = publish("{\"n\":2}");
        PushEvent e3 = publish("{\"n\":3}");
        store.ack("alice", topic, e1.id(), Duration.ofHours(1)).block();

        StepVerifier.create(streams.topicStream(topic, namespace(), Qos.AT_LEAST_ONCE, null, "alice"))
                .expectNextMatches(e -> e.id().equals(e2.id()))
                .expectNextMatches(e -> e.id().equals(e3.id()))
                .then(() -> publish("{\"n\":4}"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":4}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN a session and an explicit resume position WHEN subscribed SHOULD acknowledge the position first and replay only the events after it")
    void given_session_and_resume_position_when_subscribed_should_acknowledge_position_and_replay_after_it() {
        publish("{\"n\":1}");
        PushEvent e2 = publish("{\"n\":2}");
        PushEvent e3 = publish("{\"n\":3}");

        StepVerifier.create(streams.topicStream(topic, namespace(), Qos.AT_LEAST_ONCE, e2.id(), "alice"))
                .expectNextMatches(e -> e.id().equals(e3.id()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(store.cursor("alice", topic).blockOptional()).contains(e2.id());
    }

    @Test
    @DisplayName(
            "GIVEN a session whose pending events exceed max-queued WHEN it resumes SHOULD signal the overflow, drop the oldest, and deliver the newest three")
    void given_pending_events_over_max_queued_when_session_resumes_should_signal_overflow_and_deliver_newest() {
        PushEvent e1 = publish("{\"n\":1}");
        store.ack("alice", topic, e1.id(), Duration.ofHours(1)).block();
        List<PushEvent> later = new ArrayList<>();
        for (int n = 2; n <= 7; n++) {
            later.add(publish("{\"n\":" + n + "}"));
        }

        StepVerifier.create(streams.topicStream(topic, namespace(), Qos.AT_LEAST_ONCE, null, "alice"))
                .assertNext(signal -> {
                    assertThat(signal.signal()).isTrue();
                    assertThat(signal.attributes()).containsEntry("dropped", "3");
                    assertThat(text(signal)).contains("session_overflow");
                })
                .expectNextMatches(e -> e.id().equals(later.get(3).id()))
                .expectNextMatches(e -> e.id().equals(later.get(4).id()))
                .expectNextMatches(e -> e.id().equals(later.get(5).id()))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(store.cursor("alice", topic).blockOptional())
                .contains(later.get(2).id());
    }

    @Test
    @DisplayName(
            "GIVEN no session name WHEN subscribed SHOULD replay nothing, deliver only live events, and set no cursor")
    void given_no_session_name_when_subscribed_should_replay_nothing_and_set_no_cursor() {
        publish("{\"n\":1}");

        StepVerifier.create(streams.topicStream(topic, namespace(), Qos.AT_LEAST_ONCE, null, null))
                .then(() -> publish("{\"n\":2}"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":2}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(store.cursor("alice", topic).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a merged subscription WHEN the caller is a principal SHOULD keep a cursor for it, and WHEN the caller is anonymous SHOULD keep no session at all")
    void given_merged_subscription_when_caller_is_principal_or_anonymous_should_keep_cursor_only_for_principal() {
        PushStreams.Merged anonymous = streams.merged(List.of(topic), null, null, metrics, null, null, null);
        StepVerifier.create(anonymous.events())
                .then(() -> assertThat(store.sessions(null, 10).collectList().block())
                        .isEmpty())
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        PushStreams.Merged principal = streams.merged(List.of(topic), null, null, metrics, null, alice, null);
        assertThat(principal.atLeastOnce()).isTrue();
        StepVerifier.create(principal.events())
                .then(() ->
                        assertThat(store.cursor("alice", topic).blockOptional()).isPresent())
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    private PushProperties.Namespace namespace() {
        return properties.getNamespaces().get(NS);
    }

    private PushEvent publish(String json) {
        return publisher
                .publish(topic, json.getBytes(StandardCharsets.UTF_8), Map.of(), null)
                .block();
    }

    private static String text(PushEvent event) {
        return new String(event.data(), StandardCharsets.UTF_8);
    }

    private static void awaitSize(List<PushEvent> events, int size) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (events.size() < size && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static Authentication authenticated(String name) {
        return new UsernamePasswordAuthenticationToken(name, "pwd", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
