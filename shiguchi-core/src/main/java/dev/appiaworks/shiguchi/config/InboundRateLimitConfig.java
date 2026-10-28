package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token-bucket rate limiting of inbound HTTP requests, per caller. A bucket holds
 * {@link #burstCapacity} tokens, refills at {@link #replenishRate} tokens per second, and every
 * request takes {@link #requestedTokens}; an empty bucket answers 429 {@code RESOURCE_EXHAUSTED}
 * with {@code Retry-After} and never reaches a backend. Buckets live in the gateway's memory
 * ({@link Backend#MEMORY}) or in Redis ({@link Backend#REDIS}, shared by every replica).
 */
@Data
@NoArgsConstructor
public class InboundRateLimitConfig {
    private boolean enabled = false;
    private Backend backend = Backend.MEMORY;
    /** Tokens added to each caller's bucket per second: the sustained request rate. */
    private double replenishRate = 100.0;
    /** Bucket size: the burst a caller may send at once. */
    private int burstCapacity = 200;
    /** Tokens one request costs. */
    private int requestedTokens = 1;
    /** What identifies a caller; a {@code RateLimitKeyResolver} bean overrides this. */
    private Key key = Key.PRINCIPAL_OR_IP;
    /**
     * Header whose first value is the client IP when the gateway sits behind a trusted proxy
     * ({@code X-Forwarded-For}); empty to use the socket peer only. A header a client can set
     * itself is a key it can forge, so leave it empty when nothing in front strips it.
     */
    private String clientIpHeader = "X-Forwarded-For";
    /**
     * Ant patterns of the paths to limit. Empty (default): every transcoded REST route, every
     * {@code REST_DOWNSTREAM} route and the GraphQL endpoint. Actuator paths are never limited.
     */
    private List<String> paths = new ArrayList<>();
    /** Ant patterns excluded from limiting, evaluated after {@link #paths}. */
    private List<String> excludePaths = new ArrayList<>();
    /** Add {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining} and friends to every limited response. */
    private boolean includeHeaders = true;

    private RedisConfig redis = new RedisConfig();

    public enum Backend {
        /** Buckets in a Caffeine cache in this gateway; each replica limits on its own. */
        MEMORY,
        /**
         * Buckets in Redis via an atomic Lua script, so every replica shares one count per
         * caller. Needs {@code spring-boot-starter-data-redis-reactive} and
         * {@code spring.data.redis.*}.
         */
        REDIS
    }

    public enum Key {
        /** The authenticated principal's name, else the client IP. */
        PRINCIPAL_OR_IP,
        /** The authenticated principal's name; unauthenticated requests are not limited. */
        PRINCIPAL,
        /** The client IP, authenticated or not. */
        IP
    }

    @Data
    @NoArgsConstructor
    public static class RedisConfig {
        /** Prefix of the Redis keys ({@code <prefix><key>.tokens} / {@code .timestamp}). */
        private String keyPrefix = "shiguchi:rate-limit:";
        /** Allow the request (and log) when Redis cannot be reached, instead of answering 429. */
        private boolean failOpen = true;
    }
}
