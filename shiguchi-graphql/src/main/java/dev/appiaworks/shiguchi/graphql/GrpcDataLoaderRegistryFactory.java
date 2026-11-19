package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import graphql.GraphQLContext;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
 * <p>It registers every type the {@link BatchLoaderPipeline} knows, gRPC and REST alike, and each
 * registration calls the pipeline: {@code shiguchi.graphql.data-loader.max-batch-size} splits large
 * batches there (not in the DataLoader), and a {@code shiguchi.graphql.data-loader.cache.<Type>}
 * entry wraps that type's loader in a {@link CachingBatchLoader} shared by all requests.
 *
 * <p>Services loaded after startup: on every schema reload (a deferred service loaded, a refresh
 * swapped a schema) the registrar rescans and the types it did not have before are registered.
 * Each registration looks its loader up when a batch runs, so a refreshed service's new loader is
 * used without registering the type again. A cache entry naming a type no loaded service provides
 * yet is only reported as waiting while services are still loading; the warning that it is ignored
 * comes once every service has loaded.
 *
 * <p>Failures: a batch whose backend call fails (error, timeout, a chunk of a split batch) fails only
 * its own keys, as a partial result when the loader reports one. Under {@code data-loader.on-error: ERROR}
 * (default) each failed key completes exceptionally and the response gets an error for it; under {@code
 * NULL} they resolve to null. At most {@value #MAX_ERRORS_PER_LOADER} failed keys per loader and request
 * get an error of their own; past that, each batch adds one {@link BatchFailureSummary} for its remaining
 * keys and the others resolve to null.
 *
 * <p>It runs before the other schema reload listeners, {@link GatewaySchemaProvider} among them:
 * the other way round, a request served by the rebuilt schema before the loaders were registered
 * resolved the new relationships to null.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GrpcDataLoaderRegistryFactory implements SchemaReloadListener {

    private static final Logger log = LoggerFactory.getLogger(GrpcDataLoaderRegistryFactory.class);

    /** Failed keys per loader and request that get an error of their own; the rest are summarised. */
    static final int MAX_ERRORS_PER_LOADER = 20;

    private static final String ERROR_COUNTER_KEY = "shiguchi.dataloader.failed.";

    private final BatchLoaderRegistry batchLoaderRegistry;
    private final GrpcBatchDataLoaderRegistrar registrar;
    private final BatchLoaderPipeline pipeline;
    private final DataLoaderConfig config;
    private final GrpcMetadataPopulator metadataPopulator;

    private final Set<String> registeredTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedCacheTypes = ConcurrentHashMap.newKeySet();
    private Supplier<List<String>> pendingServices = List::of;
    private boolean started;

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

    public GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcBatchDataLoaderRegistrar registrar,
            GatewayProperties properties,
            GrpcMetadataPopulator metadataPopulator) {
        this(batchLoaderRegistry, registrar, properties.getGraphql().getDataLoader(), metadataPopulator);
    }

    @Autowired
    GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcBatchDataLoaderRegistrar registrar,
            BatchLoaderPipeline pipeline,
            GatewayProperties properties,
            GrpcMetadataPopulator metadataPopulator) {
        this(batchLoaderRegistry, registrar, pipeline, properties.getGraphql().getDataLoader(), metadataPopulator);
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
        this(
                batchLoaderRegistry,
                registrar,
                new BatchLoaderPipeline(registrar, (RestBatchDataLoaderRegistrar) null, config),
                config,
                metadataPopulator);
    }

    /** With a REST registrar: its types register too, through the same pipeline. */
    GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcBatchDataLoaderRegistrar registrar,
            Optional<RestBatchDataLoaderRegistrar> restRegistrar,
            DataLoaderConfig config,
            GrpcMetadataPopulator metadataPopulator) {
        this(
                batchLoaderRegistry,
                registrar,
                new BatchLoaderPipeline(registrar, restRegistrar.orElse(null), config),
                config,
                metadataPopulator);
    }

    GrpcDataLoaderRegistryFactory(
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcBatchDataLoaderRegistrar registrar,
            BatchLoaderPipeline pipeline,
            DataLoaderConfig config,
            GrpcMetadataPopulator metadataPopulator) {
        this.batchLoaderRegistry = batchLoaderRegistry;
        this.registrar = registrar;
        this.pipeline = pipeline;
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
        for (String typeName : List.copyOf(pipeline.types())) {
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
            batchLoaderRegistry.<String, Object>forName(typeName).registerMappedBatchLoader((keys, env) -> {
                GraphQLContext context = env.getContext();
                return GraphQlMetadata.build(context, metadataPopulator)
                        .flatMap(metadata -> pipeline.loadOutcome(typeName, keys, metadata))
                        .map(outcome -> resolve(typeName, outcome, context));
            });
            log.debug("Registered DataLoader for type: {}", typeName);
        }
        // One-to-many loaders (Type#by:field): each its own DataLoader, never cached
        for (String name : List.copyOf(pipeline.groupedNames())) {
            if (!registeredTypes.add(name)) continue;
            added++;
            batchLoaderRegistry.<String, Object>forName(name).registerMappedBatchLoader((keys, env) -> {
                GraphQLContext context = env.getContext();
                return GraphQlMetadata.build(context, metadataPopulator)
                        .flatMap(metadata -> pipeline.loadGrouped(name, keys, metadata))
                        .map(outcome -> resolve(name, outcome, context));
            });
            log.debug("Registered one-to-many DataLoader: {}", name);
        }
        if (added > 0 || !started) {
            log.info("Registered {} DataLoaders with Spring GraphQL BatchLoaderRegistry", added);
        }
        started = true;
    }

    /**
     * The DataLoader's result map: the entities, plus each failed key as the failure itself (the
     * DataLoader completes such a key exceptionally) under {@code on-error: ERROR}, or nothing
     * under {@code NULL}. Per request and loader at most {@value #MAX_ERRORS_PER_LOADER} failed
     * keys carry their own error; the first one past that carries a {@link BatchFailureSummary}
     * with the count of the rest of its batch, and the others resolve to null.
     */
    private Map<String, Object> resolve(String typeName, BatchOutcome outcome, GraphQLContext context) {
        return resolve(typeName, outcome.entities(), outcome.failures(), context);
    }

    /** As the by-id resolve, for a one-to-many load: the value of a key is its list of children. */
    private Map<String, Object> resolve(String name, GroupedOutcome outcome, GraphQLContext context) {
        return resolve(name, outcome.groups(), outcome.failures(), context);
    }

    private Map<String, Object> resolve(
            String typeName, Map<String, ?> values, Map<String, Throwable> failures, GraphQLContext context) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        if (failures.isEmpty()) return result;
        if (config.getOnError() == DataLoaderConfig.OnError.NULL) {
            log.warn(
                    "[DataLoader] {} keys of type {} failed and resolve to null (on-error: NULL): {}",
                    failures.size(),
                    typeName,
                    failures.values().iterator().next().getMessage());
            return result;
        }
        AtomicInteger counter = errorCounter(context, typeName);
        int overCap = Math.max(0, counter.get() + failures.size() - MAX_ERRORS_PER_LOADER);
        overCap = Math.min(overCap, failures.size());
        int listed = failures.size() - overCap;
        boolean summarised = false;
        int index = 0;
        for (Map.Entry<String, Throwable> failure : failures.entrySet()) {
            if (index++ < listed) {
                result.put(failure.getKey(), failure.getValue());
            } else if (!summarised) {
                summarised = true;
                result.put(failure.getKey(), new BatchFailureSummary(typeName, overCap - 1, failure.getValue()));
            } else {
                result.put(failure.getKey(), BatchFailureSummary.SUPPRESSED);
            }
        }
        counter.addAndGet(failures.size());
        return result;
    }

    private static AtomicInteger errorCounter(GraphQLContext context, String typeName) {
        if (context != null) {
            return context.computeIfAbsent(ERROR_COUNTER_KEY + typeName, k -> new AtomicInteger());
        }
        return new AtomicInteger();
    }

    /** Rescans the routes and registers the batch-loaded types a newly loaded service brought. */
    @Override
    public void onSchemaReloaded() {
        registrar.scan();
        // REST loaders are declared in YAML but only valid once their document has loaded
        pipeline.rescanRest();
        registerLoaders();
    }

    /**
     * A cache entry whose type no loader provides is reported once: as waiting while some
     * service has not loaded (it may bring the type), as ignored once every service has.
     */
    private void reportUnmatchedCacheEntries() {
        Set<String> known = pipeline.types();
        List<String> unmatched = config.getCache().keySet().stream()
                .filter(type -> !known.contains(type))
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
