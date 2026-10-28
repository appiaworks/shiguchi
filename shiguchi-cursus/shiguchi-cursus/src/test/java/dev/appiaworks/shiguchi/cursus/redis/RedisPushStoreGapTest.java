package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushGapStoreContract;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

/**
 * The shared gap contract against a real Redis. Two contract cases do not apply: {@code
 * expiredByTtl} (its idle gap lets Redis drop the whole stream key first, which has no trim
 * watermark; the per-event age trim is covered by {@code RedisPushStoreTtlTest} and the
 * whole-key case below) and {@code sweptAfterExpiry} (there is no sweep; the watermark key plays
 * that part).
 */
@DisplayName("RedisPushStore: the highest id the history dropped (real Redis)")
class RedisPushStoreGapTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DEADLINE = Duration.ofSeconds(15);

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private RedisPushStore store;

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
        store = new RedisPushStore(
                template, "test:" + test.getTestMethod().orElseThrow().getName() + ":");
    }

    private PushGapStoreContract.Engine engine() {
        return new PushGapStoreContract.Engine(
                store, null, (topic, id) -> store.redact(topic, id).block(DEADLINE));
    }

    private PushEvent append(Topic topic, int historySize, Duration ttl) {
        return store.append(new PushEvent(topic, null, "{}".getBytes(), null, null, null), historySize, ttl)
                .block(DEADLINE);
    }

    @Test
    @DisplayName("GIVEN a topic where nothing was removed WHEN asking SHOULD answer empty")
    void given_nothingRemoved_when_askingTrimmedThrough_should_beEmpty() {
        PushGapStoreContract.nothingTrimmed(engine());
    }

    @Test
    @DisplayName("GIVEN more events than the history keeps WHEN asking SHOULD answer the last trimmed id")
    void given_sizeTrim_when_askingTrimmedThrough_should_answerLastTrimmedId() {
        PushGapStoreContract.trimmedBySize(engine());
    }

    @Test
    @DisplayName("GIVEN a redacted event WHEN asking SHOULD not count it as removed")
    void given_redactedEvent_when_askingTrimmedThrough_should_notCountIt() {
        PushGapStoreContract.redactedIsNotAGap(engine());
    }

    @Test
    @DisplayName("GIVEN N plus 5 appends into a history of N WHEN counting SHOULD keep exactly N entries")
    void given_morePastTheLimit_when_appending_should_keepExactlyTheLimit() {
        Topic topic = Topic.parse("gap:exact");
        for (int i = 0; i < 12; i++) append(topic, 7, HOUR);

        assertThat(store.historyLength(topic)).isEqualTo(7);
        assertThat(store.range(topic, null, 100).count().block(DEADLINE)).isEqualTo(7L);
    }

    @Test
    @DisplayName("GIVEN the whole stream key expired WHEN asking SHOULD answer the last appended id")
    void given_streamKeyExpired_when_askingTrimmedThrough_should_answerLastAppendedId() throws InterruptedException {
        Topic topic = Topic.parse("gap:keyexpiry");
        append(topic, 10, Duration.ofSeconds(1));
        PushEvent last = append(topic, 10, Duration.ofSeconds(1));
        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isNull();

        long until = System.nanoTime() + DEADLINE.toNanos();
        while (store.historyLength(topic) > 0 && System.nanoTime() < until) Thread.sleep(100);

        assertThat(store.historyLength(topic)).isZero();
        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isEqualTo(last.id());
    }

    @Test
    @DisplayName("GIVEN explicit-id appends past the limit WHEN asking SHOULD record the watermark too")
    void given_appendWithIdPastTheLimit_when_askingTrimmedThrough_should_answerLastTrimmedId() {
        Topic topic = Topic.parse("gap:explicit");
        for (long id = 1; id <= 5; id++) {
            store.appendWithId(new PushEvent(topic, null, "{}".getBytes(), null, null, null), id, 3, HOUR)
                    .block(DEADLINE);
        }

        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isEqualTo("2-0");
        assertThat(store.historyLength(topic)).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN a later trim WHEN asking SHOULD keep only the greater watermark")
    void given_repeatedTrims_when_askingTrimmedThrough_should_moveForwardOnly() {
        Topic topic = Topic.parse("gap:forward");
        PushEvent[] events = new PushEvent[6];
        for (int i = 0; i < 6; i++) events[i] = append(topic, 2, HOUR);

        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isEqualTo(events[3].id());
    }

    @Test
    @DisplayName("GIVEN cluster mode WHEN appending past the history SHOULD work, answer no gap and write no trim key")
    void given_clusterMode_when_appendingPastHistory_should_haveNoWatermark() {
        RedisPushStore cluster = new RedisPushStore(template, "test:clusterfallback:", true);
        Topic topic = Topic.parse("gap:cluster");
        for (int i = 0; i < 20; i++) {
            cluster.append(new PushEvent(topic, null, "{}".getBytes(), null, null, null), 3, HOUR)
                    .block(DEADLINE);
        }
        cluster.appendWithId(new PushEvent(topic, null, "{}".getBytes(), null, null, null), 4102444800000L, 3, HOUR)
                .block(DEADLINE);

        assertThat(cluster.historyLength(topic)).isPositive();
        assertThat(cluster.trimmedThrough(topic).block(DEADLINE)).isNull();
        assertThat(template.keys("test:clusterfallback:trim:*").collectList().block(DEADLINE))
                .isEmpty();
    }
}
