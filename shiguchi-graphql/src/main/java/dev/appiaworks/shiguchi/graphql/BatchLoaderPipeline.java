package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
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
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public class BatchLoaderPipeline {

    private static final Logger log = LoggerFactory.getLogger(BatchLoaderPipeline.class);

    private final GrpcBatchDataLoaderRegistrar grpcRegistrar;
    private final RestBatchDataLoaderRegistrar restRegistrar;
    private final DataLoaderConfig config;

    private final Map<String, Layered> layers = new ConcurrentHashMap<>();
    private final Set<String> warnedDuplicates = ConcurrentHashMap.newKeySet();

    /** By-id loaders declared as {@link BatchLoaderDefinition} beans, by type. */
    private final Map<String, BatchLoader> beanLoaders = new LinkedHashMap<>();
    /** One-to-many loaders declared as beans, by {@code Type#by:field}. */
    private final Map<String, GroupedOutcome.Loader> beanGrouped = new LinkedHashMap<>();

    /** A type's chunking (and caching) layer, kept for as long as the raw loader it wraps is the current one. */
    private record Layered(BatchLoader raw, BatchLoader loader) {}

    @Autowired
    BatchLoaderPipeline(
            GrpcBatchDataLoaderRegistrar grpcRegistrar,
            Optional<RestBatchDataLoaderRegistrar> restRegistrar,
            GatewayProperties properties,
            ObjectProvider<BatchLoaderDefinition> definitions) {
        this(
                grpcRegistrar,
                restRegistrar.orElse(null),
                properties.getGraphql().getDataLoader(),
                definitions.orderedStream().toList());
    }

    BatchLoaderPipeline(
            GrpcBatchDataLoaderRegistrar grpcRegistrar,
            RestBatchDataLoaderRegistrar restRegistrar,
            DataLoaderConfig config) {
        this(grpcRegistrar, restRegistrar, config, List.of());
    }

    BatchLoaderPipeline(
            GrpcBatchDataLoaderRegistrar grpcRegistrar,
            RestBatchDataLoaderRegistrar restRegistrar,
            DataLoaderConfig config,
            List<BatchLoaderDefinition> definitions) {
        this.grpcRegistrar = grpcRegistrar;
        this.restRegistrar = restRegistrar;
        this.config = config;
        for (BatchLoaderDefinition definition : definitions) {
            String name = definition.name();
            boolean duplicate = definition.isGrouped()
                    ? beanGrouped.put(
                                    name,
                                    new GroupedBatchLoader(
                                            definition.items(),
                                            definition.groupByJsonName(),
                                            config.getMaxChildrenPerKey()))
                            != null
                    : beanLoaders.put(name, definition.loader()) != null;
            if (duplicate) {
                throw new IllegalStateException("Two BatchLoaderDefinition beans declare the loader " + name);
            }
        }
    }

    /** The loader names the beans declare: types for by-id ones, {@code Type#by:field} for grouped ones. */
    Set<String> beanLoaderNames() {
        Set<String> names = new LinkedHashSet<>(beanLoaders.keySet());
        names.addAll(beanGrouped.keySet());
        return names;
    }

    /** True when a service (gRPC or REST) pins {@code name}'s loader to an endpoint: the pin beats a bean. */
    private boolean pinned(String name) {
        DataLoaderConfig.TypeConfig typeConfig = config.getTypes().get(name);
        return typeConfig != null
                && typeConfig.getEndpoint() != null
                && !typeConfig.getEndpoint().isBlank();
    }

    /** Whether the bean's loader serves {@code name}: it does unless a pinned service loader exists too. */
    private boolean beanServes(String name, boolean serviceHasIt) {
        if (!serviceHasIt) return true;
        if (pinned(name)) return false;
        if (warnedDuplicates.add("bean:" + name)) {
            log.warn(
                    "Loader {} is declared by a BatchLoaderDefinition bean and by a service; the bean is used."
                            + " Pin the service with shiguchi.graphql.data-loader.types.{}.endpoint to use it instead",
                    name,
                    name);
        }
        return true;
    }

    /** Every type with a by-id loader: gRPC, REST and bean. */
    public Set<String> types() {
        Set<String> types = new LinkedHashSet<>(grpcRegistrar.getLoaders().keySet());
        types.addAll(beanLoaders.keySet());
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

    /**
     * The one-to-many loaders by name ({@code Type#by:field}), gRPC and REST. Apart from {@link #types()}
     * on purpose: a grouped loader has no entities by id, so {@code _entities} and the caches never see it.
     */
    public Set<String> groupedNames() {
        Set<String> names =
                new LinkedHashSet<>(grpcRegistrar.getGroupedLoaders().keySet());
        if (restRegistrar != null)
            names.addAll(restRegistrar.getGroupedLoaders().keySet());
        names.addAll(beanGrouped.keySet());
        return names;
    }

    boolean hasGrouped(String name) {
        return groupedLoader(name) != null;
    }

    /**
     * Loads the children of {@code keys} (parent ids) from the grouped loader {@code name}: de-duplicated,
     * chunked by {@code max-batch-size} (a REST loader's own wins) with bounded concurrency like a by-id
     * load, never cached. A failed chunk fails only its own keys; a loader that is gone fails them all.
     */
    Mono<GroupedOutcome> loadGrouped(String name, Collection<String> keys, Metadata metadata) {
        if (keys.isEmpty()) return Mono.just(GroupedOutcome.EMPTY);
        List<String> distinct = distinct(keys);
        GroupedOutcome.Loader raw = groupedLoader(name);
        if (raw == null) {
            return Mono.just(GroupedOutcome.failed(
                    distinct, new IllegalStateException("No batch loader is available for " + name)));
        }
        return chunkedGrouped(
                distinct,
                groupedChunkSize(name),
                config.getMaxConcurrentBatches(),
                chunk -> GroupedOutcome.of(raw, chunk, metadata));
    }

    /** Runs the REST registrar's scan again: services whose documents loaded since get their loaders. */
    void rescanRest() {
        if (restRegistrar != null) restRegistrar.scan();
    }

    private GroupedOutcome.Loader groupedLoader(String name) {
        GroupedOutcome.Loader service = serviceGroupedLoader(name);
        GroupedOutcome.Loader bean = beanGrouped.get(name);
        return bean != null && beanServes(name, service != null) ? bean : service;
    }

    private GroupedOutcome.Loader serviceGroupedLoader(String name) {
        GroupedOutcome.Loader grpc = grpcRegistrar.getGroupedLoaders().get(name);
        if (grpc != null || restRegistrar == null) return grpc;
        return restRegistrar.getGroupedLoaders().get(name);
    }

    /** Parent ids per call of a one-to-many loader: a REST loader's own limit wins over the global one. */
    private int groupedChunkSize(String name) {
        if (beanGrouped.containsKey(name) && groupedLoader(name) == beanGrouped.get(name)) {
            return config.getMaxBatchSize();
        }
        if (restRegistrar != null && grpcRegistrar.getGroupedLoaders().get(name) == null) {
            RestBatchLoaderConfig rest = restRegistrar.getGroupedConfigs().get(name);
            if (rest != null && rest.getMaxBatchSize() > 0) return rest.getMaxBatchSize();
        }
        return config.getMaxBatchSize();
    }

    private BatchLoader rawLoader(String type) {
        BatchLoader service = serviceLoader(type);
        BatchLoader bean = beanLoaders.get(type);
        return bean != null && beanServes(type, service != null) ? bean : service;
    }

    private BatchLoader serviceLoader(String type) {
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
        if (beanLoaders.containsKey(type) && rawLoader(type) == beanLoaders.get(type)) {
            return config.getMaxBatchSize();
        }
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
        return chunkedWith(
                keys, size, concurrency, call, BatchOutcome::failed, BatchOutcome.EMPTY, BatchOutcome::merge);
    }

    /** As {@link #chunkedOutcome}, for the one-to-many loaders' {@link GroupedOutcome}. */
    static Mono<GroupedOutcome> chunkedGrouped(
            List<String> keys, int size, int concurrency, Function<List<String>, Mono<GroupedOutcome>> call) {
        return chunkedWith(
                keys, size, concurrency, call, GroupedOutcome::failed, GroupedOutcome.EMPTY, GroupedOutcome::merge);
    }

    /** The chunking and bounded concurrency both outcome kinds share. */
    private static <O> Mono<O> chunkedWith(
            List<String> keys,
            int size,
            int concurrency,
            Function<List<String>, Mono<O>> call,
            BiFunction<List<String>, Throwable, O> failed,
            O empty,
            BinaryOperator<O> merge) {
        Function<List<String>, Mono<O>> safe =
                chunk -> Mono.defer(() -> call.apply(chunk)).onErrorResume(e -> Mono.just(failed.apply(chunk, e)));
        if (size <= 0 || keys.size() <= size) return safe.apply(keys);
        List<List<String>> chunks = new ArrayList<>((keys.size() + size - 1) / size);
        for (int from = 0; from < keys.size(); from += size) {
            chunks.add(keys.subList(from, Math.min(from + size, keys.size())));
        }
        int inFlight = concurrency <= 0 ? Integer.MAX_VALUE : concurrency;
        return Flux.fromIterable(chunks).flatMap(safe, inFlight).reduce(empty, merge);
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
