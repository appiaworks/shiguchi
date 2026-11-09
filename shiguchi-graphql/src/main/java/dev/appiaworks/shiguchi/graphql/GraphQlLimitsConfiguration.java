package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GraphQlConfig;
import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import dev.appiaworks.shiguchi.config.LimitsConfig;
import dev.appiaworks.shiguchi.ratelimit.InMemoryRateLimiter;
import dev.appiaworks.shiguchi.ratelimit.RedisRateLimiter;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.server.WebGraphQlHandler;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Query guards and request-level switches from {@code shiguchi.graphql.*}:
 *
 * <ul>
 *   <li>{@code limits.max-depth}: graphql-java's depth instrumentation, which aborts a deeper
 *       query during analysis, before any data fetcher runs.
 *   <li>{@code limits.max-complexity}, {@code limits.budget.per-minute}, {@code limits.report-cost}:
 *       {@link GraphQlLimitsInterceptor}, which prices each operation by the {@code cost} proto
 *       options.
 *   <li>{@code introspection}: {@link GraphQlIntrospectionInterceptor}.
 *   <li>{@code batch.max-operations}: {@link GraphQlBatchWebFilter}.
 *   <li>{@code incremental.enabled}: {@link GraphQlIncrementalWebFilter} and
 *       {@link GraphQlIncrementalInterceptor}.
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlLimitsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GraphQlLimitsConfiguration.class);

    private static final String LIMITS_ACTIVE =
            "${shiguchi.graphql.limits.max-complexity:0} > 0 or ${shiguchi.graphql.limits.budget.per-minute:0} > 0"
                    + " or ${shiguchi.graphql.limits.report-cost:false}";

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.limits", name = "max-depth")
    public Instrumentation gatewayMaxQueryDepthInstrumentation(GatewayProperties properties) {
        int maxDepth = properties.getGraphql().getLimits().getMaxDepth();
        if (maxDepth <= 0) return new graphql.execution.instrumentation.SimplePerformantInstrumentation();
        log.info("GraphQL queries are limited to a depth of {}", maxDepth);
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    @Bean
    @ConditionalOnExpression(LIMITS_ACTIVE)
    GraphQlLimitsInterceptor gatewayGraphQlLimitsInterceptor(
            GatewayProperties properties,
            ObjectProvider<GraphQlSource> source,
            ObjectProvider<GraphQlSchemaBuilder> schemaBuilder,
            ObjectProvider<GraphQlCostBudget> budget) {
        LimitsConfig limits = properties.getGraphql().getLimits();
        GraphQlCostCalculator calculator = new GraphQlCostCalculator(
                () -> {
                    GraphQlSchemaBuilder builder = schemaBuilder.getIfAvailable();
                    return builder == null ? Map.of() : builder.getFieldCosts();
                },
                limits.getDefaultListSize());
        log.info(
                "GraphQL operation cost: max {}, budget {} per minute, reported {}",
                limits.getMaxComplexity() > 0 ? limits.getMaxComplexity() : "unlimited",
                limits.getBudget().getPerMinute() > 0 ? limits.getBudget().getPerMinute() : "off",
                limits.isReportCost());
        return new GraphQlLimitsInterceptor(source, calculator, limits, budget.getIfAvailable());
    }

    /** The cost budget in memory, unless the Redis one below applies. */
    @Bean
    @ConditionalOnMissingBean(GraphQlCostBudget.class)
    @ConditionalOnExpression("${shiguchi.graphql.limits.budget.per-minute:0} > 0")
    GraphQlCostBudget gatewayGraphQlMemoryCostBudget(GatewayProperties properties) {
        int perMinute = properties.getGraphql().getLimits().getBudget().getPerMinute();
        return new GraphQlCostBudget(
                new InMemoryRateLimiter(perMinute / 60.0, Math.max(1, perMinute)),
                properties.getRateLimit(),
                perMinute);
    }

    /** The cost budget in Redis, shared by every gateway instance; needs Spring Data Redis. */
    @Configuration
    @ConditionalOnClass(ReactiveStringRedisTemplate.class)
    @ConditionalOnExpression("${shiguchi.graphql.limits.budget.per-minute:0} > 0")
    @ConditionalOnProperty(prefix = "shiguchi.rate-limit", name = "backend", havingValue = "REDIS")
    static class RedisCostBudget {

        @Bean
        @ConditionalOnMissingBean(GraphQlCostBudget.class)
        GraphQlCostBudget gatewayGraphQlRedisCostBudget(
                GatewayProperties properties, ObjectProvider<ReactiveStringRedisTemplate> templates) {
            ReactiveStringRedisTemplate template = templates.getIfAvailable();
            if (template == null) {
                throw new IllegalStateException("shiguchi.rate-limit.backend is REDIS but there is no"
                        + " ReactiveStringRedisTemplate bean: add spring-boot-starter-data-redis-reactive and"
                        + " configure spring.data.redis.*");
            }
            InboundRateLimitConfig rate = properties.getRateLimit();
            int perMinute = properties.getGraphql().getLimits().getBudget().getPerMinute();
            return new GraphQlCostBudget(
                    new RedisRateLimiter(
                            template,
                            rate.getRedis().getKeyPrefix() + "graphql-cost:",
                            perMinute / 60.0,
                            Math.max(1, perMinute),
                            rate.getRedis().isFailOpen()),
                    rate,
                    perMinute);
        }
    }

    @Bean
    @ConditionalOnExpression("'${shiguchi.graphql.introspection:ENABLED}'.toUpperCase() != 'ENABLED'")
    GraphQlIntrospectionInterceptor gatewayGraphQlIntrospectionInterceptor(GatewayProperties properties) {
        GraphQlConfig.Introspection mode = properties.getGraphql().getIntrospection();
        log.info("GraphQL introspection: {}", mode);
        return new GraphQlIntrospectionInterceptor(mode);
    }

    @Bean
    @ConditionalOnExpression("${shiguchi.graphql.batch.max-operations:0} > 0")
    GraphQlBatchWebFilter gatewayGraphQlBatchWebFilter(
            GatewayProperties properties,
            WebGraphQlHandler handler,
            ObjectProvider<ObjectMapper> mapper,
            Environment environment) {
        int max = properties.getGraphql().getBatch().getMaxOperations();
        String path = environment.getProperty("spring.graphql.http.path", "/graphql");
        log.info("GraphQL batched requests: up to {} operations per POST to {}", max, path);
        return new GraphQlBatchWebFilter(
                handler, mapper.getIfAvailable(() -> JsonMapper.builder().build()), path, max);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.incremental", name = "enabled", havingValue = "true")
    GraphQlIncrementalInterceptor gatewayGraphQlIncrementalInterceptor() {
        return new GraphQlIncrementalInterceptor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.incremental", name = "enabled", havingValue = "true")
    GraphQlIncrementalWebFilter gatewayGraphQlIncrementalWebFilter(
            WebGraphQlHandler handler, ObjectProvider<ObjectMapper> mapper, Environment environment) {
        String path = environment.getProperty("spring.graphql.http.path", "/graphql");
        log.info("GraphQL @defer: multipart/mixed answers for POST {}", path);
        return new GraphQlIncrementalWebFilter(
                handler, mapper.getIfAvailable(() -> JsonMapper.builder().build()), path);
    }
}
