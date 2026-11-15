package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * Builds the {@link RateLimiter} behind one {@code shiguchi.rate-limit.rules[]} entry. A rule has
 * its own rate and burst, so it needs its own limiter; the gateway wires the one for
 * {@code shiguchi.rate-limit.backend} ({@link InMemoryRateLimiter}, or {@link RedisRateLimiter}
 * with the rule id in the key prefix). Define a bean of this type to back rules with anything else.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.25.0")
@FunctionalInterface
public interface RuleLimiterFactory {

    /**
     * @param ruleId the rule's id
     * @param replenishRate tokens added per second
     * @param burstCapacity bucket size
     */
    RateLimiter create(String ruleId, double replenishRate, int burstCapacity);
}
