package dev.appiaworks.shiguchi.graphql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import graphql.language.Definition;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.IntValue;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads what a live query depends on or a mutation touches: the gRPC services behind the root
 * fields of its operation. A root selection that is not a plain field (a fragment) or a field that
 * maps to no method counts as every service, {@link #ALL}.
 */
final class GraphQlLiveDependencies {

    /** The service name that stands for every service. */
    static final String ALL = "*";

    /**
     * What a document's operation is. {@code live} is false when a root field opted out
     * ({@code live: false}). {@code throttleMs} is the {@code @live} hint, or -1. */
    record Shape(OperationDefinition.Operation type, boolean live, long throttleMs, Set<String> services) {}

    private record Raw(
            OperationDefinition.Operation type, List<String> fields, boolean opaque, boolean live, long throttleMs) {}

    private final GrpcRouteRegistry registry;
    private final Cache<String, Map<String, Raw>> documents =
            Caffeine.newBuilder().maximumSize(1000).build();

    GraphQlLiveDependencies(GrpcRouteRegistry registry) {
        this.registry = registry;
    }

    /** The operation a request runs, or null when the document cannot be read or the choice is ambiguous. */
    Shape shape(String document, String operationName) {
        Map<String, Raw> operations = documents.get(document, GraphQlLiveDependencies::parse);
        if (operations == null || operations.isEmpty()) return null;
        Raw raw;
        if (operationName != null && !operationName.isEmpty()) {
            raw = operations.get(operationName);
        } else {
            raw = operations.size() == 1 ? operations.values().iterator().next() : null;
        }
        if (raw == null) return null;
        Set<String> services = new LinkedHashSet<>();
        boolean allowed = true;
        if (raw.opaque() || raw.fields().isEmpty()) services.add(ALL);
        for (String field : raw.fields()) {
            List<RouteDefinition> routes = registry.findByGraphQlName(field);
            if (routes.isEmpty()) {
                services.add(ALL);
                continue;
            }
            for (RouteDefinition route : routes) {
                services.add(route.getFullServiceName());
                if (!route.isGraphQlLive()) allowed = false;
            }
        }
        return new Shape(raw.type(), raw.live() && allowed, raw.throttleMs(), Set.copyOf(services));
    }

    /** Whether a signal for the {@code signalled} services touches an operation depending on {@code deps}. */
    static boolean intersects(Set<String> deps, Set<String> signalled) {
        if (deps.contains(ALL) || signalled.contains(ALL)) return true;
        for (String service : signalled) {
            if (deps.contains(service)) return true;
        }
        return false;
    }

    private static Map<String, Raw> parse(String document) {
        Document parsed;
        try {
            parsed = Parser.parse(document);
        } catch (RuntimeException e) {
            return Map.of();
        }
        Map<String, Raw> operations = new LinkedHashMap<>();
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
            boolean live = false;
            long throttle = -1;
            for (Directive directive : op.getDirectives()) {
                if (!"live".equals(directive.getName())) continue;
                live = true;
                var arg = directive.getArgument("throttleMs");
                if (arg != null && arg.getValue() instanceof IntValue value) {
                    throttle = value.getValue().longValue();
                }
            }
            String name = op.getName() == null ? "" : op.getName();
            operations.put(name, new Raw(op.getOperation(), List.copyOf(fields), opaque, live, throttle));
        }
        return operations;
    }
}
