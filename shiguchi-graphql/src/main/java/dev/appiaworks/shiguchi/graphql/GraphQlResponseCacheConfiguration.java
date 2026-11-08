package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the GraphQL response cache when {@code shiguchi.graphql.response-cache.enabled} is
 * set: the cache itself (a {@code SchemaReloadListener}, emptied on every reload) and the
 * {@code WebGraphQlInterceptor} that serves from it. Nothing is created while the property is
 * off, the default.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.20.0")
public class GraphQlResponseCacheConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.response-cache", name = "enabled")
    GraphQlResponseCache graphQlResponseCache(
            GatewayProperties properties, GrpcRouteRegistry registry, ObjectProvider<MeterRegistry> meters) {
        return new GraphQlResponseCache(properties.getGraphql().getResponseCache(), registry, meters.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.response-cache", name = "enabled")
    GraphQlResponseCacheInterceptor graphQlResponseCacheInterceptor(GraphQlResponseCache cache) {
        return new GraphQlResponseCacheInterceptor(cache);
    }
}
