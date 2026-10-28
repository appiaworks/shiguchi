package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

@DisplayName("Push topic streams with echo suppression (origin)")
class PushStreamsOriginTest {

    private final PushProperties properties = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushShutdown shutdown = new PushShutdown();
    private final PushConnections connections = new PushConnections();
    private final Topic topic = Topic.parse("orders:1");
    private final PushPublisher publisher;
    private final PushStreams streams;
    private final PushSessions sessions;
    private final java.util.ArrayList<Disposable> open = new java.util.ArrayList<>();

    PushStreamsOriginTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(50);
        properties.getNamespaces().put("orders", orders);

        PushProperties.Namespace jobs = new PushProperties.Namespace();
        jobs.setQos(Qos.AT_LEAST_ONCE);
        jobs.setHistorySize(50);
        jobs.setSessionExpiry(Duration.ofHours(1));
        jobs.setSessionKey(PushProperties.SessionKey.GROUP);
        jobs.setAckDeadline(Duration.ofSeconds(30));
        properties.getNamespaces().put("jobs", jobs);

        publisher = new PushPublisher(properties, store, broker, metrics);
        sessions = new PushSessions(properties, store, metrics);
        sessions.setGroups(new PushGroups(properties, store, metrics, new PushDlq(10)));
        streams = new PushStreams(broker, store, properties, shutdown, null, sessions);
    }

    @AfterEach
    void closeStreams() {
        open.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber that declared origin A WHEN an event stamped A and one stamped B are published live SHOULD deliver only the one stamped B and count the suppressed one")
    void given_declared_origin_when_live_events_published_should_skip_the_own_origin() {
        StepVerifier.create(streams.topicStream(topic, ns("orders"), Qos.AT_LEAST_ONCE, null, "A", metrics))
                .then(() -> publish("{\"n\":1}", "A"))
                .then(() -> publish("{\"n\":2}", "B"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":2}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(metrics.suppressedCount("orders")).isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber that declared no origin WHEN events stamped A and unstamped are published SHOULD deliver both")
    void given_no_declared_origin_when_events_published_should_deliver_everything() {
        StepVerifier.create(streams.topicStream(topic, ns("orders"), Qos.AT_LEAST_ONCE, null, null, metrics))
                .then(() -> publish("{\"n\":1}", "A"))
                .then(() -> publish("{\"n\":2}", null))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":1}"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":2}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(metrics.suppressedCount("orders")).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN kept events from origins A, B and A WHEN a subscriber of origin A resumes before them SHOULD replay only the one stamped B")
    void given_kept_events_when_subscriber_of_origin_resumes_should_replay_only_foreign_events() {
        PushEvent first = publish("{\"n\":0}", null);
        publish("{\"n\":1}", "A");
        publish("{\"n\":2}", "B");
        publish("{\"n\":3}", "A");

        StepVerifier.create(streams.topicStream(topic, ns("orders"), Qos.AT_LEAST_ONCE, first.id(), "A", metrics))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":2}"))
                .expectNoEvent(Duration.ofMillis(200))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(metrics.suppressedCount("orders")).isEqualTo(2.0);
    }

    @Test
    @DisplayName(
            "GIVEN a replay that ends on a suppressed event WHEN a live event follows SHOULD deliver it exactly once, the resume position having moved past the suppressed one")
    void given_replay_ending_on_suppressed_event_when_live_event_follows_should_deliver_it_once() {
        PushEvent first = publish("{\"n\":0}", null);
        publish("{\"n\":1}", "A");

        StepVerifier.create(streams.topicStream(topic, ns("orders"), Qos.AT_LEAST_ONCE, first.id(), "A", metrics))
                .then(() -> publish("{\"n\":2}", "B"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":2}"))
                .expectNoEvent(Duration.ofMillis(200))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN a merged subscription with a caller origin WHEN events from the same and another origin are published SHOULD deliver only the other")
    void given_merged_caller_origin_when_events_published_should_skip_the_own_origin() {
        PushStreams.Merged merged = streams.merged(
                List.of(topic), null, null, metrics, null, new PushStreams.Caller(null, null, null, null, "A"));

        StepVerifier.create(merged.events())
                .then(() -> publish("{\"n\":1}", "A"))
                .then(() -> publish("{\"n\":2}", "B"))
                .assertNext(e -> assertThat(text(e)).isEqualTo("{\"n\":2}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN a consumer-group member that declared an origin WHEN an event stamped with that origin is published SHOULD still hand it to the group, the origin being ignored")
    void given_group_member_with_origin_when_event_of_that_origin_published_should_still_deliver_it()
            throws InterruptedException {
        Topic jobs = Topic.parse("jobs:q");
        PushStreams.Merged merged = streams.merged(
                List.of(jobs), null, null, metrics, null, new PushStreams.Caller(null, null, "workers", "m1", "A"));
        List<PushEvent> received = new CopyOnWriteArrayList<>();
        open.add(merged.events().subscribe(received::add));
        Thread.sleep(200);

        publisher
                .publishAsOperator(
                        jobs, "{\"n\":1}".getBytes(StandardCharsets.UTF_8), Map.of(), null, null, null, "A", null)
                .block();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (received.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).attributes()).containsEntry(PushOrigin.ATTRIBUTE, "A");
        assertThat(metrics.suppressedCount("jobs")).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a connection that declared origin A WHEN a directly sent event stamped A is delivered SHOULD answer SUPPRESSED without queueing it, and deliver one stamped B")
    void given_connection_origin_when_direct_event_of_that_origin_sent_should_answer_suppressed() {
        PushConnections.Live live = connections.open("sse", null);
        live.origin("A");
        List<PushEvent> written = new CopyOnWriteArrayList<>();
        open.add(live.direct().subscribe(e -> {
            written.add(e);
            live.delivered(e);
        }));

        PushSender.Status own =
                live.deliverDirect(direct("A"), false, Duration.ofSeconds(1)).block(Duration.ofSeconds(5));
        PushSender.Status other =
                live.deliverDirect(direct("B"), false, Duration.ofSeconds(1)).block(Duration.ofSeconds(5));

        assertThat(own).isEqualTo(PushSender.Status.SUPPRESSED);
        assertThat(other).isEqualTo(PushSender.Status.DELIVERED);
        assertThat(written).hasSize(1);
        assertThat(PushSender.toProto(PushSender.Status.SUPPRESSED))
                .isEqualTo(dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus.Status.SUPPRESSED);
        assertThat(PushSender.fromProto(dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus.Status.SUPPRESSED))
                .isEqualTo(PushSender.Status.SUPPRESSED);
    }

    @Test
    @DisplayName(
            "GIVEN an origin with a character outside the grammar WHEN it is parsed SHOULD refuse it with bad_request, and a blank one SHOULD mean none")
    void given_invalid_origin_when_parsed_should_refuse_and_blank_should_mean_none() {
        assertThat(PushOrigin.parse("  ")).isNull();
        assertThat(PushOrigin.parse(null)).isNull();
        assertThat(PushOrigin.parse(" dev-1.a:b@c_d ")).isEqualTo("dev-1.a:b@c_d");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PushOrigin.parse("bad origin!"))
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.code()).isEqualTo("bad_request"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PushOrigin.parse("x".repeat(129)))
                .isInstanceOf(PushException.class);
    }

    private PushEvent direct(String origin) {
        return new PushEvent(
                new Topic("orders", "c"),
                java.util.UUID.randomUUID().toString(),
                "{}".getBytes(StandardCharsets.UTF_8),
                Map.of(PushConnections.DIRECT, "true", PushOrigin.ATTRIBUTE, origin),
                null,
                Instant.now());
    }

    private PushProperties.Namespace ns(String name) {
        return properties.getNamespaces().get(name);
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
