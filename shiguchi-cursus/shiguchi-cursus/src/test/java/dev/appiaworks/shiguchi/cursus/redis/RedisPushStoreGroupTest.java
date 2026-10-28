package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushStore.Claimed;
import dev.appiaworks.shiguchi.cursus.PushStore.GroupStatus;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

@DisplayName("RedisPushStore consumer groups (real Redis)")
class RedisPushStoreGroupTest {

    private static final String GROUP = "workers";
    /** Long enough that a slow CI round trip never counts as an expired claim. */
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    /** The redelivery scenario's own deadline. */
    private static final Duration SHORT_DEADLINE = Duration.ofMillis(300);

    private static final Duration EXPIRY = Duration.ofHours(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private RedisPushStore store;
    private Topic jobs;

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

    /** Each test gets its own key prefix (named after the test method), so no test sees another's keys. */
    @BeforeEach
    void setup(TestInfo test) {
        String keyPrefix = "test:" + test.getTestMethod().orElseThrow().getName() + ":";
        store = new RedisPushStore(template, keyPrefix);
        jobs = Topic.parse("jobs:q");
    }

    private PushEvent append() {
        PushEvent event = new PushEvent(jobs, null, "{}".getBytes(), null, null, null);
        return store.append(event, 10, HOUR).block();
    }

    private List<Claimed> claimWithin(String member, Duration deadline) {
        return store.claim(GROUP, jobs, member, 1, deadline, EXPIRY)
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private List<Claimed> claim(String member, int max) {
        return store.claim(GROUP, jobs, member, max, DEADLINE, EXPIRY)
                .collectList()
                .block();
    }

    /**
     * Creates the group. Redis creates it at the stream's end only when the stream exists, so one
     * event is appended first (the stream), then one empty claim creates the group after it.
     */
    private void openGroup() {
        append();
        assertThat(claim("m1", 10)).isEmpty();
    }

    private static List<String> ids(List<Claimed> claimed) {
        return claimed.stream().map(c -> c.event().id()).toList();
    }

    private static void assertHandedOut(List<Claimed> claimed, PushEvent event, int deliveries) {
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).event().id()).isEqualTo(event.id());
        assertThat(claimed.get(0).deliveries()).isEqualTo(deliveries);
    }

    @Test
    @DisplayName("GIVEN the Redis store WHEN asked whether it runs consumer groups SHOULD answer true")
    void given_redisStore_when_consumerGroupsAsked_should_answerTrue() {
        assertThat(store.consumerGroups()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a topic with two events and a new group WHEN the group claims before and after a third append SHOULD hand out only the third event, once")
    void given_newGroupAtHead_when_claimedBeforeAndAfterAppend_should_handOutOnlyNewEvent() {
        append();
        append();
        assertThat(claim("m1", 10)).isEmpty();

        PushEvent e3 = append();

        assertHandedOut(claim("m1", 10), e3, 1);
    }

    @Test
    @DisplayName(
            "GIVEN a group with four new events WHEN two members claim them in turns and then claim again SHOULD hand each event to exactly one member")
    void given_fourEvents_when_twoMembersClaim_should_handEachEventToExactlyOneMember() {
        openGroup();
        PushEvent e1 = append();
        PushEvent e2 = append();
        PushEvent e3 = append();
        PushEvent e4 = append();

        List<Claimed> first = claim("m1", 2);
        List<Claimed> second = claim("m2", 10);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(claim("m1", 10)).isEmpty();
        assertThat(claim("m2", 10)).isEmpty();
        List<String> handed =
                Stream.concat(ids(first).stream(), ids(second).stream()).toList();
        assertThat(handed).doesNotHaveDuplicates().containsExactlyInAnyOrder(e1.id(), e2.id(), e3.id(), e4.id());
    }

    @Test
    @DisplayName(
            "GIVEN m1 holds an event WHEN the holder acks it twice SHOULD accept the first ack and report the second as already done")
    void given_claimedEvent_when_ackedTwiceByHolder_should_acceptOnlyFirstAck() {
        // Redis XACK does not check the consumer, so "another member acks it" is not asserted here.
        openGroup();
        PushEvent e1 = append();
        assertHandedOut(claim("m1", 1), e1, 1);

        assertThat(store.ackGroup(GROUP, jobs, "m1", e1.id()).block()).isTrue();
        assertThat(store.ackGroup(GROUP, jobs, "m1", e1.id()).block()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN m1 claimed an event and never acked it WHEN the deadline passes and m2 then m1 claim SHOULD redeliver it with a rising delivery count")
    void given_unackedClaim_when_deadlinePassesAndMembersClaim_should_redeliverWithRisingCount()
            throws InterruptedException {
        openGroup();
        PushEvent e1 = append();
        assertHandedOut(claimWithin("m1", SHORT_DEADLINE), e1, 1);

        Thread.sleep(600);
        assertHandedOut(claimWithin("m2", SHORT_DEADLINE), e1, 2);

        Thread.sleep(600);
        assertHandedOut(claimWithin("m1", SHORT_DEADLINE), e1, 3);
    }

    @Test
    @DisplayName(
            "GIVEN m1 holds an event WHEN m1 releases its claims and m2 claims right away SHOULD hand m2 the event as a redelivery")
    void given_heldClaim_when_releasedAndAnotherMemberClaims_should_redeliverImmediately() {
        openGroup();
        PushEvent e1 = append();
        assertHandedOut(claim("m1", 1), e1, 1);

        assertThat(store.releaseClaims(GROUP, jobs, "m1").block()).isEqualTo(1L);

        assertHandedOut(claim("m2", 1), e1, 2);
    }

    @Test
    @DisplayName(
            "GIVEN m1 holds more than 1000 events WHEN m1 releases its claims SHOULD release all of them, not just the first page")
    void given_moreThanOnePageOfClaims_when_released_should_releaseEveryClaim() {
        openGroup();
        int total = 1050;
        for (int i = 0; i < total; i++) {
            store.append(new PushEvent(jobs, null, "{}".getBytes(), null, null, null), total + 10, HOUR)
                    .block();
        }
        assertThat(claim("m1", total)).hasSize(total);

        assertThat(store.releaseClaims(GROUP, jobs, "m1").block()).isEqualTo((long) total);
    }

    @Test
    @DisplayName(
            "GIVEN m1 holds two events and m2 holds one WHEN the groups are listed SHOULD show one row with three pending and two consumers, filtered by prefix")
    void given_twoMembersHoldClaims_when_groupsListed_should_showPendingMembersAndCursor() {
        openGroup();
        append();
        append();
        append();
        assertThat(claim("m1", 2)).hasSize(2);
        assertThat(claim("m2", 1)).hasSize(1);

        List<GroupStatus> all = store.groups(null, 10).collectList().block();
        assertThat(all).hasSize(1);
        GroupStatus row = all.get(0);
        assertThat(row.group()).isEqualTo(GROUP);
        assertThat(row.topic()).isEqualTo(jobs);
        assertThat(row.pending()).isEqualTo(3L);
        assertThat(row.members()).isEqualTo(2L);
        assertThat(row.cursor()).isNotNull();

        assertThat(store.groups("wor", 10).collectList().block()).hasSize(1);
        assertThat(store.groups("zzz", 10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a group that handed out an event WHEN the group is dropped SHOULD remove it, and a later claim SHOULD start a fresh group at the head")
    void given_groupThatHandedOutEvent_when_dropped_should_startFreshAtHead() {
        openGroup();
        PushEvent e1 = append();
        assertHandedOut(claim("m1", 10), e1, 1);

        assertThat(store.dropGroup(GROUP).block()).isGreaterThanOrEqualTo(1L);
        assertThat(store.groups(null, 10).collectList().block()).isEmpty();

        // The fresh group starts at the head, so e1 (appended before the drop) is not handed out again.
        assertThat(claim("m1", 10)).isEmpty();
        PushEvent e2 = append();
        assertHandedOut(claim("m1", 10), e2, 1);
    }
}
