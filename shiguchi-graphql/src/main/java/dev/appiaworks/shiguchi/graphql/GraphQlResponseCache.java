package dev.appiaworks.shiguchi.graphql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.appiaworks.shiguchi.config.GraphQlConfig;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import graphql.ExecutionResult;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The GraphQL response cache: whole query responses kept for the TTL of the methods behind their
 * root fields ({@code (shiguchi.method).cache_ttl_seconds}), keyed by document, operation,
 * variables and, when the key is per caller, the principal. {@link GraphQlResponseCacheInterceptor}
 * decides per request; this class holds the entries, the parsed shape of each document and the
 * counters, and empties itself on every schema reload.
 *
 * <p>Bounded by {@code shiguchi.graphql.response-cache.max-entries}, least recently used first.
 * Each gateway replica has its own cache; there is no cross-instance invalidation.
 */
class GraphQlResponseCache implements SchemaReloadListener {

    private static final Logger log = LoggerFactory.getLogger(GraphQlResponseCache.class);
    static final String METER = "shiguchi.graphql.response.cache";

    enum Outcome {
        HIT,
        STALE,
        MISS
    }

    /** One cached response: the result, the services it came from, and its clock. */
    static final class Entry {
        final ExecutionResult result;
        final Set<String> services;
        final Instant storedAt;
        final Instant expiresAt;
        final Instant staleUntil;
        final AtomicBoolean refreshing = new AtomicBoolean();

        Entry(ExecutionResult result, Set<String> services, Instant storedAt, Duration ttl, Duration swr) {
            this.result = result;
            this.services = Set.copyOf(services);
            this.storedAt = storedAt;
            this.expiresAt = storedAt.plus(ttl);
            this.staleUntil = expiresAt.plus(swr);
        }

        boolean fresh(Instant now) {
            return now.isBefore(expiresAt);
        }

        boolean servableStale(Instant now) {
            return now.isBefore(staleUntil);
        }

        long ageSeconds(Instant now) {
            return Math.max(0, Duration.between(storedAt, now).getSeconds());
        }
    }

    /** One operation of a document: its kind and the names of its root fields, or opaque. */
    record OperationShape(OperationDefinition.Operation type, List<String> rootFields, boolean opaque) {}

    /**
     * What the interceptor does with one request. {@code ttl} is null when the query cannot be
     * cached (a root field without a route or without a TTL); {@code services} are the gRPC
     * services behind the root fields, what a mutation evicts; {@code evictAll} marks a mutation
     * whose root selection the cache could not read (a fragment at the root).
     */
    record Plan(
            OperationDefinition.Operation type,
            Duration ttl,
            Set<String> services,
            boolean perCaller,
            boolean evictAll) {}

    private final GraphQlConfig.ResponseCacheConfig config;
    private final GrpcRouteRegistry registry;
    private final Clock clock;
    private final Cache<String, Entry> entries;
    private final Cache<String, Map<String, OperationShape>> shapes;
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private final Map<Outcome, AtomicLong> counts = new EnumMap<>(Outcome.class);

    GraphQlResponseCache(GraphQlConfig.ResponseCacheConfig config, GrpcRouteRegistry registry, MeterRegistry meters) {
        this(config, registry, meters, Clock.systemUTC());
    }

    GraphQlResponseCache(
            GraphQlConfig.ResponseCacheConfig config, GrpcRouteRegistry registry, MeterRegistry meters, Clock clock) {
        this.config = config;
        this.registry = registry;
        this.clock = clock;
        this.entries = Caffeine.newBuilder().maximumSize(config.getMaxEntries()).build();
        this.shapes = Caffeine.newBuilder().maximumSize(config.getMaxEntries()).build();
        for (Outcome o : Outcome.values()) {
            counts.put(o, new AtomicLong());
            if (meters != null) {
                counters.put(
                        o,
                        Counter.builder(METER)
                                .description("GraphQL responses by where they came from")
                                .tag("result", o.name().toLowerCase(Locale.ROOT))
                                .register(meters));
            }
        }
    }

    Clock clock() {
        return clock;
    }

    Duration staleWhileRevalidate() {
        Duration swr = config.getStaleWhileRevalidate();
        return swr == null || swr.isNegative() ? Duration.ZERO : swr;
    }

    // ------------------------------------------------------------------ planning

    /**
     * Reads the document (parsed once per distinct text, kept alongside the entries) and resolves
     * its root fields against the current routes. Null when the document does not parse or names
     * no single operation: graphql-java reports that, the cache stays out of the way.
     */
    Plan plan(String document, String operationName) {
        Map<String, OperationShape> ops = shapes.get(document, GraphQlResponseCache::shapesOf);
        if (ops == null || ops.isEmpty()) return null;
        OperationShape shape;
        if (operationName != null && !operationName.isEmpty()) {
            shape = ops.get(operationName);
        } else {
            shape = ops.size() == 1 ? ops.values().iterator().next() : null;
        }
        if (shape == null) return null;
        if (shape.opaque()) {
            return new Plan(shape.type(), null, Set.of(), false, true);
        }
        Set<String> services = new LinkedHashSet<>();
        boolean perCaller = config.isVaryByPrincipal();
        boolean cacheable = !shape.rootFields().isEmpty();
        int ttl = Integer.MAX_VALUE;
        for (String field : shape.rootFields()) {
            List<RouteDefinition> routes = registry.findByGraphQlName(field);
            if (routes.isEmpty()) {
                cacheable = false;
                continue;
            }
            for (RouteDefinition route : routes) {
                services.add(route.getFullServiceName());
                if (route.getCacheTtlSeconds() <= 0) cacheable = false;
                else ttl = Math.min(ttl, route.getCacheTtlSeconds());
                if (!route.getAuthScopes().isEmpty()) perCaller = true;
            }
        }
        Duration duration = cacheable && ttl != Integer.MAX_VALUE ? Duration.ofSeconds(ttl) : null;
        return new Plan(shape.type(), duration, services, perCaller, false);
    }

    private static Map<String, OperationShape> shapesOf(String document) {
        Document parsed;
        try {
            parsed = Parser.parse(document);
        } catch (RuntimeException e) {
            log.debug("Response cache cannot read a document, leaving it to the engine: {}", e.toString());
            return Map.of();
        }
        Map<String, OperationShape> ops = new LinkedHashMap<>();
        for (Definition<?> definition : parsed.getDefinitions()) {
            if (!(definition instanceof OperationDefinition op)) continue;
            List<String> fields = new ArrayList<>();
            boolean opaque = false;
            if (op.getSelectionSet() != null) {
                for (Selection<?> selection : op.getSelectionSet().getSelections()) {
                    if (selection instanceof Field field) fields.add(field.getName());
                    else opaque = true;
                }
            }
            String name = op.getName() == null ? "" : op.getName();
            ops.put(name, new OperationShape(op.getOperation(), List.copyOf(fields), opaque));
        }
        return ops;
    }

    // ------------------------------------------------------------------ keys

    /** SHA-256 over the document, the operation, the variables in canonical form and the caller. */
    static String key(String document, String operationName, Map<String, Object> variables, String principal) {
        StringBuilder sb = new StringBuilder(document.length() + 64);
        sb.append(document).append('\u0000');
        sb.append(operationName == null ? "" : operationName).append('\u0000');
        canonical(variables, sb);
        sb.append('\u0000').append(principal == null ? "" : principal);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A deterministic rendering of the variables: maps by sorted key, so field order never matters. */
    static void canonical(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(e.getKey(), sb);
                sb.append(':');
                canonical(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                canonical(item, sb);
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            quote(value.toString(), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ entries

    Entry get(String key) {
        return entries.getIfPresent(key);
    }

    Entry store(String key, ExecutionResult result, Set<String> services, Duration ttl) {
        Entry entry = new Entry(result, services, clock.instant(), ttl, staleWhileRevalidate());
        entries.put(key, entry);
        return entry;
    }

    /** Drops every entry that came, in part or in whole, from one of these services. */
    void evictServices(Set<String> services) {
        if (services.isEmpty()) return;
        entries.asMap().values().removeIf(entry -> {
            for (String s : entry.services) {
                if (services.contains(s)) return true;
            }
            return false;
        });
    }

    void invalidateAll() {
        entries.invalidateAll();
    }

    @Override
    public void onSchemaReloaded() {
        entries.invalidateAll();
        shapes.invalidateAll();
        log.debug("GraphQL response cache emptied after schema reload");
    }

    void record(Outcome outcome) {
        counts.get(outcome).incrementAndGet();
        Counter counter = counters.get(outcome);
        if (counter != null) counter.increment();
    }

    long count(Outcome outcome) {
        return counts.get(outcome).get();
    }

    long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }
}
