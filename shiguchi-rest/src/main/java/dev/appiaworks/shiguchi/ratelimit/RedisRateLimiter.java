package dev.appiaworks.shiguchi.ratelimit;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * Token buckets in Redis, one atomic Lua script per acquisition, so every replica of the gateway
 * shares one bucket per caller. Two keys per caller ({@code <prefix><key>.tokens} and
 * {@code .timestamp}) expire on their own once a bucket would have refilled fully twice. When
 * Redis cannot be reached the request is allowed and the failure logged
 * ({@code redis.fail-open}, default) or refused.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** The bucket algorithm, executed atomically on the Redis side. */
    static final String SCRIPT = """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local fill_time = capacity / rate
            local ttl = math.floor(fill_time * 2)
            if ttl < 1 then ttl = 1 end
            local last_tokens = tonumber(redis.call("get", tokens_key))
            if last_tokens == nil then last_tokens = capacity end
            local last_refreshed = tonumber(redis.call("get", timestamp_key))
            if last_refreshed == nil then last_refreshed = now end
            local delta = math.max(0, now - last_refreshed)
            local filled = math.min(capacity, last_tokens + (delta * rate))
            local allowed = 0
            local new_tokens = filled
            local retry_after = 0
            if filled >= requested then
              new_tokens = filled - requested
              allowed = 1
            else
              retry_after = math.ceil((requested - filled) / rate)
              if retry_after < 1 then retry_after = 1 end
            end
            redis.call("setex", tokens_key, ttl, tostring(new_tokens))
            redis.call("setex", timestamp_key, ttl, tostring(now))
            return { allowed, math.floor(new_tokens), retry_after }
            """;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final RedisScript<List> BUCKET = new DefaultRedisScript<>(SCRIPT, List.class);

    private final ReactiveStringRedisTemplate redis;
    private final String keyPrefix;
    private final double replenishRate;
    private final int burstCapacity;
    private final boolean failOpen;
    private final Supplier<Instant> clock;

    public RedisRateLimiter(
            ReactiveStringRedisTemplate redis,
            String keyPrefix,
            double replenishRate,
            int burstCapacity,
            boolean failOpen) {
        this(redis, keyPrefix, replenishRate, burstCapacity, failOpen, Instant::now);
    }

    /** Clock injectable for tests. */
    public RedisRateLimiter(
            ReactiveStringRedisTemplate redis,
            String keyPrefix,
            double replenishRate,
            int burstCapacity,
            boolean failOpen,
            Supplier<Instant> clock) {
        this.redis = redis;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
        this.failOpen = failOpen;
        this.clock = clock;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Decision> tryAcquire(String key, int tokens) {
        List<String> keys = List.of(keyPrefix + key + ".tokens", keyPrefix + key + ".timestamp");
        Instant now = clock.get();
        double seconds = now.getEpochSecond() + now.getNano() / 1_000_000_000.0;
        List<String> args = List.of(
                Double.toString(replenishRate),
                Integer.toString(burstCapacity),
                String.format(java.util.Locale.ROOT, "%.3f", seconds),
                Integer.toString(tokens));
        return redis.execute(BUCKET, keys, args)
                .next()
                .map(result -> {
                    List<Object> values = (List<Object>) result;
                    boolean allowed = number(values, 0) == 1;
                    return allowed ? Decision.allow(number(values, 1)) : Decision.refuse(number(values, 2));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> fallback("empty reply", null)))
                .onErrorResume(e -> Mono.just(fallback(e.getMessage(), e)));
    }

    private Decision fallback(String reason, Throwable cause) {
        if (failOpen) {
            log.warn("Rate limiter cannot reach Redis ({}); allowing the request (fail-open)", reason, cause);
            return Decision.allow(0);
        }
        log.warn("Rate limiter cannot reach Redis ({}); refusing the request (fail-closed)", reason, cause);
        return Decision.refuse(1);
    }

    private static long number(List<Object> values, int index) {
        Object v = index < values.size() ? values.get(index) : null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return (long) Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
