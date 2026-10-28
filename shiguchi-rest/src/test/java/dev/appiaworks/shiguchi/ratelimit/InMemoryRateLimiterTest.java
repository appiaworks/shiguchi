package dev.appiaworks.shiguchi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryRateLimiter")
class InMemoryRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private InMemoryRateLimiter limiter(double rate, int burst) {
        return new InMemoryRateLimiter(rate, burst, nanos::get);
    }

    @Test
    @DisplayName(
            "GIVEN a burst of 3 WHEN four requests arrive at once SHOULD allow three with a falling remaining count and refuse the fourth with a Retry-After")
    void given_burst_when_exceeded_should_refuseWithRetryAfter() {
        InMemoryRateLimiter limiter = limiter(1.0, 3);

        assertThat(limiter.acquire("k", 1)).isEqualTo(RateLimiter.Decision.allow(2));
        assertThat(limiter.acquire("k", 1)).isEqualTo(RateLimiter.Decision.allow(1));
        assertThat(limiter.acquire("k", 1)).isEqualTo(RateLimiter.Decision.allow(0));
        RateLimiter.Decision refused = limiter.acquire("k", 1);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.remaining()).isZero();
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN an empty bucket WHEN the replenish time passes SHOULD allow again, never above the burst")
    void given_emptyBucket_when_timePasses_should_refillUpToBurst() {
        InMemoryRateLimiter limiter = limiter(2.0, 4);
        for (int i = 0; i < 4; i++) limiter.acquire("k", 1);
        assertThat(limiter.acquire("k", 1).allowed()).isFalse();

        nanos.addAndGet(500_000_000L); // half a second: one token at 2/s
        assertThat(limiter.acquire("k", 1)).isEqualTo(RateLimiter.Decision.allow(0));
        assertThat(limiter.acquire("k", 1).allowed()).isFalse();

        nanos.addAndGet(60_000_000_000L); // a minute: capped at the burst, not 120 tokens
        assertThat(limiter.acquire("k", 1)).isEqualTo(RateLimiter.Decision.allow(3));
    }

    @Test
    @DisplayName("GIVEN two keys WHEN one is exhausted SHOULD not affect the other")
    void given_twoKeys_when_oneExhausted_should_isolate() {
        InMemoryRateLimiter limiter = limiter(1.0, 1);
        assertThat(limiter.acquire("a", 1).allowed()).isTrue();
        assertThat(limiter.acquire("a", 1).allowed()).isFalse();

        assertThat(limiter.acquire("b", 1).allowed()).isTrue();
        assertThat(limiter.size()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN a request costing more tokens than the bucket holds WHEN acquired SHOULD compute Retry-After from the shortfall")
    void given_costlyRequest_when_short_should_computeRetryAfter() {
        InMemoryRateLimiter limiter = limiter(1.0, 10);
        assertThat(limiter.acquire("k", 8)).isEqualTo(RateLimiter.Decision.allow(2));

        RateLimiter.Decision refused = limiter.acquire("k", 5);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN the reactive form WHEN subscribed SHOULD yield the same decision")
    void given_reactiveForm_when_subscribed_should_decide() {
        InMemoryRateLimiter limiter = limiter(1.0, 1);
        assertThat(limiter.tryAcquire("k", 1).block()).isEqualTo(RateLimiter.Decision.allow(0));
        assertThat(limiter.tryAcquire("k", 1).block().allowed()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a non-positive rate or burst WHEN constructed SHOULD reject")
    void given_badParameters_when_constructed_should_reject() {
        assertThatThrownBy(() -> new InMemoryRateLimiter(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryRateLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
