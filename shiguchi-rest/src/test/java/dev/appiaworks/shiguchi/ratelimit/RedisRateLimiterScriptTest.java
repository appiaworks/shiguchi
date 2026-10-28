package dev.appiaworks.shiguchi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

/**
 * The token-bucket Lua script against a real Redis. {@link RedisRateLimiterTest} covers the Java
 * side with a mocked template; the script itself only ever runs inside Redis, so refills,
 * atomicity and key expiry need a server to be meaningful.
 */
@DisplayName("RedisRateLimiter (real Redis)")
class RedisRateLimiterScriptTest {

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    /** Advanced by the tests instead of sleeping: the script takes "now" as an argument. */
    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.ofEpochSecond(1_700_000_000L));

    /** A prefix of its own per test, so no test can inherit another's buckets. */
    private String prefix;

    private RedisRateLimiter limiter(double rate, int burst) {
        return new RedisRateLimiter(template, prefix, rate, burst, true, clock::get);
    }

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
    void freshKeyspace(org.junit.jupiter.api.TestInfo test) {
        prefix = "test:" + test.getTestMethod().orElseThrow().getName() + ":";
    }

    @Test
    @DisplayName(
            "GIVEN a burst of 3 WHEN a caller spends it SHOULD allow three, refuse the fourth, and report the tokens left each time")
    void given_burst_when_spent_should_refuseOnceEmpty() {
        RedisRateLimiter limiter = limiter(1.0, 3);

        assertThat(limiter.tryAcquire("alice", 1).block()).isEqualTo(RateLimiter.Decision.allow(2));
        assertThat(limiter.tryAcquire("alice", 1).block()).isEqualTo(RateLimiter.Decision.allow(1));
        assertThat(limiter.tryAcquire("alice", 1).block()).isEqualTo(RateLimiter.Decision.allow(0));

        RateLimiter.Decision refused = limiter.tryAcquire("alice", 1).block();
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN an empty bucket WHEN time passes SHOULD refill at replenish-rate and never above the burst")
    void given_emptyBucket_when_timePasses_should_refillToCapacity() {
        RedisRateLimiter limiter = limiter(2.0, 4);
        for (int i = 0; i < 4; i++) limiter.tryAcquire("bob", 1).block();
        assertThat(limiter.tryAcquire("bob", 1).block().allowed()).isFalse();

        clock.updateAndGet(now -> now.plusSeconds(1)); // 2 tokens/s -> 2 back
        assertThat(limiter.tryAcquire("bob", 1).block()).isEqualTo(RateLimiter.Decision.allow(1));
        assertThat(limiter.tryAcquire("bob", 1).block()).isEqualTo(RateLimiter.Decision.allow(0));
        assertThat(limiter.tryAcquire("bob", 1).block().allowed()).isFalse();

        clock.updateAndGet(now -> now.plusSeconds(60)); // long idle: capped at the burst, not 120
        assertThat(limiter.tryAcquire("bob", 1).block()).isEqualTo(RateLimiter.Decision.allow(3));
    }

    @Test
    @DisplayName("GIVEN two callers WHEN one is exhausted SHOULD leave the other's bucket untouched")
    void given_twoCallers_when_oneExhausted_should_isolateBuckets() {
        RedisRateLimiter limiter = limiter(1.0, 2);
        limiter.tryAcquire("carol", 1).block();
        limiter.tryAcquire("carol", 1).block();

        assertThat(limiter.tryAcquire("carol", 1).block().allowed()).isFalse();
        assertThat(limiter.tryAcquire("dave", 1).block()).isEqualTo(RateLimiter.Decision.allow(1));
    }

    @Test
    @DisplayName(
            "GIVEN a request costing several tokens WHEN the bucket holds fewer SHOULD refuse without spending what is there")
    void given_multiTokenRequest_when_notEnough_should_refuseAndKeepTokens() {
        RedisRateLimiter limiter = limiter(1.0, 5);
        assertThat(limiter.tryAcquire("erin", 3).block()).isEqualTo(RateLimiter.Decision.allow(2));

        assertThat(limiter.tryAcquire("erin", 3).block().allowed()).isFalse();
        // The refused request must not have consumed the 2 tokens that were left.
        assertThat(limiter.tryAcquire("erin", 2).block()).isEqualTo(RateLimiter.Decision.allow(0));
    }

    @Test
    @DisplayName(
            "GIVEN the configured key prefix WHEN a caller is limited SHOULD keep its bucket under that prefix with a TTL")
    void given_prefix_when_limiting_should_writeExpiringKeys() {
        limiter(1.0, 2).tryAcquire("frank", 1).block();

        assertThat(template.hasKey(prefix + "frank.tokens").block()).isTrue();
        assertThat(template.hasKey(prefix + "frank.timestamp").block()).isTrue();
        assertThat(template.getExpire(prefix + "frank.tokens").block().toSeconds())
                .isPositive();
    }
}
