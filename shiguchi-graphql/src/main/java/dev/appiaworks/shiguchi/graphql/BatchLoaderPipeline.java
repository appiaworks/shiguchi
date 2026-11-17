package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The one path every batch load takes, for gRPC and REST types alike: Spring GraphQL's
 * per-request DataLoaders and the Federation {@code _entities} fetcher both call it.
 *
 * <p>A load runs: de-duplicate the keys, then the type's cross-request cache when
 * {@code shiguchi.graphql.data-loader.cache.<Type>} is set (it asks only for the ids it misses),
 * then split into chunks of {@code max-batch-size} ids run at most
 * {@code max-concurrent-batches} at a time, then the type's raw loader. A REST loader's own
 * {@code max-batch-size} overrides the global one for that type. With {@code max-batch-size} 0
 * and no cache the raw loader is called once with the distinct keys.
 *
 * <p>The pipeline is a single bean so the caches are shared by every caller. A type declared by
 * both a gRPC route and a REST loader resolves through the gRPC one.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
class BatchLoaderPipeline {

    private static final Logger log = LoggerFactory.getLogger(BatchLoaderPipeline.class);

    private final GrpcBatchDataLoaderRegistrar grpcRegistrar;
    private final RestBatchDataLoaderRegistrar restRegistrar;
    private final DataLoaderConfig config;

    private final Map<String, Layered> layers = new ConcurrentHashMap<>();
    private final Set<String> warnedDuplicates = ConcurrentHashMap.newKeySet();

    /** A type's chunking (and caching) layer, kept for as long as the raw loader it wraps is the current one. */
    private record Layered(BatchLoader raw, BatchLoader loader) {}

    @Autowired
    BatchLoaderPipeline(
            GrpcBatchDataLoaderRegistrar grpcRegistrar,
            Optional<RestBatchDataLoaderRegistrar> restRegistrar,
            GatewayProperties properties) {
        this(grpcRegistrar, restRegistrar.orElse(null), properties.getGraphql().getDataLoader());
    }

    BatchLoaderPipeline(
            GrpcBatchDataLoaderRegistrar grpcRegistrar,
            RestBatchDataLoaderRegistrar restRegistrar,
            DataLoaderConfig config) {
        this.grpcRegistrar = grpcRegistrar;
        this.restRegistrar = restRegistrar;
        this.config = config;
    }

    /** Every type with a loader, gRPC and REST. */
    Set<String> types() {
        Set<String> types = new LinkedHashSet<>(grpcRegistrar.getLoaders().keySet());
        if (restRegistrar != null) {
            for (String type : restRegistrar.getLoaders().keySet()) {
                if (!types.add(type) && warnedDuplicates.add(type)) {
                    log.warn("Type {} has a gRPC batch loader and a REST batch loader; the gRPC one is used", type);
                }
            }
        }
        return types;
    }

    boolean has(String type) {
        return rawLoader(type) != null;
    }

    /**
     * Loads {@code keys} of {@code type}: the entities by id, absent ids missing from the map. An
     * unknown type loads nothing.
     */
    Mono<Map<String, Map<String, Object>>> load(String type, Collection<String> keys, Metadata metadata) {
        BatchLoader raw = rawLoader(type);
        if (raw == null || keys.isEmpty()) return Mono.just(Map.of());
        return layer(type, raw).loadMapped(distinct(keys), metadata);
    }

    /**
     * As {@link #load}, but a failure is reported per key instead of failing the whole load: the
     * keys of a failed chunk fail, the other chunks' entities still come back. A type whose loader
     * is gone (a refresh dropped its service after the DataLoader was registered) fails its keys
     * with an {@link IllegalStateException} rather than resolving them to null silently.
     */
    Mono<BatchOutcome> loadOutcome(String type, Collection<String> keys, Metadata metadata) {
        if (keys.isEmpty()) return Mono.just(BatchOutcome.EMPTY);
        List<String> distinct = distinct(keys);
        BatchLoader raw = rawLoader(type);
        if (raw == null) {
            return Mono.just(BatchOutcome.failed(
                    distinct, new IllegalStateException("No batch loader is available for type " + type)));
        }
        return BatchOutcome.of(layer(type, raw), distinct, metadata);
    }

    private BatchLoader rawLoader(String type) {
        BatchLoader grpc = grpcRegistrar.getLoaders().get(type);
        if (restRegistrar == null) return grpc;
        BatchLoader rest = restRegistrar.getLoaders().get(type);
        if (grpc != null && rest != null && warnedDuplicates.add(type)) {
            log.warn("Type {} has a gRPC batch loader and a REST batch loader; the gRPC one is used", type);
        }
        return grpc != null ? grpc : rest;
    }

    private BatchLoader layer(String type, BatchLoader raw) {
        DataLoaderConfig.TypeCache cacheConfig = config.getCache().get(type);
        return layers.compute(type, (t, current) -> {
                    if (current != null && current.raw() == raw) return current;
                    BatchOutcome.Loader chunking = (keys, md) -> chunkedOutcome(
                            keys,
                            chunkSize(type),
                            config.getMaxConcurrentBatches(),
                            chunk -> BatchOutcome.of(raw, chunk, md));
                    BatchLoader loader = cacheConfig == null ? chunking : new CachingBatchLoader(chunking, cacheConfig);
                    return new Layered(raw, loader);
                })
                .loader();
    }

    /** Ids per call of this type: a REST loader's own limit wins over the global one; 0 = no limit. */
    private int chunkSize(String type) {
        if (restRegistrar != null && grpcRegistrar.getLoaders().get(type) == null) {
            RestBatchLoaderConfig rest = restRegistrar.getConfigs().get(type);
            if (rest != null && rest.getMaxBatchSize() > 0) return rest.getMaxBatchSize();
        }
        return config.getMaxBatchSize();
    }

    /** The keys without repeats, in first-seen order; one copy, and none of the work when they are a set already. */
    static List<String> distinct(Collection<String> keys) {
        return keys instanceof Set<String> ? new ArrayList<>(keys) : new ArrayList<>(new LinkedHashSet<>(keys));
    }

    /**
     * As {@link #chunked}, but a chunk that fails fails only its own keys: the other chunks' entities
     * are kept. {@code call} reports a failure in its outcome; one it throws or errors with is
     * turned into a failure of that chunk's keys.
     */
    static Mono<BatchOutcome> chunkedOutcome(
            List<String> keys, int size, int concurrency, Function<List<String>, Mono<BatchOutcome>> call) {
        Function<List<String>, Mono<BatchOutcome>> safe = chunk ->
                Mono.defer(() -> call.apply(chunk)).onErrorResume(e -> Mono.just(BatchOutcome.failed(chunk, e)));
        if (size <= 0 || keys.size() <= size) return safe.apply(keys);
        List<List<String>> chunks = new ArrayList<>((keys.size() + size - 1) / size);
        for (int from = 0; from < keys.size(); from += size) {
            chunks.add(keys.subList(from, Math.min(from + size, keys.size())));
        }
        int inFlight = concurrency <= 0 ? Integer.MAX_VALUE : concurrency;
        return Flux.fromIterable(chunks).flatMap(safe, inFlight).reduce(BatchOutcome.EMPTY, BatchOutcome::merge);
    }

    /**
     * Runs {@code call} over {@code keys} in chunks of at most {@code size} (0 = one call with all of
     * them), {@code concurrency} chunks at a time (0 = all at once), and merges the results.
     */
    static <V> Mono<Map<String, V>> chunked(
            List<String> keys, int size, int concurrency, Function<List<String>, Mono<Map<String, V>>> call) {
        if (size <= 0 || keys.size() <= size) return call.apply(keys);
        List<List<String>> chunks = new ArrayList<>((keys.size() + size - 1) / size);
        for (int from = 0; from < keys.size(); from += size) {
            chunks.add(keys.subList(from, Math.min(from + size, keys.size())));
        }
        int inFlight = concurrency <= 0 ? Integer.MAX_VALUE : concurrency;
        return Flux.fromIterable(chunks)
                .flatMap(call, inFlight)
                .collect(() -> new LinkedHashMap<String, V>(), Map::putAll)
                .<Map<String, V>>map(merged -> merged);
    }
}
