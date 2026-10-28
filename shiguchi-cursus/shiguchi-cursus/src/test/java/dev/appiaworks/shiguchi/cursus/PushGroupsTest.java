package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("Consumer groups: competing members, acknowledgements, redelivery and the dead-letter queue")
class PushGroupsTest {

    private static final String GROUP = "workers";
    private static final Topic TOPIC = Topic.parse("jobs:q");

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PushMetrics metrics = new PushMetrics(registry);
    private final PushDlq dlq = new PushDlq(100);
    private final PushPublisher publisher;
    private final PushGroups groups;
    private final TopicAuthorizer authorizer = new TopicAuthorizer(null);
    private final List<Disposable> streams = new ArrayList<>();

    PushGroupsTest() {
        PushProperties.Namespace jobs = new PushProperties.Namespace();
        jobs.setQos(Qos.AT_LEAST_ONCE);
        jobs.setHistorySize(100);
        jobs.setSessionExpiry(Duration.ofHours(1));
        jobs.setSessionKey(PushProperties.SessionKey.GROUP);
        jobs.setAckDeadline(Duration.ofMillis(300));
        jobs.setMaxDeliveries(2);
        jobs.setMaxInflight(2);
        props.getNamespaces().put("jobs", jobs);

        // A namespace without consumer groups, to be refused by the ack path.
        props.getNamespaces().put("ticker", new PushProperties.Namespace());

        this.publisher = new PushPublisher(props, store, broker, metrics);
        this.groups = new PushGroups(props, store, metrics, dlq);
    }

    @AfterEach
    void disposeMembers() {
        streams.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName(
            "GIVEN two members of one group WHEN four events are published SHOULD hand each event to exactly one member")
    void given_two_members_in_group_when_events_published_should_deliver_each_event_to_exactly_one_member() {
        List<PushEvent> m1 = new CopyOnWriteArrayList<>();
        List<PushEvent> m2 = new CopyOnWriteArrayList<>();
        member("m1", m1);
        member("m2", m2);

        List<String> published = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            published.add(publish("{\"n\":" + n + "}").id());
        }

        assertThat(awaitTrue(() -> ids(m1, m2).size() == 4, Duration.ofSeconds(2)))
                .isTrue();
        assertThat(ids(m1, m2)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(published);
    }

    @Test
    @DisplayName(
            "GIVEN one member with max-inflight 2 and five unacknowledged events WHEN one is acknowledged SHOULD hold two until the ack frees a slot for the third")
    void given_member_at_max_inflight_when_one_event_acknowledged_should_deliver_the_next_event() {
        List<PushEvent> m1 = new CopyOnWriteArrayList<>();
        member("m1", m1);

        List<String> published = new ArrayList<>();
        for (int n = 1; n <= 5; n++) {
            published.add(publish("{\"n\":" + n + "}").id());
        }

        assertThat(awaitTrue(() -> m1.size() == 2, Duration.ofSeconds(2))).isTrue();
        // Shorter than the ack deadline: past it the member's own expired claims come back to it first.
        pause(Duration.ofMillis(150));
        assertThat(m1).hasSize(2);

        groups.ack(null, GROUP, "m1", "jobs:q", m1.get(0).id(), authorizer).block();

        assertThat(awaitTrue(() -> m1.size() == 3, Duration.ofSeconds(1))).isTrue();
        assertThat(m1.get(2).id()).isEqualTo(published.get(2));
        assertThat(metrics.groupAcksCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "GIVEN a member that never acknowledges WHEN the ack deadline passes and another member subscribes SHOULD redeliver the event to the other member with deliveries 2")
    void given_member_never_acks_when_deadline_passes_and_other_member_subscribed_should_redeliver_with_deliveries() {
        // At its inflight cap the holder cannot reclaim its own expired claim, so the other member takes it.
        props.getNamespaces().get("jobs").setMaxInflight(1);
        List<PushEvent> m1 = new CopyOnWriteArrayList<>();
        List<PushEvent> m2 = new CopyOnWriteArrayList<>();
        member("m1", m1);
        PushEvent e1 = publish("{\"n\":1}");
        assertThat(awaitTrue(() -> m1.size() == 1, Duration.ofSeconds(2))).isTrue();

        member("m2", m2);

        assertThat(awaitTrue(() -> !m2.isEmpty(), Duration.ofSeconds(3))).isTrue();
        assertThat(m2.get(0).id()).isEqualTo(e1.id());
        assertThat(m2.get(0).attributes()).containsEntry("deliveries", "2");
        assertThat(outcome("redelivery")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "GIVEN a member that never acknowledges and max-deliveries 2 WHEN the event is delivered past that limit SHOULD dead-letter it and count the outcome")
    void given_member_never_acks_when_max_deliveries_exceeded_should_dead_letter_the_event() {
        List<PushEvent> m1 = new CopyOnWriteArrayList<>();
        member("m1", m1);

        publish("{\"n\":1}");

        assertThat(awaitTrue(() -> dlq.size() == 1, Duration.ofSeconds(4))).isTrue();
        PushDlq.Entry entry = dlq.list(null, 10).get(0);
        assertThat(entry.source()).isEqualTo("group:workers");
        assertThat(entry.reason()).contains("max-deliveries 2");
        assertThat(m1.size()).isLessThanOrEqualTo(2);
        assertThat(outcome("dead_lettered")).isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "GIVEN a member holding an unacknowledged event WHEN it leaves and another member is subscribed SHOULD release the event to that member at once with deliveries 2")
    void given_member_holding_event_when_it_leaves_should_release_event_to_another_member() {
        List<PushEvent> m1 = new CopyOnWriteArrayList<>();
        List<PushEvent> m2 = new CopyOnWriteArrayList<>();
        Disposable leaving = member("m1", m1);
        PushEvent e1 = publish("{\"n\":1}");
        assertThat(awaitTrue(() -> m1.size() == 1, Duration.ofSeconds(2))).isTrue();
        member("m2", m2);
        assertThat(groups.members()).isEqualTo(2);

        leaving.dispose();

        assertThat(groups.members()).isEqualTo(1);
        assertThat(awaitTrue(() -> !m2.isEmpty(), Duration.ofSeconds(1))).isTrue();
        assertThat(m2.get(0).id()).isEqualTo(e1.id());
        assertThat(m2.get(0).attributes()).containsEntry("deliveries", "2");
    }

    @Test
    @DisplayName(
            "GIVEN a subscription on a namespace without consumer groups WHEN an event is acknowledged on it SHOULD refuse with bad_request")
    void given_namespace_without_consumer_groups_when_acked_should_refuse_bad_request() {
        assertThatThrownBy(() -> groups.ack(null, GROUP, "m1", "ticker:x", "1", authorizer)
                        .block())
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.code()).isEqualTo("bad_request"));
    }

    @Test
    @DisplayName("GIVEN an event held by another member WHEN this member acknowledges it SHOULD refuse with not_found")
    void given_event_held_by_another_member_when_acked_by_this_member_should_refuse_not_found() {
        List<PushEvent> m2 = new CopyOnWriteArrayList<>();
        member("m2", m2);
        PushEvent e1 = publish("{\"n\":1}");
        assertThat(awaitTrue(() -> m2.size() == 1, Duration.ofSeconds(2))).isTrue();

        assertThatThrownBy(() -> groups.ack(null, GROUP, "m1", "jobs:q", e1.id(), authorizer)
                        .block())
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.code()).isEqualTo("not_found"));
    }

    @Test
    @DisplayName("GIVEN a blank group name WHEN it is validated SHOULD refuse with bad_request saying it is required")
    void given_blank_group_name_when_validated_should_refuse_bad_request_saying_required() {
        assertThatThrownBy(() -> PushGroups.name("group", "  ")).isInstanceOfSatisfying(PushException.class, e -> {
            assertThat(e.code()).isEqualTo("bad_request");
            assertThat(e.getMessage()).contains("required");
        });
    }

    @Test
    @DisplayName(
            "GIVEN a group namespace on a store that keeps no consumer groups WHEN the groups are built SHOULD throw naming GROUP")
    void given_group_namespace_on_store_without_consumer_groups_when_built_should_throw_naming_group() {
        assertThatThrownBy(() -> new PushGroups(props, new NoGroupsStore(), metrics, dlq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GROUP");
    }

    @Test
    @DisplayName(
            "GIVEN a STRICT group with two members WHEN events are published and the active member leaves SHOULD deliver only to the active one, then to the other")
    void given_strict_group_when_active_member_leaves_should_hand_over_to_the_other_member() {
        jobs().setOrdering(PushProperties.Ordering.STRICT);
        jobs().setMaxDeliveries(10);
        List<PushEvent> m1 = new CopyOnWriteArrayList<>();
        List<PushEvent> m2 = new CopyOnWriteArrayList<>();
        Disposable first = member("m1", m1);
        pause(Duration.ofMillis(100));
        member("m2", m2);

        PushEvent e1 = publish("{\"n\":1}");
        assertThat(awaitTrue(() -> m1.size() == 1, Duration.ofSeconds(2))).isTrue();
        pause(Duration.ofMillis(200));
        assertThat(m2).isEmpty();

        groups.ack(null, GROUP, "m1", "jobs:q", e1.id(), authorizer).block();
        first.dispose();
        PushEvent e2 = publish("{\"n\":2}");

        assertThat(awaitTrue(() -> !m2.isEmpty(), Duration.ofSeconds(5))).isTrue();
        assertThat(m2.get(0).id()).isEqualTo(e2.id());
        assertThat(registry.get("shiguchi.cursus.groups.strict.takeovers")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("GIVEN a group WHEN an admin seeks it to first SHOULD report it and count the seek")
    void given_group_when_seeked_to_first_should_move_the_position_and_count_it() {
        List<PushEvent> m1 = new CopyOnWriteArrayList<>();
        member("m1", m1);
        PushEvent e1 = publish("{\"n\":1}");
        assertThat(awaitTrue(() -> m1.size() == 1, Duration.ofSeconds(2))).isTrue();
        groups.ack(null, GROUP, "m1", "jobs:q", e1.id(), authorizer).block();

        PushReport report = report();
        assertThat(report.seekGroup(GROUP, "jobs:q", "first").block()).isTrue();
        assertThat(report.seekGroup("nobody", "jobs:q", "first").block()).isFalse();

        assertThat(awaitTrue(() -> m1.size() == 2, Duration.ofSeconds(3))).isTrue();
        assertThat(m1.get(1).id()).isEqualTo(e1.id());
        assertThat(registry.get("shiguchi.cursus.groups.seeks")
                        .tag("kind", "group")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThatThrownBy(() -> report.seekGroup(GROUP, "jobs:q", " ").block()).isInstanceOf(PushException.class);
    }

    @Test
    @DisplayName(
            "GIVEN a KEY namespace and a store that cannot order by key WHEN groups start SHOULD fail with a clear message")
    void given_key_ordering_on_unsupported_store_when_started_should_fail() {
        jobs().setOrdering(PushProperties.Ordering.KEY);
        assertThatThrownBy(() -> new PushGroups(props, new NoGroupsStore(), metrics, dlq))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ordering KEY");
    }

    private PushReport report() {
        PushReport report = new PushReport(
                props,
                broker,
                store,
                metrics,
                new PushConnections(),
                publisher,
                PushArchive.none(),
                new PushRepublishJobs(),
                new PushAudit());
        report.setGroups(groups);
        return report;
    }

    private Disposable member(String name, List<PushEvent> received) {
        Disposable subscription = groups.memberStream(TOPIC, jobs(), GROUP, name, broker.subscribe(TOPIC))
                .subscribe(received::add);
        streams.add(subscription);
        return subscription;
    }

    private PushProperties.Namespace jobs() {
        return props.getNamespaces().get("jobs");
    }

    private PushEvent publish(String json) {
        return publisher
                .publish(TOPIC, json.getBytes(StandardCharsets.UTF_8), Map.of(), null)
                .block();
    }

    private double outcome(String outcome) {
        return registry.get("shiguchi.cursus.groups.deliveries")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private static List<String> ids(List<PushEvent> first, List<PushEvent> second) {
        List<String> out = new ArrayList<>();
        first.forEach(e -> out.add(e.id()));
        second.forEach(e -> out.add(e.id()));
        return out;
    }

    private static boolean awaitTrue(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            pause(Duration.ofMillis(10));
        }
        return true;
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A store that keeps history but runs no consumer groups. */
    private static final class NoGroupsStore implements PushStore {

        @Override
        public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
            return Mono.just(event);
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
        public Capabilities capabilities() {
            return new Capabilities(true, false);
        }
    }
}
