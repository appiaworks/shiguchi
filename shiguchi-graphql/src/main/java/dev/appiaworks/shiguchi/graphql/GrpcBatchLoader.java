package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    public GrpcBatchLoader(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            ReactiveGrpcDispatcher dispatcher,
            String batchKeyJsonName,
            String batchResultJsonName,
            String entityKeyJsonName) {
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

        Map<String, Object> requestArgs = Map.of(batchKeyJsonName, new ArrayList<>(keys));

        if (route.getMethodType() == MethodDescriptor.MethodType.SERVER_STREAMING) {
            // Streaming batch RPC: each stream item is one entity; collect into map keyed by entity key.
            return dispatcher
                    .dispatchStreamingToMap(route, ctx, requestArgs, metadata)
                    .timeout(Duration.ofSeconds(10))
                    .collectList()
                    .map(items -> {
                        log.debug("[BatchLoader] Got {} results for route={}", items.size(), route.getGraphQlName());
                        return (Map<String, Map<String, Object>>) items.stream()
                                .collect(Collectors.toMap(
                                        item -> String.valueOf(item.get(entityKeyJsonName)),
                                        item -> item,
                                        (a, b) -> a,
                                        LinkedHashMap::new));
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
                        Duration.ofSeconds(10),
                        Mono.error(new java.util.concurrent.TimeoutException(
                                "Batch gRPC call timed out for route: " + route.getGraphQlName())))
                .map(response -> {
                    List<Map<String, Object>> items =
                            (List<Map<String, Object>>) response.getOrDefault(batchResultJsonName, List.of());
                    log.debug("[BatchLoader] Got {} results for route={}", items.size(), route.getGraphQlName());
                    return (Map<String, Map<String, Object>>) items.stream()
                            .collect(Collectors.toMap(
                                    item -> String.valueOf(item.get(entityKeyJsonName)),
                                    item -> item,
                                    (a, b) -> a,
                                    LinkedHashMap::new));
                })
                .doOnError(ex -> log.warn(
                        "[BatchLoader] Batch load failed for route={}: {}", route.getGraphQlName(), ex.getMessage()));
    }
}
