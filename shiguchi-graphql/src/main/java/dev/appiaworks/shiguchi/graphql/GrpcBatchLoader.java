package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Reactive batch loader for gRPC endpoints that support batching.
 *
 * <p>Given a list of string IDs, dispatches a single gRPC call and returns a
 * {@code Mono<Map<K,V>>} keyed by entity ID. Absent keys produce no entry; the DataLoader
 * maps them to null automatically via {@code registerMappedBatchLoader}.
 *
 * <p>Server-streaming RPCs ({@code returns (stream T)}) are collected via
 * {@code dispatchStreamingToMap}; unary RPCs extract a repeated field from the response.
 */
public class GrpcBatchLoader implements BatchLoader {
    private static final Logger log = LoggerFactory.getLogger(GrpcBatchLoader.class);

    private final RouteDefinition route;
    private final ServiceEndpointContext ctx;
    private final ReactiveGrpcDispatcher dispatcher;
    private final String batchKeyJsonName;
    private final String batchResultJsonName;
    private final String entityKeyJsonName;
    private final Duration timeout;

    public GrpcBatchLoader(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            ReactiveGrpcDispatcher dispatcher,
            String batchKeyJsonName,
            String batchResultJsonName,
            String entityKeyJsonName) {
        this(route, ctx, dispatcher, batchKeyJsonName, batchResultJsonName, entityKeyJsonName, Duration.ofSeconds(10));
    }

    /** {@code timeout} bounds one batch call; past it the call fails and its keys resolve to null. */
    public GrpcBatchLoader(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            ReactiveGrpcDispatcher dispatcher,
            String batchKeyJsonName,
            String batchResultJsonName,
            String entityKeyJsonName,
            Duration timeout) {
        this.timeout = timeout;
        this.route = route;
        this.ctx = ctx;
        this.dispatcher = dispatcher;
        this.batchKeyJsonName = batchKeyJsonName;
        this.batchResultJsonName = batchResultJsonName;
        this.entityKeyJsonName = entityKeyJsonName;
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
        log.debug("[BatchLoader] Dispatching batch for route={} keys={}", route.getGraphQlName(), keys);

        // The pipeline hands over a list it owns and never touches again, so it is sent as it is.
        Map<String, Object> requestArgs = Map.of(batchKeyJsonName, keys);

        if (route.getMethodType() == MethodDescriptor.MethodType.SERVER_STREAMING) {
            // Streaming batch RPC: each stream item is one entity; collect into map keyed by entity key.
            return dispatcher
                    .dispatchStreamingToMap(route, ctx, requestArgs, metadata)
                    .timeout(timeout)
                    .collectList()
                    .map(items -> {
                        log.debug("[BatchLoader] Got {} results for route={}", items.size(), route.getGraphQlName());
                        return byEntityKey(items);
                    })
                    .doOnError(ex -> log.warn(
                            "[BatchLoader] Batch load failed for route={}: {}",
                            route.getGraphQlName(),
                            ex.getMessage()));
        }

        // Unary batch RPC: single response contains a repeated result field.
        return dispatcher
                .dispatchToMap(route, ctx, requestArgs, metadata)
                .timeout(
                        timeout,
                        Mono.error(new java.util.concurrent.TimeoutException(
                                "Batch gRPC call timed out for route: " + route.getGraphQlName())))
                .map(response -> {
                    List<Map<String, Object>> items =
                            (List<Map<String, Object>>) response.getOrDefault(batchResultJsonName, List.of());
                    log.debug("[BatchLoader] Got {} results for route={}", items.size(), route.getGraphQlName());
                    return byEntityKey(items);
                })
                .doOnError(ex -> log.warn(
                        "[BatchLoader] Batch load failed for route={}: {}", route.getGraphQlName(), ex.getMessage()));
    }

    /** The items by their key field, the first of a repeated key winning. */
    private Map<String, Map<String, Object>> byEntityKey(List<Map<String, Object>> items) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>(items.size() * 4 / 3 + 1);
        for (Map<String, Object> item : items) {
            byKey.putIfAbsent(String.valueOf(item.get(entityKeyJsonName)), item);
        }
        return byKey;
    }
}
