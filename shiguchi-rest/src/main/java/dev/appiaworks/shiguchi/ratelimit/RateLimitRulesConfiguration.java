package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Wires {@link RateLimitRulesPolicy} when {@code shiguchi.rate-limit.rules} lists at least one
 * rule; nothing otherwise. Independent of {@code shiguchi.rate-limit.enabled}: rules work without
 * the global limiter, and the global limiter works without rules.
 */
@Configuration
@Conditional(RateLimitRulesConfiguration.RulesConfigured.class)
public class RateLimitRulesConfiguration {

    /** True when {@code shiguchi.rate-limit.rules} binds to a non-empty list. */
    static class RulesConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return Binder.get(context.getEnvironment())
                    .bind("shiguchi.rate-limit.rules", Bindable.listOf(InboundRateLimitConfig.Rule.class))
                    .map(rules -> !rules.isEmpty())
                    .orElse(false);
        }
    }

    @Bean
    public RateLimitRulesPolicy gatewayRateLimitRulesPolicy(
            GatewayProperties properties, ObjectProvider<RuleLimiterFactory> limiters) {
        InboundRateLimitConfig c = properties.getRateLimit();
        RuleLimiterFactory factory = limiters.getIfAvailable();
        if (factory == null) {
            if (c.getBackend() == InboundRateLimitConfig.Backend.REDIS) {
                throw new IllegalStateException("shiguchi.rate-limit.backend is REDIS but there is no"
                        + " ReactiveStringRedisTemplate bean (or Spring Data Redis is not on the classpath): add"
                        + " spring-boot-starter-data-redis-reactive and configure spring.data.redis.*");
            }
            factory = (id, rate, burst) -> new InMemoryRateLimiter(rate, burst);
        }
        return new RateLimitRulesPolicy(
                c.getRules(),
                c.getReplenishRate(),
                c.getBurstCapacity(),
                c.getClientIpHeader(),
                factory,
                c.getRulesOnBackendError());
    }

    /** Present only when Spring Data Redis is on the classpath and {@code backend: REDIS}. */
    @Configuration
    @ConditionalOnClass(ReactiveStringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "shiguchi.rate-limit", name = "backend", havingValue = "REDIS")
    static class Redis {

        @Bean
        @ConditionalOnMissingBean(RuleLimiterFactory.class)
        public RuleLimiterFactory gatewayRedisRuleLimiters(
                GatewayProperties properties, ObjectProvider<ReactiveStringRedisTemplate> templates) {
            ReactiveStringRedisTemplate template = templates.getIfAvailable();
            if (template == null) return null;
            var redis = properties.getRateLimit().getRedis();
            return (id, rate, burst) ->
                    new RedisRateLimiter(template, redis.getKeyPrefix() + "rule:", rate, burst, redis.isFailOpen());
        }
    }
}
