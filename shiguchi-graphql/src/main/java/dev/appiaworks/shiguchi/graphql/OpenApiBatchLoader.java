package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import io.grpc.Metadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reactive batch loader for REST_DOWNSTREAM services, implementing the same {@link BatchLoader}
 * contract as {@link GrpcBatchLoader} so REST types participate in Apollo Federation
 * _entities resolution.
 *
 * <p>Two strategies are supported based on {@link RestBatchLoaderConfig#isBatchEndpoint()}:
 * <ul>
 *   <li><b>Batch</b>: one GET to {@code baseUrl + endpoint?idsParam=id1,id2,id3}. Response
 *       may be a bare JSON array or a wrapper object with the array at {@code resultField}.</li>
 *   <li><b>Parallel</b>: N concurrent GETs to {@code baseUrl + endpoint} with {@code {id}}
 *       template substituted per key. Results are merged into the output map.</li>
 * </ul>
 *
 * <p>{@code max-batch-size} splits the batch strategy's ids over several requests, and
 * {@code max-concurrency} bounds how many requests (those, or the parallel strategy's single GETs)
 * run at once.
 *
 * <p>The Authorization header from gRPC {@link Metadata} is forwarded as an HTTP header
 * when present so REST services receive the same bearer token.
 */
public class OpenApiBatchLoader implements BatchOutcome.Loader, GroupedBatchLoader.ItemSource {

    private static final Logger log = LoggerFactory.getLogger(OpenApiBatchLoader.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};
    private static final String AUTHORIZATION = "Authorization";

    private final WebClient webClient;
    private final RestBatchLoaderConfig config;
    private final ObjectMapper objectMapper;

    public OpenApiBatchLoader(WebClient webClient, RestBatchLoaderConfig config, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * A failed request fails the keys it carried, not the ones other requests loaded (see
     * {@link BatchOutcome}); through {@link #loadMapped} any failure fails the whole load.
     */
    @Override
    public Mono<BatchOutcome> loadOutcome(List<String> keys, Metadata metadata) {
        if (keys == null || keys.isEmpty()) {
            return Mono.just(BatchOutcome.EMPTY);
        }
        log.debug(
                "[OpenApiBatchLoader] Loading {} keys for type={} via {}",
                keys.size(),
                config.getType(),
                config.getEndpoint());

        return config.isBatchEndpoint() ? loadBatches(keys, metadata) : loadParallel(keys, metadata);
    }

    /** At most {@code max-concurrency} requests at a time; 0 = all at once. */
    private int concurrency() {
        return config.getMaxConcurrency() <= 0 ? Integer.MAX_VALUE : config.getMaxConcurrency();
    }

    // -------------------------------------------------------------------------
    // Batch strategy: GET /endpoint?idsParam=id1,id2,id3
    // -------------------------------------------------------------------------

    private Mono<BatchOutcome> loadBatches(List<String> keys, Metadata metadata) {
        // The ids travel comma-joined, so an id with a comma would read as two ids on the other side
        // (percent-encoding it changes nothing: the server decodes it back to a comma first).
        List<String> usable = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (key.indexOf(',') >= 0) {
                log.warn(
                        "[OpenApiBatchLoader] Skipping id with a comma for type={}: the batch strategy"
                                + " cannot carry it (use batch-endpoint: false)",
                        config.getType());
            } else {
                usable.add(key);
            }
        }
        if (usable.isEmpty()) return Mono.just(BatchOutcome.EMPTY);
        return BatchLoaderPipeline.chunkedOutcome(
                usable, config.getMaxBatchSize(), config.getMaxConcurrency(), chunk -> loadBatch(chunk, metadata));
    }

    private Mono<BatchOutcome> loadBatch(List<String> keys, Metadata metadata) {
        String idsValue = String.join(",", keys);
        String authHeader = extractAuth(metadata);
        return webClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(config.getEndpoint())
                        .queryParam(config.getIdsParam(), idsValue)
                        .build())
                .headers(h -> {
                    if (authHeader != null) h.set(AUTHORIZATION, authHeader);
                })
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .map(body -> BatchOutcome.ok(parseBatchResponse(body, keys)))
                .doOnError(ex -> log.warn(
                        "[OpenApiBatchLoader] Batch request failed for type={}: {}", config.getType(), ex.getMessage()))
                .onErrorMap(ex -> RestBackendException.of(ex, config.getType(), config.getEndpoint()));
    }

    private RestBackendException unreadable(Exception cause, String what) {
        return new RestBackendException(
                200,
                config.getType(),
                config.getEndpoint(),
                "REST " + config.getType() + " " + config.getEndpoint() + " answered " + what,
                cause);
    }

    private Map<String, Map<String, Object>> parseBatchResponse(String body, List<String> requestedKeys) {
        List<Map<String, Object>> items = readItems(body);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            Object keyValue = item.get(config.getKeyField());
            if (keyValue != null) {
                result.put(String.valueOf(keyValue), item);
            }
        }
        log.debug(
                "[OpenApiBatchLoader] Parsed {} of {} requested entities for type={}",
                result.size(),
                requestedKeys.size(),
                config.getType());
        return result;
    }

    /** The entity list of a response: a bare JSON array, or the array at {@code result-field}. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readItems(String body) {
        try {
            if (config.getResultField() != null && !config.getResultField().isBlank()) {
                Map<String, Object> wrapper = objectMapper.readValue(body, MAP_TYPE);
                Object raw = wrapper.get(config.getResultField());
                if (!(raw instanceof List)) {
                    throw unreadable(null, "without a list at resultField '" + config.getResultField() + "'");
                }
                return (List<Map<String, Object>>) raw;
            }
            return objectMapper.readValue(body, LIST_TYPE);
        } catch (RestBackendException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unreadable(ex, "a body that is not a JSON list of entities");
        }
    }

    // -------------------------------------------------------------------------
    // One-to-many: the children of every parent key, as one flat list
    // -------------------------------------------------------------------------

    /**
     * The entities {@code keys} (parent ids) own, as the backend sent them, for {@link GroupedBatchLoader}:
     * one GET with every id (batch strategy), or one GET per id, flattened (parallel strategy; a 404 is
     * a parent with no children). Never split here: the pipeline chunks before it calls. A failed
     * request fails the load, and so every key of that chunk.
     */
    @Override
    public Mono<List<Map<String, Object>>> loadItems(List<String> keys, Metadata metadata) {
        if (keys == null || keys.isEmpty()) return Mono.just(List.of());
        if (!config.isBatchEndpoint()) {
            return Flux.fromIterable(keys)
                    .flatMap(id -> loadSingleItems(id, metadata), concurrency())
                    .collectList()
                    .map(lists -> {
                        List<Map<String, Object>> all = new ArrayList<>();
                        lists.forEach(all::addAll);
                        return all;
                    });
        }
        for (String key : keys) {
            if (key.indexOf(',') >= 0) {
                return Mono.error(new IllegalStateException("Parent id '" + key + "' of type " + config.getType()
                        + " has a comma: the batch strategy cannot carry it (use batch-endpoint: false)"));
            }
        }
        String authHeader = extractAuth(metadata);
        return webClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(config.getEndpoint())
                        .queryParam(config.getIdsParam(), String.join(",", keys))
                        .build())
                .headers(h -> {
                    if (authHeader != null) h.set(AUTHORIZATION, authHeader);
                })
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .map(this::readItems)
                .doOnError(ex -> log.warn(
                        "[OpenApiBatchLoader] Grouped request failed for type={}: {}",
                        config.getType(),
                        ex.getMessage()))
                .onErrorMap(ex -> RestBackendException.of(ex, config.getType(), config.getEndpoint()));
    }

    private Mono<List<Map<String, Object>>> loadSingleItems(String id, Metadata metadata) {
        String authHeader = extractAuth(metadata);
        return webClient
                .get()
                .uri(config.getEndpoint(), Map.of("id", id))
                .headers(h -> {
                    if (authHeader != null) h.set(AUTHORIZATION, authHeader);
                })
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .map(this::readItems)
                .onErrorResume(WebClientResponseException.NotFound.class, ex -> Mono.just(List.of()))
                .onErrorMap(ex -> RestBackendException.of(ex, config.getType(), config.getEndpoint()));
    }

    // -------------------------------------------------------------------------
    // Parallel strategy: N concurrent GETs to endpoint with {id} substituted
    // -------------------------------------------------------------------------

    /** A 404 for an id means that id does not exist (absent, no error); any other failure fails that id only. */
    private Mono<BatchOutcome> loadParallel(List<String> keys, Metadata metadata) {
        return Flux.fromIterable(keys)
                .flatMap(
                        id -> loadSingle(id, metadata)
                                .map(entity -> BatchOutcome.ok(Map.of(id, entity)))
                                .onErrorResume(WebClientResponseException.NotFound.class, ex -> {
                                    log.debug(
                                            "[OpenApiBatchLoader] 404 for type={} id={}: absent", config.getType(), id);
                                    return Mono.just(BatchOutcome.EMPTY);
                                })
                                .onErrorResume(ex -> {
                                    log.warn(
                                            "[OpenApiBatchLoader] Single GET failed for type={} id={}: {}",
                                            config.getType(),
                                            id,
                                            ex.getMessage());
                                    return Mono.just(BatchOutcome.failed(
                                            List.of(id),
                                            RestBackendException.of(ex, config.getType(), config.getEndpoint())));
                                }),
                        concurrency())
                .collect(() -> new BatchOutcome(new LinkedHashMap<>(), new LinkedHashMap<>()), (all, one) -> {
                    one.entities().forEach(all.entities()::putIfAbsent);
                    all.failures().putAll(one.failures());
                });
    }

    private Mono<Map<String, Object>> loadSingle(String id, Metadata metadata) {
        String authHeader = extractAuth(metadata);
        // Template expansion encodes the id, so "a/b?c" stays one path segment and cannot add a query.
        return webClient
                .get()
                .uri(config.getEndpoint(), Map.of("id", id))
                .headers(h -> {
                    if (authHeader != null) h.set(AUTHORIZATION, authHeader);
                })
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .map(body -> {
                    try {
                        return objectMapper.readValue(body, MAP_TYPE);
                    } catch (Exception ex) {
                        throw unreadable(ex, "a body that is not a JSON object");
                    }
                });
    }

    // -------------------------------------------------------------------------

    private static String extractAuth(Metadata metadata) {
        Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        String value = metadata.get(authKey);
        return (value != null && !value.isBlank()) ? value : null;
    }
}
