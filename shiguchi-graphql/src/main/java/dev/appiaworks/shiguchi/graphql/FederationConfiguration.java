package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Apollo Federation subgraph protocol onto the generated GraphQL schema when
 * {@code graphql.federation.enabled} is set, so a router can compose this gateway with other
 * subgraphs instead of treating it as a standalone endpoint.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class FederationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.federation", name = "enabled", havingValue = "true")
    public FederationDataFetcher federationDataFetcher(
            GraphQlSchemaBuilder schemaBuilder, BatchLoaderPipeline pipeline, GrpcMetadataPopulator metadataPopulator) {
        return new FederationDataFetcher(schemaBuilder, pipeline, metadataPopulator);
    }
}
