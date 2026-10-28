package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import graphql.execution.preparsed.PreparsedDocumentProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link GatewayDocumentCache} when {@code shiguchi.graphql.document-cache.enabled} or
 * {@code shiguchi.graphql.persisted-queries.enabled} is set. As a
 * {@link PreparsedDocumentProvider} bean it is what DGS hands graphql-java; DGS's own provider
 * backs off when one exists. As a {@code SchemaReloadListener} it is emptied on every reload.
 *
 * <p>Leave {@code dgs.graphql.apq.enabled} off alongside it: DGS would wrap this provider in its
 * own persisted-query cache, which survives schema reloads.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlDocumentCacheConfiguration {

    @Bean
    @ConditionalOnExpression("${shiguchi.graphql.document-cache.enabled:false}"
            + " or ${shiguchi.graphql.persisted-queries.enabled:false}")
    public GatewayDocumentCache gatewayDocumentCache(GatewayProperties properties) {
        return new GatewayDocumentCache(properties.getGraphql());
    }
}
