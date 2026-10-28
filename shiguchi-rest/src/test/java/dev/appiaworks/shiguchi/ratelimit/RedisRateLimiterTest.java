package dev.appiaworks.shiguchi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

/**
 * The plumbing around the Lua script: which keys and arguments go to Redis, how its reply
 * becomes a decision, and what happens when Redis is unreachable. The script itself runs in
 * Redis and is covered by the sample against a real server.
 */
@DisplayName("RedisRateLimiter")
class RedisRateLimiterTest {

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final Instant now = Instant.ofEpochSecond(1_700_000_000L, 250_000_000);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void reply(List<Object> values) {
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn((Flux) Flux.just(values));
    }

    @Test
    @DisplayName(
            "GIVEN Redis answers allowed with 7 tokens left WHEN acquiring SHOULD call the script with prefixed keys, rate, burst, time and cost, and allow")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void given_allowedReply_when_acquiring_should_passArgumentsAndAllow() {
        reply(List.of(1L, 7L, 0L));
        RedisRateLimiter limiter = new RedisRateLimiter(redis, "gw:", 10.0, 20, true, () -> now);

        RateLimiter.Decision decision = limiter.tryAcquire("user:alice", 1).block();

        assertThat(decision).isEqualTo(RateLimiter.Decision.allow(7));
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Object>> args = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly("gw:user:alice.tokens", "gw:user:alice.timestamp");
        assertThat(args.getValue()).containsExactly("10.0", "20", "1700000000.250", "1");
    }

    @Test
    @DisplayName("GIVEN Redis answers refused with a 3 s wait WHEN acquiring SHOULD refuse with that Retry-After")
    void given_refusedReply_when_acquiring_should_refuse() {
        reply(List.of(0L, 0L, 3L));
        RedisRateLimiter limiter = new RedisRateLimiter(redis, "gw:", 10.0, 20, true, () -> now);

        assertThat(limiter.tryAcquire("ip:10.0.0.1", 1).block()).isEqualTo(RateLimiter.Decision.refuse(3));
    }

    @Test
    @DisplayName("GIVEN Redis is unreachable WHEN fail-open SHOULD allow, WHEN fail-closed SHOULD refuse")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void given_redisDown_when_acquiring_should_followFailPolicy() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn((Flux) Flux.error(new RedisConnectionFailureException("connection refused")));

        assertThat(new RedisRateLimiter(redis, "gw:", 10.0, 20, true, () -> now)
                        .tryAcquire("k", 1)
                        .block())
                .isEqualTo(RateLimiter.Decision.allow(0));
        assertThat(new RedisRateLimiter(redis, "gw:", 10.0, 20, false, () -> now)
                        .tryAcquire("k", 1)
                        .block())
                .isEqualTo(RateLimiter.Decision.refuse(1));
    }

    @Test
    @DisplayName("GIVEN Redis answers nothing WHEN acquiring SHOULD apply the fail policy too")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void given_emptyReply_when_acquiring_should_applyFailPolicy() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn((Flux) Flux.empty());

        assertThat(new RedisRateLimiter(redis, "gw:", 10.0, 20, false, () -> now)
                        .tryAcquire("k", 1)
                        .block()
                        .allowed())
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a null prefix WHEN keys are built SHOULD use the bare key")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void given_nullPrefix_when_acquiring_should_useBareKey() {
        reply(List.of(1L, 1L, 0L));
        new RedisRateLimiter(redis, null, 1.0, 1, true, () -> now)
                .tryAcquire("k", 1)
                .block();

        verify(redis).execute(any(RedisScript.class), eq(List.of("k.tokens", "k.timestamp")), anyList());
    }
}
