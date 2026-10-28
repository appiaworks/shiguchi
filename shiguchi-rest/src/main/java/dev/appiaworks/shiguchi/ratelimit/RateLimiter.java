package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import reactor.core.publisher.Mono;

/**
 * Where the token buckets live. The gateway ships {@link InMemoryRateLimiter} (per replica) and
 * {@link RedisRateLimiter} (shared); define your own bean of this type to plug anything else in
 * and the gateway backs off its default.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.5.0")
public interface RateLimiter {

    /**
     * Takes {@code tokens} from the bucket of {@code key} if it holds enough.
     *
     * @param key    the caller identity from the {@link RateLimitKeyResolver}
     * @param tokens tokens this request costs
     */
    Mono<Decision> tryAcquire(String key, int tokens);

    /**
     * The outcome of one acquisition.
     *
     * @param allowed           whether the request may proceed
     * @param remaining         tokens left in the bucket afterwards (0 when refused)
     * @param retryAfterSeconds when refused, seconds until the bucket holds enough tokens again
     *                          (at least 1); 0 when allowed
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
        public static Decision allow(long remaining) {
            return new Decision(true, Math.max(0, remaining), 0);
        }

        public static Decision refuse(long retryAfterSeconds) {
            return new Decision(false, 0, Math.max(1, retryAfterSeconds));
        }
    }
}
