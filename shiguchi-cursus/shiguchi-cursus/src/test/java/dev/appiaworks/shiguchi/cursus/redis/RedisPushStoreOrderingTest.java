package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.PushStore.Claimed;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

@DisplayName("RedisPushStore consumer groups: KEY ordering and seek (real Redis)")
class RedisPushStoreOrderingTest {

    private static final String GROUP = "workers";
    /** Long enough that a slow CI round trip never counts as an expired claim. */
    private static final Duration DEADLINE = Duration.ofSeconds(3);

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
        return append(null);
    }

    private PushEvent append(String key) {
        PushEvent event = new PushEvent(jobs, null, "{}".getBytes(), null, key, null);
        return store.append(event, 10, HOUR).block();
    }

    /** Like {@link #append(String)} but with a stream cap large enough not to trim a thousand entries. */
    private PushEvent appendKept(String key) {
        return store.append(new PushEvent(jobs, null, "{}".getBytes(), null, key, null), 5000, HOUR)
                .block();
    }

    private List<Claimed> claimKeyed(String member, int max, Duration deadline) {
        return store.claim(GROUP, jobs, member, max, deadline, EXPIRY, PushProperties.Ordering.KEY)
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private List<Claimed> claimKeyed(String member, int max) {
        return claimKeyed(member, max, DEADLINE);
    }

    /** Creates the group at the head: one event appended so the topic exists, then one empty claim. */
    private void openGroup() {
        append();
        assertThat(claimKeyed("m1", 10)).isEmpty();
    }

    private List<Claimed> claim(String member, int max) {
        return store.claim(GROUP, jobs, member, max, DEADLINE, EXPIRY)
                .collectList()
                .block();
    }

    private static List<String> ids(List<Claimed> claimed) {
        return claimed.stream().map(c -> c.event().id()).toList();
    }

    @Test
    @DisplayName("GIVEN the Redis store WHEN asked whether it orders by key SHOULD answer true")
    void given_the_redis_store_when_asked_whether_it_orders_by_key_should_answer_true() {
        assertThat(store.keyOrdering()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN two events with the same key WHEN two members claim SHOULD hand both to the first member in order")
    void given_two_events_with_the_same_key_when_two_members_claim_should_hand_both_to_the_first_member_in_order() {
        openGroup();
        PushEvent first = append("a");
        PushEvent second = append("a");

        List<Claimed> m1 = claimKeyed("m1", 1);
        List<Claimed> m2 = claimKeyed("m2", 5);
        List<Claimed> m1Rest = claimKeyed("m1", 5);

        assertThat(ids(m1)).containsExactly(first.id());
        assertThat(m2).isEmpty();
        assertThat(ids(m1Rest)).containsExactly(second.id());
        assertThat(m1Rest.get(0).deliveries()).isEqualTo(1);
        assertThat(m1Rest.get(0).event().key()).isEqualTo("a");
    }

    @Test
    @DisplayName("GIVEN a key in flight at m1 WHEN m2 claims SHOULD skip that key but take other keys")
    void given_a_key_in_flight_at_m1_when_m2_claims_should_skip_that_key_but_take_other_keys() {
        openGroup();
        PushEvent a1 = append("a");
        PushEvent b1 = append("b");
        PushEvent a2 = append("a");

        assertThat(ids(claimKeyed("m1", 1))).containsExactly(a1.id());
        List<Claimed> m2 = claimKeyed("m2", 5);

        assertThat(ids(m2)).containsExactly(b1.id());
        assertThat(ids(m2)).doesNotContain(a2.id());
    }

    @Test
    @DisplayName(
            "GIVEN more than 1000 pending entries and a key in flight beyond the first 1000 WHEN m2 claims SHOULD still skip that key")
    void given_over_a_thousand_pending_and_a_key_in_flight_beyond_them_when_m2_claims_should_skip_that_key() {
        openGroup();
        for (int i = 0; i < 1100; i++) appendKept("filler-" + i);
        assertThat(claimKeyed("m1", 1100)).hasSize(1100);
        PushEvent a1 = append("a");
        PushEvent a2 = append("a");
        assertThat(ids(claimKeyed("m1", 1))).containsExactly(a1.id());

        List<Claimed> m2 = claimKeyed("m2", 5);

        assertThat(ids(m2)).doesNotContain(a2.id());
    }

    @Test
    @DisplayName("GIVEN a key in flight WHEN m1 acks SHOULD let m2 claim the next event of that key")
    void given_a_key_in_flight_when_m1_acks_should_let_m2_claim_the_next_event_of_that_key() {
        openGroup();
        PushEvent a1 = append("a");
        PushEvent a2 = append("a");
        assertThat(ids(claimKeyed("m1", 1))).containsExactly(a1.id());
        assertThat(claimKeyed("m2", 5)).isEmpty();

        assertThat(store.ackGroup(GROUP, jobs, "m1", a1.id()).block()).isTrue();

        assertThat(ids(claimKeyed("m2", 5))).containsExactly(a2.id());
    }

    @Test
    @DisplayName("GIVEN a key in flight WHEN the claim expires SHOULD free the key for another member")
    void given_a_key_in_flight_when_the_claim_expires_should_free_the_key_for_another_member() throws Exception {
        Duration shortDeadline = Duration.ofMillis(400);
        openGroup();
        PushEvent a1 = append("a");
        assertThat(ids(claimKeyed("m1", 1, shortDeadline))).containsExactly(a1.id());
        assertThat(claimKeyed("m2", 5, shortDeadline)).isEmpty();

        Thread.sleep(shortDeadline.toMillis() + 300);
        List<Claimed> m2 = claimKeyed("m2", 5, shortDeadline);

        assertThat(ids(m2)).containsExactly(a1.id());
        assertThat(m2.get(0).deliveries()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a group with claims WHEN seeked to first SHOULD redeliver from the start and drop claims")
    void given_a_group_with_claims_when_seeked_to_first_should_redeliver_from_the_start_and_drop_claims() {
        append();
        assertThat(claim("m1", 10)).isEmpty();
        PushEvent e1 = append();
        PushEvent e2 = append();
        assertThat(ids(claim("m1", 5))).containsExactly(e1.id(), e2.id());

        assertThat(store.seekGroup(GROUP, jobs, store.firstId()).block()).isTrue();

        assertThat(store.ackGroup(GROUP, jobs, "m1", e1.id()).block()).isFalse();
        assertThat(ids(claim("m2", 10))).endsWith(e1.id(), e2.id());
    }

    @Test
    @DisplayName("GIVEN a session cursor WHEN seeked backwards SHOULD move the cursor")
    void given_a_session_cursor_when_seeked_backwards_should_move_the_cursor() {
        PushEvent e1 = append();
        PushEvent e2 = append();
        store.ack("s1", jobs, e2.id(), EXPIRY).block();

        assertThat(store.seekSession("s1", jobs, e1.id()).block()).isTrue();

        assertThat(store.cursor("s1", jobs).block()).isEqualTo(e1.id());
    }

    @Test
    @DisplayName("GIVEN an unknown group WHEN seeked SHOULD answer false")
    void given_an_unknown_group_when_seeked_should_answer_false() {
        append();

        assertThat(store.seekGroup("nobody", jobs, "0").block()).isFalse();
        assertThat(store.seekSession("nobody", jobs, "1-0").block()).isFalse();
    }
}
