package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("Persistent push sessions: session keys, start position, overflow, acknowledgements and construction")
class PushSessionsTest {

    private static final Topic INBOX_ALICE = Topic.parse("inbox:alice");
    private static final Topic ALERTS_X = Topic.parse("alerts:x");

    private final PushProperties props = new PushProperties();
    private final MemoryPushStore store = new MemoryPushStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PushMetrics metrics = new PushMetrics(registry);
    private final PushSessions sessions;
    private final Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
    private final TopicAuthorizer authorizer = new TopicAuthorizer(null);

    PushSessionsTest() {
        PushProperties.Namespace inbox = new PushProperties.Namespace();
        inbox.setQos(Qos.AT_LEAST_ONCE);
        inbox.setHistorySize(10);
        inbox.setSessionExpiry(Duration.ofHours(1));
        inbox.setSessionKey(PushProperties.SessionKey.PRINCIPAL);
        inbox.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);
        props.getNamespaces().put("inbox", inbox);

        PushProperties.Namespace alerts = new PushProperties.Namespace();
        alerts.setQos(Qos.AT_LEAST_ONCE);
        alerts.setHistorySize(10);
        alerts.setSessionExpiry(Duration.ofHours(1));
        alerts.setSessionKey(PushProperties.SessionKey.CLIENT);
        alerts.setMaxQueued(3);
        alerts.setAuth(PushProperties.SubscribeAuth.NONE);
        props.getNamespaces().put("alerts", alerts);

        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        sessions = new PushSessions(props, store, metrics);
    }

    @Test
    @DisplayName(
            "GIVEN a principal-keyed namespace WHEN the session key is computed SHOULD name the session after the principal and be null for anonymous callers or a namespace without sessions")
    void given_principal_keyed_namespace_when_key_computed_should_name_session_after_principal() {
        PushProperties.Namespace inbox = props.getNamespaces().get("inbox");

        assertThat(sessions.key(inbox, alice, null)).isEqualTo("alice");
        assertThat(sessions.key(inbox, null, null)).isNull();
        assertThat(sessions.key(props.getNamespaces().get("ticker"), alice, null))
                .isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a client-keyed namespace WHEN the session key is computed SHOULD scope the client session to the principal, or to '-' when anonymous")
    void given_client_keyed_namespace_when_key_computed_should_scope_client_session_to_principal() {
        PushProperties.Namespace alerts = props.getNamespaces().get("alerts");

        assertThat(sessions.key(alerts, alice, "dev1")).isEqualTo("dev1@alice");
        assertThat(sessions.key(alerts, null, "dev1")).isEqualTo("dev1@-");
        assertThat(sessions.key(alerts, alice, null)).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a client session id with characters outside the allowed set WHEN the session key is computed SHOULD refuse it with bad_request")
    void given_invalid_client_session_when_key_computed_should_refuse_with_bad_request() {
        PushProperties.Namespace alerts = props.getNamespaces().get("alerts");

        assertThatThrownBy(() -> sessions.key(alerts, alice, "bad session!"))
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.code()).isEqualTo("bad_request"));
    }

    @Test
    @DisplayName(
            "GIVEN a new session on a topic with three events in history WHEN it starts and later restarts from an older id SHOULD start at the head and never move the cursor back")
    void given_new_session_with_history_when_started_then_restarted_from_older_id_should_stay_at_head() {
        PushProperties.Namespace inbox = props.getNamespaces().get("inbox");
        publish("inbox:alice");
        PushEvent second = publish("inbox:alice");
        PushEvent third = publish("inbox:alice");

        PushSessions.Start first =
                sessions.start(inbox, "alice", INBOX_ALICE, null).block();
        PushSessions.Start restarted =
                sessions.start(inbox, "alice", INBOX_ALICE, second.id()).block();

        assertThat(first.afterId()).isEqualTo(third.id());
        assertThat(first.dropped()).isZero();
        assertThat(restarted.afterId()).isEqualTo(third.id());
        assertThat(store.cursor("alice", INBOX_ALICE).block()).isEqualTo(third.id());
    }

    @Test
    @DisplayName(
            "GIVEN a session acknowledged its first event and three more were published WHEN it starts SHOULD resume after the acknowledged event with nothing dropped")
    void given_acknowledged_session_when_more_events_published_and_started_should_resume_after_acknowledged_event() {
        PushProperties.Namespace inbox = props.getNamespaces().get("inbox");
        PushEvent acknowledged = publish("inbox:alice");
        sessions.ack(alice, null, "inbox:alice", acknowledged.id(), authorizer).block();
        publish("inbox:alice");
        publish("inbox:alice");
        publish("inbox:alice");

        PushSessions.Start start =
                sessions.start(inbox, "alice", INBOX_ALICE, null).block();

        assertThat(start.afterId()).isEqualTo(acknowledged.id());
        assertThat(start.dropped()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a session behind six pending events on a namespace with max-queued three WHEN it resumes SHOULD drop the three oldest, move its cursor past them and count them as session overflow")
    void given_session_behind_six_pending_events_when_resumed_should_drop_oldest_and_count_overflow() {
        PushProperties.Namespace alerts = props.getNamespaces().get("alerts");
        PushEvent acknowledged = publish("alerts:x");
        sessions.ack(null, "dev1", "alerts:x", acknowledged.id(), authorizer).block();
        List<PushEvent> later = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            later.add(publish("alerts:x"));
        }
        String lastDropped = later.get(2).id();

        PushSessions.Start start =
                sessions.start(alerts, "dev1@-", ALERTS_X, null).block();

        assertThat(start.dropped()).isEqualTo(3);
        assertThat(start.afterId()).isEqualTo(lastDropped);
        assertThat(store.cursor("dev1@-", ALERTS_X).block()).isEqualTo(lastDropped);
        assertThat(registry.get("shiguchi.cursus.events.dropped")
                        .tag("reason", "session_overflow")
                        .counter()
                        .count())
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName(
            "GIVEN a new session on a topic with no history yet WHEN it starts and events are then published SHOULD start from the first id and replay every later event")
    void given_new_session_on_topic_without_history_when_started_should_replay_every_later_event() {
        PushProperties.Namespace inbox = props.getNamespaces().get("inbox");
        Topic news = Topic.parse("inbox:news");

        PushSessions.Start start = sessions.start(inbox, "alice", news, null).block();
        PushEvent first = publish("inbox:news");
        PushEvent second = publish("inbox:news");

        assertThat(start.afterId()).isEqualTo(store.firstId());
        List<String> replayed = store.range(news, store.firstId(), 10)
                .map(PushEvent::id)
                .collectList()
                .block();
        assertThat(replayed).containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName(
            "GIVEN an authenticated caller on a persistent namespace WHEN they acknowledge an event SHOULD move their cursor to it and count the acknowledgement")
    void given_authenticated_caller_when_acknowledges_event_should_move_cursor_and_count_ack() {
        PushEvent event = publish("inbox:alice");

        sessions.ack(alice, null, "inbox:alice", event.id(), authorizer).block();

        assertThat(store.cursor("alice", INBOX_ALICE).block()).isEqualTo(event.id());
        assertThat(metrics.acksCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "GIVEN a store that keeps no sessions WHEN a namespace with session-expiry is declared SHOULD refuse to start naming session-expiry")
    void given_store_without_sessions_when_namespace_has_session_expiry_should_refuse_to_start() {
        PushProperties withSessions = new PushProperties();
        PushProperties.Namespace inbox = new PushProperties.Namespace();
        inbox.setSessionExpiry(Duration.ofHours(1));
        withSessions.getNamespaces().put("inbox", inbox);

        assertThatThrownBy(() -> new PushSessions(withSessions, new NoSessionsStore(), metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-expiry");
    }

    private PushEvent publish(String topic) {
        PushEvent event = new PushEvent(
                Topic.parse(topic), null, "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null, Instant.now());
        return store.append(event, 10, null).block();
    }

    /** A store that keeps no sessions: every session method keeps the PushStore default. */
    private static final class NoSessionsStore implements PushStore {

        @Override
        public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
            return Mono.empty();
        }

        @Override
        public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
            return Flux.empty();
        }

        @Override
        public int compareIds(String a, String b) {
            return a.compareTo(b);
        }

        @Override
        public long historyLength(Topic topic) {
            return 0;
        }

        @Override
        public PushStore.Capabilities capabilities() {
            return new PushStore.Capabilities(false, false);
        }
    }
}
