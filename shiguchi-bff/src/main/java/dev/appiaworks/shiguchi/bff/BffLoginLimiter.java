package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.ratelimit.InMemoryRateLimiter;
import dev.appiaworks.shiguchi.ratelimit.RateLimiter.Decision;
import java.net.InetSocketAddress;
import java.util.function.LongSupplier;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Caps how fast one client address may start sign-ins: each start stores a login record for the
 * whole {@code login-timeout}. A token bucket per address in this instance (a cluster-wide limit
 * belongs at the edge); the buckets live in {@link InMemoryRateLimiter}'s capped, expiring cache.
 * The address is the request's remote address, which carries forwarded headers only when Spring's
 * {@code ForwardedHeaderTransformer} is on.
 */
final class BffLoginLimiter {

    private static final String UNKNOWN = "unknown";

    private final InMemoryRateLimiter limiter;

    BffLoginLimiter(BffProperties.LoginRateLimit settings) {
        this(settings, System::nanoTime);
    }

    BffLoginLimiter(BffProperties.LoginRateLimit settings, LongSupplier nanoClock) {
        this.limiter = new InMemoryRateLimiter(settings.getRequestsPerSecond(), settings.effectiveBurst(), nanoClock);
    }

    /** The limiter for these settings, or {@code null} when the limit is off. */
    static BffLoginLimiter of(BffProperties.LoginRateLimit settings) {
        return settings.getRequestsPerSecond() > 0 ? new BffLoginLimiter(settings) : null;
    }

    /** Takes one token for the caller; a refused decision carries the seconds to wait (at least 1). */
    Decision tryAcquire(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        String key = remote == null || remote.getAddress() == null
                ? UNKNOWN
                : remote.getAddress().getHostAddress();
        return limiter.acquire(key, 1);
    }

    /** Number of addresses currently tracked. */
    long tracked() {
        return limiter.size();
    }
}
