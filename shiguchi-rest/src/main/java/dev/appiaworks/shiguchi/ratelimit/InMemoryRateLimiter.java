package dev.appiaworks.shiguchi.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;
import reactor.core.publisher.Mono;

/**
 * Token buckets in a Caffeine cache: one per key, refilled lazily on access, dropped after they
 * would have refilled fully twice over. Every replica of the gateway counts on its own, so a
 * caller spread over N replicas gets up to N times the configured rate — use
 * {@link RedisRateLimiter} when that matters.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final double replenishRatePerNano;
    private final double burstCapacity;
    private final LongSupplier nanoClock;
    private final Cache<String, Bucket> buckets;

    public InMemoryRateLimiter(double replenishRatePerSecond, int burstCapacity) {
        this(replenishRatePerSecond, burstCapacity, System::nanoTime);
    }

    /** Clock injectable for tests. */
    public InMemoryRateLimiter(double replenishRatePerSecond, int burstCapacity, LongSupplier nanoClock) {
        if (replenishRatePerSecond <= 0) throw new IllegalArgumentException("replenishRate must be positive");
        if (burstCapacity < 1) throw new IllegalArgumentException("burstCapacity must be at least 1");
        this.replenishRatePerNano = replenishRatePerSecond / 1_000_000_000.0;
        this.burstCapacity = burstCapacity;
        this.nanoClock = nanoClock;
        long fillSeconds = (long) Math.ceil(burstCapacity / replenishRatePerSecond);
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(Math.max(1, fillSeconds * 2)))
                .maximumSize(1_000_000)
                .build();
    }

    @Override
    public Mono<Decision> tryAcquire(String key, int tokens) {
        return Mono.fromSupplier(() -> acquire(key, tokens));
    }

    /** Synchronous form for callers that already are on a worker thread. */
    public Decision acquire(String key, int tokens) {
        Bucket bucket = buckets.get(key, k -> new Bucket(burstCapacity, nanoClock.getAsLong()));
        synchronized (bucket) {
            long now = nanoClock.getAsLong();
            long elapsed = Math.max(0, now - bucket.lastNanos);
            bucket.tokens = Math.min(burstCapacity, bucket.tokens + elapsed * replenishRatePerNano);
            bucket.lastNanos = now;
            if (bucket.tokens >= tokens) {
                bucket.tokens -= tokens;
                return Decision.allow((long) Math.floor(bucket.tokens));
            }
            double missing = tokens - bucket.tokens;
            long retryAfterSeconds = (long) Math.ceil(missing / replenishRatePerNano / 1_000_000_000.0);
            return Decision.refuse(retryAfterSeconds);
        }
    }

    /** Number of live buckets (tests, diagnostics). */
    public long size() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }
}
