package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Scans gRPC routes to identify and register batch loaders.
 *
 * <p>For each batch-enabled route, this component creates a {@link GrpcBatchLoader} instance
 * and stores it keyed by the GraphQL type name. These are discovered and registered by the
 * {@link GrpcDataLoaderRegistryFactory} during DGS initialization.
 *
 * <p>The scan runs at startup and again whenever the registered services change (a deferred
 * service loaded late, a refresh swapped a schema): {@link GrpcDataLoaderRegistryFactory} rescans
 * on each schema reload. A route that did not change keeps its loader.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GrpcBatchDataLoaderRegistrar {
    private static final Logger log = LoggerFactory.getLogger(GrpcBatchDataLoaderRegistrar.class);

    private final GrpcRouteRegistry registry;
    private final ReactiveGrpcDispatcher dispatcher;
    private final Map<String, BatchLoader> loadersByType = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, RouteDefinition> routesByType = new HashMap<>();

    public GrpcBatchDataLoaderRegistrar(GrpcRouteRegistry registry, ReactiveGrpcDispatcher dispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    /**
     * Rebuilds the loaders from the routes registered now. Safe to call again: a type whose route
     * is unchanged keeps its loader, a changed route gets a new one, a route gone drops its type.
     */
    @PostConstruct
    synchronized void scan() {
        log.debug("Scanning routes for batch loaders...");

        Map<String, BatchLoader> found = new LinkedHashMap<>();
        Map<String, RouteDefinition> foundRoutes = new HashMap<>();
        for (RouteDefinition route : registry.allRoutes()) {
            if (!route.isLoader()) {
                continue;
            }

            try {
                BatchLoader loader = routesByType.get(route.getLoaderFor()) == route
                        ? loadersByType.get(route.getLoaderFor())
                        : createBatchLoader(route);
                if (loader != null) {
                    found.put(route.getLoaderFor(), loader);
                    foundRoutes.put(route.getLoaderFor(), route);
                }
            } catch (Exception ex) {
                log.warn("Failed to register batch loader for route {}", route.getGraphQlName(), ex);
            }
        }

        synchronized (loadersByType) {
            loadersByType.clear();
            loadersByType.putAll(found);
        }
        routesByType.clear();
        routesByType.putAll(foundRoutes);

        log.info("Registered {} batch loaders for GraphQL types: {}", found.size(), found.keySet());
    }

    private BatchLoader createBatchLoader(RouteDefinition route) {
        var ctx = registry.contextFor(route);
        var methodOptional = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName());

        if (methodOptional.isEmpty()) {
            log.warn("Could not find method descriptor for batch route {}", route.getGraphQlName());
            return null;
        }

        MethodDescriptor methodDescriptor = methodOptional.get();

        String batchKeyJsonName = snakeToCamel(route.getLoaderIdsField());
        String batchResultJsonName = snakeToCamel(route.getLoaderResultField());
        String entityKeyJsonName = detectEntityKeyField(methodDescriptor, route.getLoaderResultField())
                .orElse("id");

        var loader =
                new GrpcBatchLoader(route, ctx, dispatcher, batchKeyJsonName, batchResultJsonName, entityKeyJsonName);

        log.debug(
                "Registered batch loader for type {} (route: {}, entity key: {})",
                route.getLoaderFor(),
                route.getGraphQlName(),
                entityKeyJsonName);
        return loader;
    }

    /**
     * Detects the entity key field by looking for the field marked with batch_key = true.
     *
     * <p>For unary RPCs the output type is a wrapper containing a repeated result field;
     * for server-streaming RPCs the output type IS the entity (each stream item is one entity).
     * Both paths are tried in order.
     */
    private Optional<String> detectEntityKeyField(MethodDescriptor methodDescriptor, String batchResultField) {
        Descriptor outputType = methodDescriptor.getOutputType();

        // Unary path: output wraps a repeated field named batchResultField
        if (batchResultField != null && !batchResultField.isBlank()) {
            String camelResultField = snakeToCamel(batchResultField);
            for (FieldDescriptor field : outputType.getFields()) {
                if (field.getName().equals(batchResultField)
                        || field.getJsonName().equals(camelResultField)) {
                    if (field.isRepeated() && field.getMessageType() != null) {
                        Optional<String> key = findBatchKeyField(field.getMessageType());
                        if (key.isPresent()) return key;
                    }
                    break;
                }
            }
        }

        // Streaming path: output type is the entity itself
        Optional<String> key = findBatchKeyField(outputType);
        if (key.isPresent()) return key;

        log.debug("No batch_key field found for output type {}", outputType.getFullName());
        return Optional.empty();
    }

    private Optional<String> findBatchKeyField(Descriptor entityType) {
        for (FieldDescriptor field : entityType.getFields()) {
            var gqlField = field.getOptions().getExtension(GraphQlProto.graphqlField);
            if (gqlField != null && gqlField.getBatchKey()) {
                return Optional.of(snakeToCamel(field.getName()));
            }
        }
        return Optional.empty();
    }

    /**
     * Converts snake_case strings to camelCase.
     *
     * @param snake the snake_case string
     * @return the camelCase equivalent
     */
    private static String snakeToCamel(String snake) {
        if (snake == null || snake.isBlank()) {
            return snake;
        }

        StringBuilder sb = new StringBuilder();
        boolean uppercase = false;

        for (char c : snake.toCharArray()) {
            if (c == '_') {
                uppercase = true;
            } else if (uppercase) {
                sb.append(Character.toUpperCase(c));
                uppercase = false;
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /**
     * Returns an unmodifiable view of all registered batch loaders keyed by GraphQL type name.
     *
     * @return the batch loaders map
     */
    public Map<String, BatchLoader> getLoaders() {
        return Collections.unmodifiableMap(loadersByType);
    }
}
