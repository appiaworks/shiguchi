package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import graphql.GraphQLContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

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
 *
 * <p>Services loaded after startup: on every schema reload (a deferred service loaded, a refresh
 * swapped a schema) the registrar rescans and the types it did not have before are registered.
 * Each registration looks its loader up when a batch runs, so a refreshed service's new loader is
 * used without registering the type again. A cache entry naming a type no loaded service provides
 * yet is only reported as waiting while services are still loading; the warning that it is ignored
 * comes once every service has loaded.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GrpcDataLoaderRegistryFactory implements SchemaReloadListener {

    private static final Logger log = LoggerFactory.getLogger(GrpcDataLoaderRegistryFactory.class);

    private final BatchLoaderRegistry batchLoaderRegistry;
    private final GrpcBatchDataLoaderRegistrar registrar;
    private final DataLoaderConfig config;
    private final GrpcMetadataPopulator metadataPopulator;

    private final Set<String> registeredTypes = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedLoader> cachingLoaders = new ConcurrentHashMap<>();
    private final Set<String> reportedCacheTypes = ConcurrentHashMap.newKeySet();
    private Supplier<List<String>> pendingServices = List::of;
    private boolean started;

    /** A type's caching wrapper, kept for as long as the loader it wraps is the current one. */
    private record CachedLoader(BatchLoader delegate, BatchLoader caching) {}

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

    /** Services not loaded yet; while any are, a cache entry with no loader may still find one. */
    @Autowired(required = false)
    void pendingServices(ServiceLoadStatus status, GatewayProperties properties, GrpcRouteRegistry registry) {
        this.pendingServices = () -> status.notLoaded(properties, registry);
    }

    void setPendingServices(Supplier<List<String>> pendingServices) {
        this.pendingServices = pendingServices;
    }

    @PostConstruct
    synchronized void registerLoaders() {
        reportUnmatchedCacheEntries();

        int added = 0;
        for (String typeName : List.copyOf(registrar.getLoaders().keySet())) {
            if (!registeredTypes.add(typeName)) continue;
            added++;
            DataLoaderConfig.TypeCache cacheConfig = config.getCache().get(typeName);
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
                    .registerMappedBatchLoader((keys, env) -> {
                        BatchLoader loader = currentLoader(typeName);
                        if (loader == null) return Mono.just(Map.of());
                        return GraphQlMetadata.build(env.getContext(), metadataPopulator)
                                .flatMap(metadata -> loader.loadMapped(new ArrayList<>(keys), metadata));
                    });
            log.debug("Registered DataLoader for type: {}", typeName);
        }
        if (added > 0 || !started) {
            log.info("Registered {} DataLoaders with Spring GraphQL BatchLoaderRegistry", added);
        }
        started = true;
    }

    /** Rescans the routes and registers the batch-loaded types a newly loaded service brought. */
    @Override
    public void onSchemaReloaded() {
        registrar.scan();
        registerLoaders();
    }

    /** The type's loader as of now, wrapped in its cache when one is configured; null once the type is gone. */
    private BatchLoader currentLoader(String typeName) {
        BatchLoader registered = registrar.getLoaders().get(typeName);
        if (registered == null) return null;
        DataLoaderConfig.TypeCache cacheConfig = config.getCache().get(typeName);
        if (cacheConfig == null) return registered;
        return cachingLoaders
                .compute(
                        typeName,
                        (type, cached) -> cached != null && cached.delegate() == registered
                                ? cached
                                : new CachedLoader(registered, new CachingBatchLoader(registered, cacheConfig)))
                .caching();
    }

    /**
     * A cache entry whose type no loader provides is reported once: as waiting while some
     * service has not loaded (it may bring the type), as ignored once every service has.
     */
    private void reportUnmatchedCacheEntries() {
        List<String> unmatched = config.getCache().keySet().stream()
                .filter(type -> !registrar.getLoaders().containsKey(type))
                .toList();
        if (unmatched.isEmpty()) return;
        List<String> pending = pendingServices.get();
        for (String type : unmatched) {
            if (!pending.isEmpty()) {
                if (reportedCacheTypes.add("pending:" + type)) {
                    log.info(
                            "shiguchi.graphql.data-loader.cache.{} names no batch-loaded type yet; waiting for {}",
                            type,
                            pending);
                }
            } else if (reportedCacheTypes.add(type)) {
                log.warn("shiguchi.graphql.data-loader.cache.{} names no batch-loaded type; ignored", type);
            }
        }
    }
}
