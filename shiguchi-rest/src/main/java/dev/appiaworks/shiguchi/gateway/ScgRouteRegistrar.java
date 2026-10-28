package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.routing.HttpPathTemplate;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Registers SCG RouteDefinitions programmatically from a ServiceEndpointContext.
 *
 * Each REST-bound RPC becomes an SCG route with:
 *  - Path + Method predicates (matching the proto google.api.http path template)
 *  - URI pointing to the gRPC backend (grpc://host:port)
 *  - ShiguchiFilter handles actual gRPC dispatch (registered globally at order 10150)
 *
 * SCG routing is used for its cross-cutting infrastructure (rate limiting, circuit breaker,
 * auth token relay, metrics). The actual gRPC invocation always goes through ShiguchiFilter.
 */
@Component
@ConditionalOnClass(RouteDefinitionWriter.class)
public class ScgRouteRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ScgRouteRegistrar.class);

    /** SCG's per-route response timeout metadata key ({@code RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR}). */
    static final String RESPONSE_TIMEOUT_ATTR = "response-timeout";

    private final RouteDefinitionWriter routeDefinitionWriter;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public ScgRouteRegistrar(
            RouteDefinitionWriter routeDefinitionWriter,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.routeDefinitionWriter = routeDefinitionWriter;
        this.eventPublisher = eventPublisher;
    }

    public void register(ServiceEndpointContext ctx) {
        for (RouteDefinition route : ctx.getRoutes()) {
            if (!route.hasRestBinding()) continue;
            try {
                registerRoute(route, ctx);
            } catch (Exception e) {
                log.warn(
                        "Failed to register SCG route for {}/{}: {}",
                        route.getFullServiceName(),
                        route.getMethodName(),
                        e.getMessage());
            }
        }
        refreshRoutes();
    }

    public void deregister(ServiceEndpointContext ctx) {
        for (RouteDefinition route : ctx.getRoutes()) {
            if (!route.hasRestBinding()) continue;
            String routeId = routeId(route);
            routeDefinitionWriter.delete(Mono.just(routeId)).onErrorComplete().subscribe();
        }
        refreshRoutes();
    }

    /**
     * SCG's CachingRouteLocator only re-reads the RouteDefinition repository on a
     * RefreshRoutesEvent — without it, routes written after the locator's initial load
     * (deferred startup retries, schema refresh) never become routable.
     */
    private void refreshRoutes() {
        eventPublisher.publishEvent(new org.springframework.cloud.gateway.event.RefreshRoutesEvent(this));
    }

    private void registerRoute(RouteDefinition route, ServiceEndpointContext ctx) {
        String routeId = routeId(route);
        var scgRoute = new org.springframework.cloud.gateway.route.RouteDefinition();
        scgRoute.setId(routeId);
        // gRPC routes: ShiguchiFilter writes the response itself, so the URI is never used.
        // HTTP passthrough routes: SCG proxies to the real backend after the filter falls through.
        scgRoute.setUri(route.isPassthrough() ? URI.create(route.getUpstreamUri()) : URI.create("no://op"));

        List<org.springframework.cloud.gateway.handler.predicate.PredicateDefinition> predicates = new ArrayList<>();

        var pathPred = new org.springframework.cloud.gateway.handler.predicate.PredicateDefinition();
        pathPred.setName("Path");
        // The SCG predicate only has to match; variables are extracted by the gateway filter from
        // the parsed template. PathPattern cannot read google.api.http variables ({book.id},
        // {name=shelves/*}), so the predicate gets the template's segments as plain wildcards.
        String path = route.getHttpPath();
        pathPred.addArg(
                "pattern",
                HttpPathTemplate.isTemplate(path) ? HttpPathTemplate.of(path).toPathPattern() : path);
        predicates.add(pathPred);

        var methodPred = new org.springframework.cloud.gateway.handler.predicate.PredicateDefinition();
        methodPred.setName("Method");
        methodPred.addArg("methods", route.getHttpMethod());
        predicates.add(methodPred);

        scgRoute.setPredicates(predicates);
        // Cross-cutting filters are applied globally via default-filters. A passthrough route
        // under a path-prefix strips that prefix so the backend sees the path its own document
        // declares.
        scgRoute.setFilters(passthroughFilters(route, ctx));
        // Passthrough routes get the resolved HTTP timeout as SCG's per-route response timeout (ms).
        // gRPC routes are answered by ShiguchiFilter itself and carry none.
        if (route.isPassthrough() && route.getUpstreamTimeout() != null) {
            scgRoute.setMetadata(java.util.Map.of(
                    RESPONSE_TIMEOUT_ATTR, route.getUpstreamTimeout().toMillis()));
        }

        routeDefinitionWriter
                .save(Mono.just(scgRoute))
                .subscribe(
                        v -> log.debug(
                                "Registered SCG route [{}] {} {}", routeId, route.getHttpMethod(), route.getHttpPath()),
                        e -> log.warn("Failed to save SCG route {}: {}", routeId, e.getMessage()));
    }

    private static List<org.springframework.cloud.gateway.filter.FilterDefinition> passthroughFilters(
            RouteDefinition route, ServiceEndpointContext ctx) {
        if (!route.isPassthrough()) return List.of();
        int parts = prefixSegments(ctx.getConfig().getPathPrefix());
        if (parts == 0) return List.of();
        var strip = new org.springframework.cloud.gateway.filter.FilterDefinition();
        strip.setName("StripPrefix");
        strip.addArg("parts", String.valueOf(parts));
        return List.of(strip);
    }

    /** Number of non-empty segments in a path prefix: {@code /inv} → 1, {@code /api/v2} → 2, blank → 0. */
    static int prefixSegments(String prefix) {
        if (prefix == null || prefix.isBlank()) return 0;
        int n = 0;
        for (String s : prefix.split("/")) {
            if (!s.isBlank()) n++;
        }
        return n;
    }

    /**
     * Route id, unique per HTTP binding rather than per gRPC method.
     *
     * <p>Several registered backends can expose the same proto service — sharded deployments, or a
     * service and its replacement — and each gets its own path via {@code path-prefix}. Keying on
     * {@code service/method} alone gave them one id, so registering the second silently overwrote
     * the first's SCG route and that path started returning 404 with no diagnostic anywhere. The
     * method+path suffix keeps distinct bindings distinct; two routes that genuinely share a path
     * are caught earlier, by the duplicate detection in {@code GrpcRouteRegistry}.
     */
    private static String routeId(RouteDefinition route) {
        String base = route.getFullServiceName() + "/" + route.getMethodName();
        return route.hasRestBinding() ? base + "#" + route.getHttpMethod() + ":" + route.getHttpPath() : base;
    }
}
