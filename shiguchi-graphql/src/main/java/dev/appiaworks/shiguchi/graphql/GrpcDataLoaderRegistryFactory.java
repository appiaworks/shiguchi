package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import graphql.GraphQLContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Registers a per-request {@link org.dataloader.DataLoader} for each gRPC batch route
 * with Spring GraphQL's {@link BatchLoaderRegistry}.
 *
 * <p>Spring GraphQL adds the loaders registered with {@link BatchLoaderRegistry} to the
 * request's {@link org.dataloader.DataLoaderRegistry} (the one DGS built), which graphql-java
 * dispatches through its DataLoaderDispatchStrategy. {@link GatewayBatchLoaderRegistry} is that
 * registry here: it gives each loader the dispatch predicate DGS's registry needs.
 *
 * <p>Auth forwarding: the {@link org.dataloader.BatchLoaderEnvironment} carries the
 * per-request {@link GraphQLContext}, which contains the {@link ServerWebExchange}. The batch
 * call's metadata is built from it by {@link GrpcMetadataPopulator}, as for the top-level data
 * fetcher calls and REST: the caller's principal, its Authorization (or the credential source's),
 * forward-headers and tracing.
 *
 * <p>{@code shiguchi.graphql.data-loader.max-batch-size} splits large batches, and a
 * {@code shiguchi.graphql.data-loader.cache.<Type>} entry wraps that type's loader in a
 * {@link CachingBatchLoader} shared by all requests.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GrpcDataLoaderRegistryFactory {

    private static final Logger log = LoggerFactory.getLogger(GrpcDataLoaderRegistryFactory.class);

    private final BatchLoaderRegistry batchLoaderRegistry;
    private final GrpcBatchDataLoaderRegistrar registrar;
    private final DataLoaderConfig config;
    private final GrpcMetadataPopulator metadataPopulator;

    public GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry, GrpcBatchDataLoaderRegistrar registrar) {
        this(batchLoaderRegistry, registrar, new DataLoaderConfig());
    }

    /** As the constructor Spring uses, without a populator: only the inbound Authorization header is relayed. */
    public GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcBatchDataLoaderRegistrar registrar,
            GatewayProperties properties) {
        this(batchLoaderRegistry, registrar, properties, null);
    }

    @Autowired
    public GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcBatchDataLoaderRegistrar registrar,
            GatewayProperties properties,
            GrpcMetadataPopulator metadataPopulator) {
        this(batchLoaderRegistry, registrar, properties.getGraphql().getDataLoader(), metadataPopulator);
    }

    GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry, GrpcBatchDataLoaderRegistrar registrar, DataLoaderConfig config) {
        this(batchLoaderRegistry, registrar, config, null);
    }

    GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcBatchDataLoaderRegistrar registrar,
            DataLoaderConfig config,
            GrpcMetadataPopulator metadataPopulator) {
        this.batchLoaderRegistry = batchLoaderRegistry;
        this.registrar = registrar;
        this.config = config;
        this.metadataPopulator = metadataPopulator;
    }

    @PostConstruct
    void registerLoaders() {
        config.getCache().keySet().stream()
                .filter(type -> !registrar.getLoaders().containsKey(type))
                .forEach(type ->
                        log.warn("shiguchi.graphql.data-loader.cache.{} names no batch-loaded type; ignored", type));

        registrar.getLoaders().forEach((typeName, registered) -> {
            DataLoaderConfig.TypeCache cacheConfig = config.getCache().get(typeName);
            BatchLoader loader = cacheConfig == null ? registered : new CachingBatchLoader(registered, cacheConfig);
            if (cacheConfig != null) {
                log.info(
                        "DataLoader for type {} caches entities across requests (ttl={}, maxSize={})",
                        typeName,
                        cacheConfig.getTtl(),
                        cacheConfig.getMaxSize());
            }
            batchLoaderRegistry
                    .<String, Map<String, Object>>forName(typeName)
                    .withOptions(options -> {
                        if (config.getMaxBatchSize() > 0) {
                            options.setMaxBatchSize(config.getMaxBatchSize());
                        }
                    })
                    .registerMappedBatchLoader((keys, env) -> GraphQlMetadata.build(env.getContext(), metadataPopulator)
                            .flatMap(metadata -> loader.loadMapped(new ArrayList<>(keys), metadata)));
            log.debug("Registered DataLoader for type: {}", typeName);
        });
        log.info(
                "Registered {} DataLoaders with Spring GraphQL BatchLoaderRegistry",
                registrar.getLoaders().size());
    }
}
