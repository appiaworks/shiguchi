package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GraphQlConfig;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Live queries ({@code @live}), when {@code shiguchi.graphql.live.enabled} is set. */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlLiveConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GraphQlLiveConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.live", name = "enabled", havingValue = "true")
    GraphQlLiveDependencies gatewayGraphQlLiveDependencies(GrpcRouteRegistry registry) {
        return new GraphQlLiveDependencies(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.live", name = "enabled", havingValue = "true")
    GraphQlLiveInvalidations gatewayGraphQlLiveInvalidations() {
        return new GraphQlLiveInvalidations();
    }

    /** Carries invalidations between instances through the Cursus broker, when the push module is on. */
    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.live", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "dev.appiaworks.shiguchi.cursus.PushBroker")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "enabled", havingValue = "true")
    GraphQlLiveCursusBridge gatewayGraphQlLiveCursusBridge(
            GraphQlLiveInvalidations invalidations,
            dev.appiaworks.shiguchi.cursus.PushBroker broker,
            dev.appiaworks.shiguchi.cursus.PushProperties push) {
        return new GraphQlLiveCursusBridge(invalidations, broker, push.resolvedInstanceId());
    }

    /** Upstream events on a method's {@code invalidated_by} topics re-run its live queries. */
    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.live", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "dev.appiaworks.shiguchi.cursus.PushBroker")
    @ConditionalOnProperty(prefix = "shiguchi.cursus", name = "enabled", havingValue = "true")
    GraphQlLiveUpstreamFeed gatewayGraphQlLiveUpstreamFeed(
            GrpcRouteRegistry registry,
            dev.appiaworks.shiguchi.cursus.PushBroker broker,
            GraphQlLiveInvalidations invalidations) {
        return new GraphQlLiveUpstreamFeed(registry, broker, invalidations, java.time.Duration.ofSeconds(30));
    }

    /** Warns when invalidations cannot leave the process. */
    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.live", name = "enabled", havingValue = "true")
    org.springframework.boot.ApplicationRunner gatewayGraphQlLiveScopeNotice(
            org.springframework.beans.factory.ObjectProvider<GraphQlLiveCursusBridge> bridge) {
        return args -> {
            if (bridge.getIfAvailable() == null) {
                log.warn("GraphQL live queries are invalidated in this process only: with several gateway instances a"
                        + " mutation re-runs the live queries on the instance that served it (enable"
                        + " shiguchi.cursus with a shared engine to carry them across instances)");
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.live", name = "enabled", havingValue = "true")
    GraphQlLiveInterceptor gatewayGraphQlLiveInterceptor(
            GatewayProperties properties,
            GraphQlLiveDependencies dependencies,
            GraphQlLiveInvalidations invalidations,
            org.springframework.beans.factory.ObjectProvider<CookieSessionBindingInterceptor> binding) {
        GraphQlConfig.LiveConfig live = properties.getGraphql().getLive();
        log.info(
                "GraphQL live queries: at least {} between results, {} per principal, {} in all",
                live.getMinInterval(),
                live.getMaxPerPrincipal(),
                live.getMaxTotal());
        return new GraphQlLiveInterceptor(
                dependencies,
                invalidations,
                new GraphQlLiveStreams(live.getMaxPerPrincipal(), live.getMaxTotal()),
                live,
                binding);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.live", name = "enabled", havingValue = "true")
    GraphQlLiveInvalidationInterceptor gatewayGraphQlLiveInvalidationInterceptor(
            GraphQlLiveDependencies dependencies, GraphQlLiveInvalidations invalidations) {
        return new GraphQlLiveInvalidationInterceptor(dependencies, invalidations);
    }
}
