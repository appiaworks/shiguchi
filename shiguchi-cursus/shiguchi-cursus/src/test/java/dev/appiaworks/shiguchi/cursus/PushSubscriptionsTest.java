package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.test.StepVerifier;

@DisplayName("Push subscriptions for in-process transports")
class PushSubscriptionsTest {

    private final PushProperties properties = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushShutdown shutdown = new PushShutdown();
    private final PushConnections connections = new PushConnections();
    private final PushPublisher publisher;
    private final PushSubscriptions subscriptions;

    PushSubscriptionsTest() {
        // No connection lifetime, so only shutdown, admin disconnect or the caller end a stream.
        properties.getConnection().setMaxLifetime(Duration.ZERO);

        // Fire-and-forget namespace, open to anyone.
        properties.getNamespaces().put("ticker", new PushProperties.Namespace());

        // Resumable namespace with a small client queue, so overflow is easy to reach.
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        orders.setClientQueueSize(2);
        properties.getNamespaces().put("orders", orders);

        // Namespace that only authenticated callers may subscribe to.
        PushProperties.Namespace secure = new PushProperties.Namespace();
        secure.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);
        properties.getNamespaces().put("secure", secure);

        this.publisher = new PushPublisher(properties, store, broker, metrics);
        this.subscriptions = new PushSubscriptions(
                properties, broker, store, shutdown, new TopicAuthorizer(null), connections, metrics);
    }

    @Test
    @DisplayName(
            "GIVEN a live subscription WHEN an event is published SHOULD deliver it, count it, and track the connection")
    void given_live_subscription_when_event_published_should_deliver_and_track_connection() {
        StepVerifier.create(subscriptions.open("graphql", null, List.of("ticker:btc"), null, null))
                .then(() -> {
                    assertThat(connections.list(i -> true)).singleElement().satisfies(info -> {
                        assertThat(info.transport()).isEqualTo("graphql");
                        assertThat(info.topics()).containsExactly("ticker:btc");
                    });
                    assertThat(metrics.connections("graphql")).isEqualTo(1);
                    publish("ticker:btc", "{}");
                })
                .assertNext(e -> assertThat(e.topic()).hasToString("ticker:btc"))
                .then(() -> assertThat(metrics.deliveredCount()).isEqualTo(1))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(metrics.connections("graphql")).isZero();
        assertThat(connections.size()).isZero();
    }

    @Test
    @DisplayName("GIVEN a topic in an undeclared namespace WHEN opened SHOULD error with unknown_namespace")
    void given_undeclared_namespace_when_opened_should_error_unknown_namespace() {
        StepVerifier.create(subscriptions.open("graphql", null, List.of("nope:x"), null, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PushException.class, pe -> {
                    assertThat(pe.code()).isEqualTo("unknown_namespace");
                }))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN a topic without a namespace separator WHEN opened SHOULD error with bad_request")
    void given_malformed_topic_when_opened_should_error_bad_request() {
        StepVerifier.create(subscriptions.open("graphql", null, List.of("nocolon"), null, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PushException.class, pe -> {
                    assertThat(pe.code()).isEqualTo("bad_request");
                }))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN an AUTHENTICATED namespace WHEN opened anonymously SHOULD refuse with 401, and WHEN opened by an authenticated caller SHOULD deliver")
    void given_authenticated_namespace_when_opened_anonymously_should_refuse_and_authenticated_should_deliver() {
        StepVerifier.create(subscriptions.open("graphql", null, List.of("secure:x"), null, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PushException.class, pe -> {
                    assertThat(pe.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(pe.code()).isEqualTo("unauthenticated");
                }))
                .verify(Duration.ofSeconds(5));

        StepVerifier.create(subscriptions.open("graphql", authenticated("alice"), List.of("secure:x"), null, null))
                .then(() -> publish("secure:x", "{}"))
                .assertNext(e -> assertThat(e.topic()).hasToString("secure:x"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN three events in history WHEN resumed after the first SHOULD replay the second and third, then live events")
    void given_events_in_history_when_resumed_after_first_should_replay_then_stream_live() {
        PushEvent first = publish("orders:1", "{\"n\":1}");
        PushEvent second = publish("orders:1", "{\"n\":2}");
        PushEvent third = publish("orders:1", "{\"n\":3}");
        // The in-memory broker delivers on the publishing thread, inside publish(): the live event is
        // signalled before the task returns, so it is matched by its payload, not by a stored id.
        StepVerifier.create(subscriptions.open("graphql", null, List.of("orders:1"), null, first.id()))
                .expectNextMatches(e -> e.id().equals(second.id()))
                .expectNextMatches(e -> e.id().equals(third.id()))
                .then(() -> publish("orders:1", "{\"n\":4}"))
                .expectNextMatches(e -> new String(e.data(), StandardCharsets.UTF_8).equals("{\"n\":4}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN a resume id the store cannot parse WHEN opened SHOULD error with bad_request")
    void given_unparsable_resume_id_when_opened_should_error_bad_request() {
        StepVerifier.create(subscriptions.open("graphql", null, List.of("orders:1"), null, "not-an-id"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PushException.class, pe -> {
                    assertThat(pe.code()).isEqualTo("bad_request");
                }))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN an open subscription WHEN an operator disconnects it SHOULD complete the stream")
    void given_open_subscription_when_operator_disconnects_should_complete_stream() {
        StepVerifier.create(subscriptions.open("graphql", null, List.of("ticker:btc"), null, null))
                .then(() -> {
                    String id = connections.list(i -> true).get(0).id();
                    assertThat(connections.disconnect(id, "admin")).isTrue();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN an open subscription WHEN the instance starts stopping SHOULD complete the stream")
    void given_open_subscription_when_instance_stopping_should_complete_stream() {
        StepVerifier.create(subscriptions.open("graphql", null, List.of("ticker:btc"), null, null))
                .then(shutdown::stop)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN an AT_LEAST_ONCE subscriber that stopped reading WHEN more events arrive than its queue holds SHOULD error with overflow status 429")
    void given_at_least_once_subscriber_not_reading_when_queue_fills_should_error_overflow() {
        // Requesting nothing models a subscriber that has stopped reading. The queue holds two; the
        // third event overflows it. The error is delayed until the queued events are read, so the
        // subscriber still gets what was queued, then the error, once it asks again.
        StepVerifier.create(subscriptions.open("graphql", null, List.of("orders:1"), null, null), 0)
                .then(() -> {
                    publish("orders:1", "{\"n\":1}");
                    publish("orders:1", "{\"n\":2}");
                    publish("orders:1", "{\"n\":3}");
                })
                .thenRequest(2)
                .expectNextCount(2)
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PushException.class, pe -> {
                    assertThat(pe.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(pe.code()).isEqualTo("overflow");
                }))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN more topics than connection.max-subscriptions WHEN opened SHOULD error with bad_request")
    void given_too_many_topics_when_opened_should_error_bad_request() {
        properties.getConnection().setMaxSubscriptions(2);

        StepVerifier.create(
                        subscriptions.open("graphql", null, List.of("ticker:a", "ticker:b", "ticker:c"), null, null))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PushException.class, pe -> {
                    assertThat(pe.code()).isEqualTo("bad_request");
                }))
                .verify(Duration.ofSeconds(5));
    }

    private PushEvent publish(String topic, String json) {
        return publisher
                .publish(Topic.parse(topic), json.getBytes(StandardCharsets.UTF_8), Map.of(), null)
                .block();
    }

    private static Authentication authenticated(String name) {
        TestingAuthenticationToken token =
                new TestingAuthenticationToken(name, "pwd", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        token.setAuthenticated(true);
        return token;
    }
}
