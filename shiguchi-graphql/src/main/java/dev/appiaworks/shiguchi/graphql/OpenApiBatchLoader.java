package dev.appiaworks.shiguchi.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import io.grpc.Metadata;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
 * <p>The Authorization header from gRPC {@link Metadata} is forwarded as an HTTP header
 * when present so REST services receive the same bearer token.
 */
public class OpenApiBatchLoader implements BatchLoader {

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

    @Override
    public Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
        if (keys == null || keys.isEmpty()) {
            return Mono.just(Map.of());
        }
        log.debug(
                "[OpenApiBatchLoader] Loading {} keys for type={} via {}",
                keys.size(),
                config.getType(),
                config.getEndpoint());

        return config.isBatchEndpoint() ? loadBatch(keys, metadata) : loadParallel(keys, metadata);
    }

    // -------------------------------------------------------------------------
    // Batch strategy: GET /endpoint?idsParam=id1,id2,id3
    // -------------------------------------------------------------------------

    private Mono<Map<String, Map<String, Object>>> loadBatch(List<String> keys, Metadata metadata) {
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
                .map(body -> parseBatchResponse(body, keys))
                .doOnError(ex -> log.warn(
                        "[OpenApiBatchLoader] Batch request failed for type={}: {}", config.getType(), ex.getMessage()))
                .onErrorReturn(new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseBatchResponse(String body, List<String> requestedKeys) {
        try {
            List<Map<String, Object>> items;
            if (config.getResultField() != null && !config.getResultField().isBlank()) {
                Map<String, Object> wrapper = objectMapper.readValue(body, MAP_TYPE);
                Object raw = wrapper.get(config.getResultField());
                if (!(raw instanceof List)) {
                    log.warn(
                            "[OpenApiBatchLoader] resultField '{}' not a list in response for type={}",
                            config.getResultField(),
                            config.getType());
                    return new LinkedHashMap<>();
                }
                items = (List<Map<String, Object>>) raw;
            } else {
                items = objectMapper.readValue(body, LIST_TYPE);
            }

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
        } catch (Exception ex) {
            log.warn(
                    "[OpenApiBatchLoader] Failed to parse batch response for type={}: {}",
                    config.getType(),
                    ex.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // -------------------------------------------------------------------------
    // Parallel strategy: N concurrent GETs to endpoint with {id} substituted
    // -------------------------------------------------------------------------

    private Mono<Map<String, Map<String, Object>>> loadParallel(List<String> keys, Metadata metadata) {
        List<Mono<Map.Entry<String, Map<String, Object>>>> singles = keys.stream()
                .map(id -> loadSingle(id, metadata)
                        .map(entity -> Map.entry(id, entity))
                        .onErrorResume(ex -> {
                            log.warn(
                                    "[OpenApiBatchLoader] Single GET failed for type={} id={}: {}",
                                    config.getType(),
                                    id,
                                    ex.getMessage());
                            return Mono.empty();
                        }))
                .collect(Collectors.toList());

        return Flux.merge(singles)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private Mono<Map<String, Object>> loadSingle(String id, Metadata metadata) {
        String path = config.getEndpoint().replace("{id}", id);
        String authHeader = extractAuth(metadata);
        return webClient
                .get()
                .uri(path)
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
                        log.warn(
                                "[OpenApiBatchLoader] Failed to parse single response for type={} id={}: {}",
                                config.getType(),
                                id,
                                ex.getMessage());
                        return new LinkedHashMap<String, Object>();
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
