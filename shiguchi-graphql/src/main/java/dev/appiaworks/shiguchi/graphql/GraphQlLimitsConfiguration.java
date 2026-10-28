package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Query guards from {@code shiguchi.graphql.limits.*}: graphql-java's depth and complexity
 * instrumentations, registered as beans so Spring GraphQL (and DGS on top of it) applies them to
 * every operation, including subscriptions and federation's {@code _entities}. A query over a
 * limit is aborted during analysis, before any data fetcher runs, with an error such as
 * {@code maximum query depth exceeded 4 > 3}.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlLimitsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GraphQlLimitsConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.limits", name = "max-depth")
    public Instrumentation gatewayMaxQueryDepthInstrumentation(GatewayProperties properties) {
        int maxDepth = properties.getGraphql().getLimits().getMaxDepth();
        if (maxDepth <= 0) return new graphql.execution.instrumentation.SimplePerformantInstrumentation();
        log.info("GraphQL queries are limited to a depth of {}", maxDepth);
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.limits", name = "max-complexity")
    public Instrumentation gatewayMaxQueryComplexityInstrumentation(GatewayProperties properties) {
        int maxComplexity = properties.getGraphql().getLimits().getMaxComplexity();
        if (maxComplexity <= 0) return new graphql.execution.instrumentation.SimplePerformantInstrumentation();
        log.info("GraphQL queries are limited to a complexity of {}", maxComplexity);
        return new MaxQueryComplexityInstrumentation(maxComplexity);
    }
}
