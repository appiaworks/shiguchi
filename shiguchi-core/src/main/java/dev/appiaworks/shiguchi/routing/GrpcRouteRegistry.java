package dev.appiaworks.shiguchi.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Central registry for all gateway routing state.
 *
 * Holds a ServiceEndpointContext per backend service name and two
 * pre-built indexes for O(1) route resolution at request time.
 *
 * Index maps are rebuilt atomically via volatile reference swap on every
 * registerContext() / replaceContext() call, so in-flight requests that
 * already captured a reference to the old index complete normally.
 *
 * Template routes (paths containing {@code {variables}}) are parsed into
 * {@link HttpPathTemplate}s at index-build time, so per-request matching walks a parsed
 * segment list. A template the parser rejects is logged and left out; the service's other
 * routes still register.
 *
 * <p><b>Duplicate bindings.</b> Two services can declare the same REST path or the same GraphQL
 * field name — usually a service and its replacement being served side by side during a migration.
 * The first claim wins, contexts are visited in service-name order so "first" is predictable rather
 * than a function of hash layout, and every collision is logged at WARN naming both contenders.
 *
 * <p>Registration deliberately does not fail on a collision. Contexts arrive one at a time and
 * indexes are rebuilt on each arrival, so throwing here would abort startup based on how far
 * through registration the gateway happened to be, and would kill the refresh scheduler's thread
 * on a transient overlap during {@link #replaceContext}. A loud warning plus a deterministic
 * winner keeps the gateway serving while making the misconfiguration impossible to miss.
 */
@Component
public class GrpcRouteRegistry {

    private static final Logger log = LoggerFactory.getLogger(GrpcRouteRegistry.class);

    // Per-service context — ConcurrentHashMap for atomic put/replace
    private final Map<String, ServiceEndpointContext> contextByServiceName = new ConcurrentHashMap<>();

    // Volatile index references — swapped atomically on every context registration/replacement
    private volatile Map<String, RouteDefinition> restRouteIndex = Collections.emptyMap();
    private volatile Map<String, List<RouteDefinition>> graphqlRouteIndex = Collections.emptyMap();
    // Pre-compiled template patterns for routes whose path contains {variables}, bucketed by
    // METHOD|<static prefix up to the first variable segment>. A lookup walks the request path's
    // prefixes from longest to shortest and only pattern-matches the routes under each, so the
    // cost is O(segments) map hits rather than O(template routes) pattern matches — the
    // difference between 1.6 µs and 125 µs per templated request at 200 vs 20 000 routes
    // (RouteRegistryBenchmark). Templates starting with a variable live under the empty prefix.
    private volatile Map<String, List<Map.Entry<HttpPathTemplate, RouteDefinition>>> templateRoutes = Map.of();
    // O(1) route→context lookup — rebuilt alongside the other indexes
    private volatile Map<RouteDefinition, ServiceEndpointContext> routeToContext = Collections.emptyMap();
    // service/method → route, for merge sources. Keyed both with and without the endpoint, so a
    // source that names its registration and one that does not are both O(1).
    private volatile Map<String, RouteDefinition> sourceMethodIndex = Collections.emptyMap();

    // -------------------------------------------------------------------------
    // Context registration — called at startup and on refresh
    // -------------------------------------------------------------------------

    public void registerContext(ServiceEndpointContext ctx) {
        contextByServiceName.put(ctx.getConfig().getName(), ctx);
        rebuildIndexes();
        log.info(
                "Registered context for service '{}' with {} routes",
                ctx.getConfig().getName(),
                ctx.getRoutes().size());
    }

    public void replaceContext(String serviceName, ServiceEndpointContext newCtx) {
        contextByServiceName.put(serviceName, newCtx);
        rebuildIndexes();
        log.info(
                "Replaced context for service '{}' with {} routes",
                serviceName,
                newCtx.getRoutes().size());
    }

    private void rebuildIndexes() {
        Map<String, RouteDefinition> newRest = new ConcurrentHashMap<>();
        // LinkedHashMap, not ConcurrentHashMap: schema generation reads this map's iteration order,
        // so hash-bucket ordering would reshuffle the generated SDL between otherwise identical runs.
        // The map is published as an immutable snapshot, so it is never concurrently mutated.
        Map<String, List<RouteDefinition>> newGraphQl = new java.util.LinkedHashMap<>();
        Map<String, List<Map.Entry<HttpPathTemplate, RouteDefinition>>> newTemplates = new HashMap<>();
        Map<RouteDefinition, ServiceEndpointContext> newRouteToCtx = new HashMap<>();
        Map<String, RouteDefinition> newSourceMethods = new HashMap<>();

        // Which service claimed each key, so a collision can name both contenders.
        Map<String, String> restClaimedBy = new HashMap<>();
        Map<String, String> graphQlClaimedBy = new HashMap<>();

        for (ServiceEndpointContext ctx : contextsInStableOrder()) {
            String serviceName = ctx.getConfig().getName();
            for (RouteDefinition route : ctx.getRoutes()) {
                newRouteToCtx.put(route, ctx);
                newSourceMethods.put(sourceKey(serviceName, route.getFullServiceName(), route.getMethodName()), route);
                // First registration wins the unqualified key, matching the old scan's findFirst().
                newSourceMethods.putIfAbsent(sourceKey("", route.getFullServiceName(), route.getMethodName()), route);
                if (route.hasRestBinding()) {
                    HttpPathTemplate template = null;
                    if (HttpPathTemplate.isTemplate(route.getHttpPath())) {
                        try {
                            template = HttpPathTemplate.of(route.getHttpPath());
                        } catch (IllegalArgumentException e) {
                            log.warn(
                                    "Skipping REST route {} for {}/{}: {}",
                                    route.getHttpMethod(),
                                    route.getFullServiceName(),
                                    route.getMethodName(),
                                    e.getMessage());
                            continue;
                        }
                    }
                    String key = restKey(route.getHttpMethod(), route.getHttpPath());
                    String incumbent = restClaimedBy.putIfAbsent(key, serviceName);
                    if (incumbent != null) {
                        log.warn(
                                "Duplicate REST route {} {} declared by both '{}' ({}/{}) and '{}' ({}/{}); "
                                        + "keeping '{}' and ignoring the other. Two services must not bind the same "
                                        + "path — remove or re-path one of them.",
                                route.getHttpMethod(),
                                route.getHttpPath(),
                                incumbent,
                                newRest.get(key).getFullServiceName(),
                                newRest.get(key).getMethodName(),
                                serviceName,
                                route.getFullServiceName(),
                                route.getMethodName(),
                                incumbent);
                        continue; // first claim wins; skip the template entry too
                    }
                    newRest.put(key, route);
                    if (template != null) {
                        newTemplates
                                .computeIfAbsent(
                                        templateKey(route.getHttpMethod(), staticPrefix(route.getHttpPath())),
                                        k -> new ArrayList<>())
                                .add(Map.entry(template, route));
                    }
                }
                if (route.hasGraphQlBinding()) {
                    String name = route.getGraphQlName();
                    String incumbent = graphQlClaimedBy.putIfAbsent(name, serviceName);
                    if (incumbent != null) {
                        log.warn(
                                "Duplicate GraphQL field '{}' declared by both '{}' and '{}' ({}/{}); keeping '{}' "
                                        + "and ignoring the other. Emitting both would put two fields of the same "
                                        + "name in the schema, which fails to parse — rename one via the "
                                        + "(shiguchi.api.graphql).name annotation.",
                                name,
                                incumbent,
                                serviceName,
                                route.getFullServiceName(),
                                route.getMethodName(),
                                incumbent);
                        continue;
                    }
                    newGraphQl.computeIfAbsent(name, k -> new ArrayList<>()).add(route);
                }
            }
        }

        this.restRouteIndex = Collections.unmodifiableMap(newRest);
        this.graphqlRouteIndex = Collections.unmodifiableMap(newGraphQl);
        Map<String, List<Map.Entry<HttpPathTemplate, RouteDefinition>>> frozenTemplates = new HashMap<>();
        newTemplates.forEach((k, v) -> frozenTemplates.put(k, List.copyOf(v)));
        this.templateRoutes = Collections.unmodifiableMap(frozenTemplates);
        this.routeToContext = Collections.unmodifiableMap(newRouteToCtx);
        this.sourceMethodIndex = Collections.unmodifiableMap(newSourceMethods);
    }

    /**
     * Contexts ordered by service name.
     *
     * <p>{@link ConcurrentHashMap#values()} iterates in hash-bucket order, so without this the
     * winner of a duplicate-route collision would depend on how the map happened to be laid out —
     * two deployments of identical config could route the same path to different backends. Sorting
     * makes "first claim wins" mean something a reader can predict from the config alone.
     */
    private List<ServiceEndpointContext> contextsInStableOrder() {
        List<ServiceEndpointContext> contexts = new ArrayList<>(contextByServiceName.values());
        contexts.sort(java.util.Comparator.comparing(ctx -> ctx.getConfig().getName()));
        return contexts;
    }

    // -------------------------------------------------------------------------
    // Route resolution — hot path, called on every request
    // -------------------------------------------------------------------------

    /**
     * Finds a REST route whose path template matches the actual request path.
     * Tries exact key first (O(1)), then the parsed templates sharing a static prefix with the path.
     *
     * @param actualPath the raw request path, still percent-encoded
     */
    public Optional<RouteDefinition> findByHttpKey(String httpMethod, String actualPath) {
        // Fast path: exact HashMap lookup (covers paths without template variables)
        RouteDefinition exact = restRouteIndex.get(restKey(httpMethod, actualPath));
        if (exact != null) return Optional.of(exact);

        // Template path: only the patterns sharing a static prefix with the request are tried,
        // longest prefix first, so a more specific template wins over a catch-all.
        Map<String, List<Map.Entry<HttpPathTemplate, RouteDefinition>>> templates = templateRoutes;
        if (templates.isEmpty()) return Optional.empty();
        String path = actualPath.startsWith("/") ? actualPath : "/" + actualPath;
        int end = path.length();
        while (true) {
            List<Map.Entry<HttpPathTemplate, RouteDefinition>> candidates =
                    templates.get(templateKey(httpMethod, path.substring(0, end)));
            if (candidates != null) {
                for (Map.Entry<HttpPathTemplate, RouteDefinition> e : candidates) {
                    if (e.getKey().matches(path)) return Optional.of(e.getValue());
                }
            }
            if (end == 0) return Optional.empty();
            int slash = path.lastIndexOf('/', end - 1);
            end = Math.max(slash, 0);
        }
    }

    /** The template's leading static segments, {@code /a/b} for {@code /a/b/{id}/c}; empty when it starts with a variable. */
    static String staticPrefix(String template) {
        String t = template.startsWith("/") ? template : "/" + template;
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{' || c == '*') {
                cut = t.lastIndexOf('/', i);
                break;
            }
        }
        String prefix = t.substring(0, Math.max(cut, 0));
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    private static String templateKey(String httpMethod, String staticPrefix) {
        return httpMethod.toUpperCase(java.util.Locale.ROOT) + "|" + staticPrefix;
    }

    public List<RouteDefinition> findByGraphQlName(String operationName) {
        return graphqlRouteIndex.getOrDefault(operationName, Collections.emptyList());
    }

    /**
     * Finds a route by its proto service and method, regardless of how it is exposed over HTTP.
     *
     * <p>Merge sources are named by {@code service/method} rather than by path, and a source may
     * have no REST binding of its own — a backend can participate in a merged endpoint without
     * being individually routable.
     */
    public Optional<RouteDefinition> findBySourceMethod(String fullServiceName, String methodName) {
        return findBySourceMethod("", fullServiceName, methodName);
    }

    /**
     * @param endpoint the registered service name to look in, or empty to search all of them.
     *     Naming it matters when several registrations expose the same proto service — sharded
     *     backends serving an identical {@code .proto} — where searching by service and method
     *     alone would keep returning whichever shard happened to be found first.
     */
    public Optional<RouteDefinition> findBySourceMethod(String endpoint, String fullServiceName, String methodName) {
        // Merge routes call this once per source per request, and scanning every context's routes
        // to answer it is O(contexts × routes). It reads from an index rebuilt alongside the other
        // route indexes instead, so a refresh swaps it wholesale and no stale entry survives.
        Map<String, RouteDefinition> index = sourceMethodIndex;
        RouteDefinition exact = index.get(sourceKey(endpoint, fullServiceName, methodName));
        if (exact != null) {
            return Optional.of(exact);
        }
        // No endpoint named: any registration exposing that service/method will do.
        return endpoint == null || endpoint.isEmpty()
                ? Optional.ofNullable(index.get(sourceKey("", fullServiceName, methodName)))
                : Optional.empty();
    }

    /** Index key; the empty-endpoint form is the "any registration" entry. */
    private static String sourceKey(String endpoint, String fullServiceName, String methodName) {
        return (endpoint == null ? "" : endpoint) + "|" + fullServiceName + "/" + methodName;
    }

    public ServiceEndpointContext contextFor(RouteDefinition route) {
        ServiceEndpointContext ctx = routeToContext.get(route);
        if (ctx == null) throw new IllegalStateException("No context for route: " + route.getFullServiceName());
        return ctx;
    }

    public Optional<ServiceEndpointContext> contextByServiceName(String name) {
        return Optional.ofNullable(contextByServiceName.get(name));
    }

    /** Finds the context that owns the given protobuf service FQN. */
    public Optional<ServiceEndpointContext> contextByServiceFqn(String serviceFqn) {
        return contextByServiceName.values().stream()
                .filter(ctx -> ctx.getServiceByFqn().containsKey(serviceFqn))
                .findFirst();
    }

    public List<RouteDefinition> allRoutes() {
        return contextByServiceName.values().stream()
                .flatMap(ctx -> ctx.getRoutes().stream())
                .toList();
    }

    /**
     * The GraphQL-bound routes that actually won their field name, paired with the context that
     * owns each — one route per field, in the same order the schema should declare them.
     *
     * <p>Schema generation must use this rather than walking every context's raw routes: emitting a
     * field for each colliding route would put two fields of the same name in one type, which the
     * SDL parser rejects, taking the whole schema down. Reading the deduplicated index keeps the
     * generated schema and the runtime dispatch agreeing on which route owns a field.
     */
    public List<Map.Entry<RouteDefinition, ServiceEndpointContext>> graphQlRoutesWithContext() {
        List<Map.Entry<RouteDefinition, ServiceEndpointContext>> result = new ArrayList<>();
        Map<RouteDefinition, ServiceEndpointContext> routeCtx = this.routeToContext;
        for (List<RouteDefinition> routes : graphqlRouteIndex.values()) {
            for (RouteDefinition route : routes) {
                ServiceEndpointContext ctx = routeCtx.get(route);
                if (ctx != null) {
                    result.add(Map.entry(route, ctx));
                }
            }
        }
        return result;
    }

    public List<ServiceEndpointContext> allContexts() {
        return List.copyOf(contextByServiceName.values());
    }

    private static String restKey(String method, String path) {
        return method.toUpperCase() + ":" + path;
    }
}
