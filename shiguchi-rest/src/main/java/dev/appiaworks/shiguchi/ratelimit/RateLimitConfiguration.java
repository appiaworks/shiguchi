package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Wires the rate limiter for {@code shiguchi.rate-limit.backend}: {@link InMemoryRateLimiter}
 * for {@code MEMORY}, {@link RedisRateLimiter} over Boot's {@code ReactiveStringRedisTemplate} for
 * {@code REDIS} (fails at startup with a plain message when Spring Data Redis is not on the
 * classpath or no template bean exists). A user-defined {@link RateLimiter} or
 * {@link RateLimitKeyResolver} bean replaces the corresponding default.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(RateLimitKeyResolver.class)
    public RateLimitKeyResolver gatewayRateLimitKeyResolver(GatewayProperties properties) {
        return new DefaultRateLimitKeyResolver(properties.getRateLimit());
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    @ConditionalOnProperty(
            prefix = "shiguchi.rate-limit",
            name = "backend",
            havingValue = "MEMORY",
            matchIfMissing = true)
    public RateLimiter gatewayInMemoryRateLimiter(GatewayProperties properties) {
        var c = properties.getRateLimit();
        log.info(
                "Inbound rate limiting on (MEMORY): {} req/s, bursts of {}, keyed by {}",
                c.getReplenishRate(),
                c.getBurstCapacity(),
                c.getKey());
        return new InMemoryRateLimiter(c.getReplenishRate(), c.getBurstCapacity());
    }

    /** Present only when Spring Data Redis is on the classpath; the {@code REDIS} backend needs it. */
    @Configuration
    @ConditionalOnClass(ReactiveStringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "shiguchi.rate-limit", name = "backend", havingValue = "REDIS")
    static class Redis {

        @Bean
        @ConditionalOnMissingBean(RateLimiter.class)
        public RateLimiter gatewayRedisRateLimiter(
                GatewayProperties properties, ObjectProvider<ReactiveStringRedisTemplate> templates) {
            ReactiveStringRedisTemplate template = templates.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("shiguchi.rate-limit.backend is REDIS but there is no"
                        + " ReactiveStringRedisTemplate bean: add spring-boot-starter-data-redis-reactive and"
                        + " configure spring.data.redis.*");
            }
            var c = properties.getRateLimit();
            log.info(
                    "Inbound rate limiting on (REDIS, prefix '{}'): {} req/s, bursts of {}, keyed by {}, {}",
                    c.getRedis().getKeyPrefix(),
                    c.getReplenishRate(),
                    c.getBurstCapacity(),
                    c.getKey(),
                    c.getRedis().isFailOpen() ? "fail-open" : "fail-closed");
            return new RedisRateLimiter(
                    template,
                    c.getRedis().getKeyPrefix(),
                    c.getReplenishRate(),
                    c.getBurstCapacity(),
                    c.getRedis().isFailOpen());
        }
    }

    /** Catches {@code backend: REDIS} without Spring Data Redis on the classpath. */
    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.rate-limit", name = "backend", havingValue = "REDIS")
    @ConditionalOnMissingClass("org.springframework.data.redis.core.ReactiveStringRedisTemplate")
    public RateLimiter gatewayMissingRedisRateLimiter() {
        throw new IllegalStateException("shiguchi.rate-limit.backend is REDIS but Spring Data Redis is not on"
                + " the classpath: add spring-boot-starter-data-redis-reactive");
    }
}
