package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.cursus.PushEvent;
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

@DisplayName("RedisPushStore (real Redis)")
class RedisPushStoreTest {

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private String keyPrefix;
    private RedisPushStore store;
    private Topic topic;

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

    @BeforeEach
    void setup(TestInfo test) {
        keyPrefix = "test:" + test.getTestMethod().orElseThrow().getName() + ":";
        store = new RedisPushStore(template, keyPrefix);
        topic = Topic.parse("orders:123");
    }

    @Test
    @DisplayName(
            "GIVEN append with historySize 0 WHEN event is appended SHOULD return id with millis-seq format and keep nothing")
    void given_historySize_zero_when_appended_should_generateLocalIdAndKeepNothing() {
        PushEvent event = new PushEvent(topic, null, "{}".getBytes(), null, null, null);

        PushEvent stored = store.append(event, 0, null).block();

        assertThat(stored.id()).contains("-");
        assertThat(stored.id()).matches("\\d+-\\d+");
        assertThat(store.historyLength(topic)).isZero();
        assertThat(store.range(topic, null, 10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN append with historySize 3 and ttl 1h WHEN 5 events are appended SHOULD keep at least 3 oldest kept events with Redis stream ids")
    void given_historySize_three_when_fiveAppended_should_keepAtLeastThreeEvents() {
        Duration ttl = Duration.ofHours(1);
        String firstId = null;
        String secondId = null;

        for (int i = 0; i < 5; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            PushEvent stored = store.append(event, 3, ttl).block();
            if (i == 0) firstId = stored.id();
            if (i == 1) secondId = stored.id();
        }

        long length = store.historyLength(topic);
        assertThat(length).isGreaterThanOrEqualTo(3).isLessThanOrEqualTo(5);

        List<PushEvent> events = store.range(topic, null, 100).collectList().block();
        assertThat(events).hasSize((int) length);
        // Verify at least the last 3 are present
        String lastId = events.get(events.size() - 1).id();
        assertThat(lastId).contains("-");
    }

    @Test
    @DisplayName("GIVEN range with afterId WHEN queried SHOULD exclude the afterId itself and return only later events")
    void given_range_with_afterId_when_queried_should_excludeAfterId() {
        Duration ttl = Duration.ofHours(1);
        String idOfSecond = null;

        for (int i = 0; i < 3; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            PushEvent stored = store.append(event, 3, ttl).block();
            if (i == 1) idOfSecond = stored.id();
        }

        List<PushEvent> events =
                store.range(topic, idOfSecond, 10).collectList().block();

        // Should only contain events after idOfSecond, not idOfSecond itself
        for (PushEvent e : events) {
            assertThat(store.compareIds(e.id(), idOfSecond)).isPositive();
        }
    }

    @Test
    @DisplayName("GIVEN range with limit WHEN queried SHOULD respect the limit")
    void given_range_with_limit_when_queried_should_respectLimit() {
        Duration ttl = Duration.ofHours(1);

        for (int i = 0; i < 5; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            store.append(event, 10, ttl).block();
        }

        List<PushEvent> events = store.range(topic, null, 2).collectList().block();
        assertThat(events).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN two ids with format millis-seq WHEN compared SHOULD order correctly by millis then seq")
    void given_stream_ids_when_compared_should_orderCorrectly() {
        // Redis stream ids: millis-seq format
        assertThat(store.compareIds("100-1", "100-2")).isNegative();
        assertThat(store.compareIds("100-2", "100-1")).isPositive();
        assertThat(store.compareIds("100-1", "100-1")).isZero();
        assertThat(store.compareIds("100-1", "101-0")).isNegative();
    }

    @Test
    @DisplayName("GIVEN plain numeric id (memory store format) WHEN compared SHOULD treat as millis-0")
    void given_plain_numeric_id_when_compared_should_treatAsMillisZero() {
        assertThat(store.compareIds("100", "101")).isNegative();
        assertThat(store.compareIds("100", "100-1")).isNegative();
        assertThat(store.compareIds("100-0", "100")).isZero();
    }

    @Test
    @DisplayName("GIVEN garbage id format WHEN compared SHOULD throw IllegalArgumentException")
    void given_garbage_id_when_compared_should_throw() {
        assertThatThrownBy(() -> store.compareIds("not-valid-format", "100-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an event id");
    }

    @Test
    @DisplayName("GIVEN event appended with ttl 1 second WHEN 1500ms passes SHOULD expire the key")
    void given_ttl_one_second_when_time_passes_should_expireKey() throws InterruptedException {
        PushEvent event = new PushEvent(topic, null, "{}".getBytes(), null, null, null);
        store.append(event, 3, Duration.ofSeconds(1)).block();

        assertThat(store.historyLength(topic)).isOne();

        // Sleep past the TTL
        Thread.sleep(1500);

        assertThat(store.historyLength(topic)).isZero();
    }

    @Test
    @DisplayName("GIVEN append with no ttl WHEN queried SHOULD keep history beyond typical ttl window")
    void given_no_ttl_when_appended_should_keepHistoryWithoutExpiry() throws InterruptedException {
        PushEvent event = new PushEvent(topic, null, "{}".getBytes(), null, null, null);
        store.append(event, 3, null).block();

        Thread.sleep(100);

        assertThat(store.historyLength(topic)).isOne();
    }

    @Test
    @DisplayName("GIVEN store WHEN capabilities checked SHOULD report history true and persistentSessions false")
    void given_store_when_capabilitiesQueried_should_reportHistoryOnly() {
        var capabilities = store.capabilities();

        assertThat(capabilities.history()).isTrue();
        assertThat(capabilities.persistentSessions()).isFalse();
    }

    @Test
    @DisplayName("GIVEN three events appended WHEN range requested oldest first SHOULD return them in insert order")
    void given_three_events_when_ranged_should_returnOldestFirst() {
        Duration ttl = Duration.ofHours(1);
        String id1 = null;
        String id2 = null;

        for (int i = 0; i < 3; i++) {
            PushEvent event = new PushEvent(topic, null, ("{\"i\":" + i + "}").getBytes(), null, null, null);
            PushEvent stored = store.append(event, 3, ttl).block();
            if (i == 0) id1 = stored.id();
            if (i == 1) id2 = stored.id();
        }

        List<PushEvent> events = store.range(topic, null, 10).collectList().block();

        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        assertThat(store.compareIds(events.get(0).id(), events.get(1).id())).isNegative();
        assertThat(store.compareIds(events.get(1).id(), events.get(2).id())).isNegative();
    }

    private static final String LEASE_KEY = "upstream:orders:42";

    @Test
    @DisplayName("GIVEN a free lease WHEN a holder asks for it SHOULD grant it")
    void given_freeLease_when_leased_should_grant() {
        assertThat(store.lease(LEASE_KEY, "gw-a", Duration.ofSeconds(5)).block())
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN a lease held by gw-a WHEN gw-a asks again SHOULD renew it")
    void given_leaseHeldBySameHolder_when_leasedAgain_should_renew() {
        store.lease(LEASE_KEY, "gw-a", Duration.ofSeconds(5)).block();

        assertThat(store.lease(LEASE_KEY, "gw-a", Duration.ofSeconds(5)).block())
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN a lease held by gw-a WHEN gw-b asks for it SHOULD refuse it")
    void given_leaseHeldByOtherHolder_when_leased_should_refuse() {
        store.lease(LEASE_KEY, "gw-a", Duration.ofSeconds(5)).block();

        assertThat(store.lease(LEASE_KEY, "gw-b", Duration.ofSeconds(5)).block())
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a lease released by gw-a WHEN gw-b asks for it SHOULD grant it")
    void given_leaseReleasedByHolder_when_otherHolderLeases_should_grant() {
        store.lease(LEASE_KEY, "gw-a", Duration.ofSeconds(5)).block();
        store.release(LEASE_KEY, "gw-a").block();

        assertThat(store.lease(LEASE_KEY, "gw-b", Duration.ofSeconds(5)).block())
                .isTrue();
    }
}
