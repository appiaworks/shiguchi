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

    /**
     * Per-method or per-tag limits, enforced by a {@code GatewayPolicy} after authorization (so
     * on every surface, including the gRPC proxy in {@code ENFORCE} mode). Independent of
     * {@link #enabled}; empty (default) creates no policy. Every matching rule applies.
     */
    private List<Rule> rules = new ArrayList<>();

    /**
     * What a {@link #rules} rule does when its limiter errors or is unavailable (the backend is
     * unreachable, a custom limiter throws): {@link BackendError#ALLOW} (default, fail open, like
     * {@code redis.fail-open}) lets the request through and logs a warning; {@link BackendError#DENY}
     * refuses it with 429 {@code RESOURCE_EXHAUSTED} and {@code Retry-After: 1}.
     */
    private BackendError rulesOnBackendError = BackendError.ALLOW;

    /** Outcome of a rule whose limiter fails. */
    public enum BackendError {
        /** Fail open: allow the request. */
        ALLOW,
        /** Fail closed: throttle the request. */
        DENY
    }

    /**
     * One rule of {@link #rules}: which methods it covers ({@link #match} and/or {@link #tag},
     * both when both are set), who gets a bucket ({@link #key}) and the bucket itself.
     */
    @Data
    @NoArgsConstructor
    public static class Rule {
        /** Names the rule's buckets and log lines; defaults to {@code rule-<index>}. Must be unique. */
        private String id;
        /** Glob on the full method name ({@code acme.Orders/*}); {@code *} any run, {@code ?} one character. */
        private String match;
        /** A route tag the method must carry (exact). */
        private String tag;
        /** {@code principal} (default, the client IP when anonymous), {@code ip} or {@code claim:<name>}. */
        private String key = "principal";
        /** Tokens per second; unset takes {@code shiguchi.rate-limit.replenish-rate}. */
        private Double replenishRate;
        /** Bucket size; unset takes {@code shiguchi.rate-limit.burst-capacity}. */
        private Integer burstCapacity;
        /** Tokens one request costs. */
        private int cost = 1;
    }

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
