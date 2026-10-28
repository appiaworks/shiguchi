package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.ratelimit.RateLimiter.Decision;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

@DisplayName("BffLoginLimiter: sign-in starts per client address")
class BffLoginLimiterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private static BffProperties.LoginRateLimit settings(double rate, int burst) {
        BffProperties.LoginRateLimit limit = new BffProperties.LoginRateLimit();
        limit.setRequestsPerSecond(rate);
        limit.setBurst(burst);
        return limit;
    }

    private static ServerHttpRequest from(String address) throws Exception {
        return MockServerHttpRequest.get("/bff/login/idp")
                .remoteAddress(new InetSocketAddress(InetAddress.getByName(address), 40000))
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN rate 10 and burst 20 WHEN one address asks 21 times SHOULD refuse the 21st with a wait of at least 1 s")
    void given_burst20_when_21Requests_should_refuseThe21st() throws Exception {
        BffLoginLimiter limiter = new BffLoginLimiter(settings(10, 20), nanos::get);

        for (int i = 0; i < 20; i++)
            assertThat(limiter.tryAcquire(from("203.0.113.7")).allowed()).isTrue();
        Decision refused = limiter.tryAcquire(from("203.0.113.7"));

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN one address over its limit WHEN another address asks SHOULD allow it")
    void given_oneAddressOverLimit_when_otherAsks_should_allowIt() throws Exception {
        BffLoginLimiter limiter = new BffLoginLimiter(settings(10, 20), nanos::get);
        for (int i = 0; i < 21; i++) limiter.tryAcquire(from("203.0.113.7"));

        assertThat(limiter.tryAcquire(from("203.0.113.8")).allowed()).isTrue();
        assertThat(limiter.tryAcquire(from("203.0.113.7")).allowed()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a burst of 0 WHEN built SHOULD allow twice the rate at once")
    void given_burstUnset_when_built_should_allowTwiceTheRate() throws Exception {
        BffLoginLimiter limiter = new BffLoginLimiter(settings(5, 0), nanos::get);

        for (int i = 0; i < 10; i++)
            assertThat(limiter.tryAcquire(from("203.0.113.7")).allowed()).isTrue();
        assertThat(limiter.tryAcquire(from("203.0.113.7")).allowed()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a refused address WHEN time passes SHOULD allow it again at the configured rate")
    void given_refusedAddress_when_timePasses_should_allowAgain() throws Exception {
        BffLoginLimiter limiter = new BffLoginLimiter(settings(10, 1), nanos::get);
        limiter.tryAcquire(from("203.0.113.7"));
        assertThat(limiter.tryAcquire(from("203.0.113.7")).allowed()).isFalse();

        nanos.addAndGet(100_000_000L);

        assertThat(limiter.tryAcquire(from("203.0.113.7")).allowed()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a rate of 0 WHEN the limiter is requested SHOULD be none at all")
    void given_rateZero_when_limiterRequested_should_beNone() {
        assertThat(BffLoginLimiter.of(settings(0, 0))).isNull();
        assertThat(BffLoginLimiter.of(settings(1, 0))).isNotNull();
    }

    @Test
    @DisplayName("GIVEN a request without a remote address WHEN limited SHOULD share one bucket instead of failing")
    void given_noRemoteAddress_when_limited_should_shareOneBucket() {
        BffLoginLimiter limiter = new BffLoginLimiter(settings(1, 1), nanos::get);
        ServerHttpRequest anonymous =
                MockServerHttpRequest.get("/bff/login/idp").build();

        assertThat(limiter.tryAcquire(anonymous).allowed()).isTrue();
        assertThat(limiter.tryAcquire(anonymous).allowed()).isFalse();
    }

    @Test
    @DisplayName("GIVEN many distinct addresses WHEN they ask SHOULD track each in a bounded bucket cache")
    void given_manyAddresses_when_asking_should_trackThemInBoundedCache() throws Exception {
        BffLoginLimiter limiter = new BffLoginLimiter(settings(10, 20), nanos::get);

        for (int i = 0; i < 2_000; i++) limiter.tryAcquire(from("10.0." + (i / 250) + "." + (i % 250)));

        // InMemoryRateLimiter's cache is capped at 1 000 000 entries and expires idle buckets.
        assertThat(limiter.tracked()).isBetween(1L, 1_000_000L).isEqualTo(2_000L);
    }
}
