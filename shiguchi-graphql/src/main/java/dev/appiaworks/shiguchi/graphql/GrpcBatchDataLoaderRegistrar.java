package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GrpcLoaderConfig;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.OpenApiToDescriptor;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
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
@DependsOn({"dynamicRouteLoader", "staticRouteLoader", "restDownstreamRouteLoader"})
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GrpcBatchDataLoaderRegistrar {
    private static final Logger log = LoggerFactory.getLogger(GrpcBatchDataLoaderRegistrar.class);

    private volatile RelationshipResolver relationshipResolver = RelationshipResolver.annotationsOnly();
    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final ReactiveGrpcDispatcher dispatcher;
    private final Map<String, BatchLoader> loadersByType = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, GroupedOutcome.Loader> groupedLoaders =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, RouteDefinition> routesByType = new HashMap<>();
    private final Duration timeout;
    private final Map<String, String> ownersByType = new HashMap<>();
    private final Set<String> reportedConflicts = new HashSet<>();
    private final DataLoaderConfig config;
    private final MeterRegistry meters;
    private boolean started;
    private volatile List<RelationshipDefinition> beanRelationships = List.of();
    private volatile Set<String> beanLoaderNames = Set.of();
    private volatile OpenApiRelationshipIndex openApiIndex;

    public GrpcBatchDataLoaderRegistrar(GrpcRouteRegistry registry, ReactiveGrpcDispatcher dispatcher) {
        this(registry, dispatcher, new DataLoaderConfig(), null);
    }

    public GrpcBatchDataLoaderRegistrar(
            GrpcRouteRegistry registry, ReactiveGrpcDispatcher dispatcher, GatewayProperties properties) {
        this(registry, dispatcher, properties.getGraphql().getDataLoader(), null, properties);
    }

    @Autowired
    public GrpcBatchDataLoaderRegistrar(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            GatewayProperties properties,
            ObjectProvider<MeterRegistry> meters) {
        this(registry, dispatcher, properties.getGraphql().getDataLoader(), meters.getIfAvailable(), properties);
    }

    GrpcBatchDataLoaderRegistrar(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            DataLoaderConfig config,
            MeterRegistry meters) {
        this(registry, dispatcher, config, meters, null);
    }

    GrpcBatchDataLoaderRegistrar(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            DataLoaderConfig config,
            MeterRegistry meters,
            GatewayProperties properties) {
        this.properties = properties;
        if (properties != null) this.relationshipResolver = RelationshipResolver.of(properties, List.of());
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.config = config;
        this.timeout = config.getTimeout();
        this.meters = meters;
    }

    /** Extra relationship sources; keep in step with {@link GraphQlSchemaBuilder#setRelationshipSources}. */
    @Autowired(required = false)
    public void setRelationshipSources(List<RelationshipSource> sources) {
        this.relationshipResolver = RelationshipResolver.of(properties, sources);
        this.beanRelationships = sources.stream()
                .filter(RelationshipDefinition.class::isInstance)
                .map(RelationshipDefinition.class::cast)
                .toList();
    }

    /** The OpenAPI extensions of the REST_DOWNSTREAM services, for the checks and the loader names. */
    @Autowired(required = false)
    public void setOpenApiRelationshipIndex(OpenApiRelationshipIndex index) {
        this.openApiIndex = index;
    }

    /** The loaders declared as {@link BatchLoaderDefinition} beans: they count as loaders for the checks. */
    @Autowired(required = false)
    public void setBatchLoaderDefinitions(List<BatchLoaderDefinition> definitions) {
        this.beanLoaderNames =
                definitions.stream().map(BatchLoaderDefinition::name).collect(Collectors.toSet());
    }

    /**
     * Rebuilds the loaders from the routes registered now. Safe to call again: a type whose route
     * is unchanged keeps its loader, a changed route gets a new one, a route gone drops its type.
     *
     * <p>A type's loader belongs to one endpoint (the service's configured name). When several
     * endpoints declare a loader for the same type, {@code shiguchi.graphql.data-loader.types.<Type>.endpoint}
     * picks the owner. Without the pin the first scan (startup) throws; a later scan (a reload, a
     * late-loaded service) rejects the conflicting new loader, keeps the existing owner, logs an
     * error and counts it in {@code shiguchi.graphql.loader.conflicts}.
     */
    @PostConstruct
    synchronized void scan() {
        log.debug("Scanning routes for batch loaders...");

        // type -> endpoint name -> that endpoint's loader route. The registry's contexts hold one
        // service each, so a transient old+new overlap while a service is replaced collapses here.
        Map<String, Map<String, RouteDefinition>> candidates = new LinkedHashMap<>();
        for (ServiceEndpointContext ctx : registry.contextsInStableOrder()) {
            String endpoint = ctx.getConfig().getName();
            for (RouteDefinition route : ctx.getRoutes()) {
                if (!route.isLoader()) continue;
                candidates
                        .computeIfAbsent(loaderName(route), t -> new LinkedHashMap<>())
                        .putIfAbsent(endpoint, route);
            }
        }

        Map<String, BatchLoader> found = new LinkedHashMap<>();
        Map<String, GroupedOutcome.Loader> foundGrouped = new LinkedHashMap<>();
        Map<String, RouteDefinition> foundRoutes = new HashMap<>();
        Map<String, String> foundOwners = new HashMap<>();
        Set<String> conflictsNow = new HashSet<>();
        for (Map.Entry<String, Map<String, RouteDefinition>> entry : candidates.entrySet()) {
            String type = entry.getKey();
            Map<String, RouteDefinition> byEndpoint = entry.getValue();
            String owner = chooseOwner(type, byEndpoint, conflictsNow);
            if (owner == null) continue;
            RouteDefinition route = byEndpoint.get(owner);
            boolean unchanged = routesByType.get(type) == route && owner.equals(ownersByType.get(type));
            if (route.isGroupedLoader()) {
                if (!checkGrouped(type, route)) continue;
                try {
                    GroupedOutcome.Loader loader =
                            unchanged ? groupedLoaders.get(type) : createGroupedLoader(type, route);
                    if (loader != null) {
                        foundGrouped.put(type, loader);
                        foundRoutes.put(type, route);
                        foundOwners.put(type, owner);
                    }
                } catch (Exception ex) {
                    log.warn("Failed to register grouped batch loader for route {}", route.getGraphQlName(), ex);
                }
                continue;
            }
            try {
                BatchLoader loader = unchanged ? loadersByType.get(type) : createBatchLoader(route);
                if (loader != null) {
                    found.put(type, loader);
                    foundRoutes.put(type, route);
                    foundOwners.put(type, owner);
                }
            } catch (Exception ex) {
                log.warn("Failed to register batch loader for route {}", route.getGraphQlName(), ex);
            }
        }
        checkPins(candidates);
        checkGroupedCaches(candidates);
        checkYaml(candidates);
        checkExtensions(candidates);
        checkBeans(candidates);

        synchronized (loadersByType) {
            loadersByType.clear();
            loadersByType.putAll(found);
        }
        synchronized (groupedLoaders) {
            groupedLoaders.clear();
            groupedLoaders.putAll(foundGrouped);
        }
        routesByType.clear();
        routesByType.putAll(foundRoutes);
        ownersByType.clear();
        ownersByType.putAll(foundOwners);
        reportedConflicts.retainAll(conflictsNow);
        started = true;

        log.info("Registered {} batch loaders for GraphQL types: {}", found.size(), found.keySet());
        if (!foundGrouped.isEmpty()) {
            log.info("Registered {} one-to-many batch loaders: {}", foundGrouped.size(), foundGrouped.keySet());
        }
    }

    /**
     * The name a loader is known by: the type for a by-id loader, {@code Type#by:groupField} (the
     * group field in its JSON spelling) for a one-to-many one. It keys the ownership, the pins and
     * the DataLoader, so a type's by-id loader and its grouped loaders never collide.
     */
    static String loaderName(RouteDefinition route) {
        return route.isGroupedLoader()
                ? groupedName(route.getLoaderFor(), route.getLoaderGroupBy())
                : route.getLoaderFor();
    }

    /** {@code Type#by:groupField}, the group field in its JSON (camelCase) spelling. */
    static String groupedName(String type, String groupBy) {
        return type + "#by:" + snakeToCamel(groupBy);
    }

    /**
     * A one-to-many loader whose {@code group_by} names no field of its result entity could never
     * group anything: startup fails, a reload logs it and leaves the loader out.
     */
    private boolean checkGrouped(String name, RouteDefinition route) {
        String problem = groupedProblem(route);
        if (problem == null) return true;
        String message = "Loader " + name + " (route " + route.getGraphQlName() + "): " + problem;
        if (!started) throw new IllegalStateException(message);
        log.error(message);
        return false;
    }

    private String groupedProblem(RouteDefinition route) {
        var ctx = registry.contextFor(route);
        var method = ctx == null
                ? Optional.<MethodDescriptor>empty()
                : ctx.lookupMethod(route.getFullServiceName(), route.getMethodName());
        if (method.isEmpty()) return null; // reported as a missing method when the loader is created
        Optional<Descriptor> entity = entityDescriptor(method.get(), route.getLoaderResultField());
        if (entity.isEmpty()) return null;
        String groupBy = route.getLoaderGroupBy();
        String camel = snakeToCamel(groupBy);
        for (FieldDescriptor field : entity.get().getFields()) {
            if (field.getName().equals(groupBy) || field.getJsonName().equals(camel)) return null;
        }
        return "group_by '" + groupBy + "' is not a field of " + entity.get().getFullName()
                + ", the entity the loader returns";
    }

    /**
     * A cache entry naming a one-to-many loader ({@code data-loader.cache."Book#by:authorId"}) can
     * not work: its results are lists per parent, loaded for the request that asks, with no entity
     * id to key a cache by. Startup fails, a reload logs it.
     */
    private void checkGroupedCaches(Map<String, Map<String, RouteDefinition>> candidates) {
        for (String name : config.getCache().keySet()) {
            Map<String, RouteDefinition> byEndpoint = candidates.get(name);
            if (byEndpoint == null || byEndpoint.values().stream().noneMatch(RouteDefinition::isGroupedLoader))
                continue;
            String message = "shiguchi.graphql.data-loader.cache." + name + " names a one-to-many loader (group_by);"
                    + " only the by-id loader of a type can be cached";
            if (!started) throw new IllegalStateException(message);
            log.error(message);
        }
    }

    /**
     * Checks what the YAML declares against what the services expose: relationships, batch keys and
     * loaders. A bad entry fails startup, naming the entry and its YAML path; a reload logs it.
     */
    private void checkYaml(Map<String, Map<String, RouteDefinition>> candidates) {
        if (properties == null || properties.getServices() == null) return;
        List<String> problems = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> restTypes = restLoaderNames();
        restTypes.addAll(beanLoaderNames);
        List<ServiceDefinition> services = properties.getServices();
        for (int i = 0; i < services.size(); i++) {
            ServiceDefinition svc = services.get(i);
            if (svc.getRelationships().isEmpty()
                    && svc.getBatchKeys().isEmpty()
                    && svc.getLoaders().isEmpty()) {
                continue;
            }
            ServiceEndpointContext ctx = registry.contextsInStableOrder().stream()
                    .filter(c -> svc.getName() != null
                            && svc.getName().equals(c.getConfig().getName()))
                    .findFirst()
                    .orElse(null);
            if (ctx == null) continue; // not loaded yet: checked on the scan that sees it
            Map<String, Descriptor> types = new HashMap<>();
            for (var fd : ctx.getFileDescriptors().values()) collectMessages(fd.getMessageTypes(), types);
            String base = "shiguchi.services[" + i + "] (" + svc.getName() + ")";
            for (int j = 0; j < svc.getRelationships().size(); j++) {
                RelationshipConfig rel = svc.getRelationships().get(j);
                String where = base + ".relationships[" + j + "] (from '" + rel.getFrom() + "')";
                String problem = relationshipProblem(rel, types, candidates, restTypes, seen);
                if (problem != null) problems.add(where + ": " + problem);
            }
            for (Map.Entry<String, String> key : svc.getBatchKeys().entrySet()) {
                Descriptor type = types.get(key.getKey());
                String where = base + ".batch-keys." + key.getKey();
                if (type == null) {
                    problems.add(where + ": " + key.getKey() + " is not a message type of the service");
                } else if (findField(type, key.getValue()) == null) {
                    problems.add(where + ": '" + key.getValue() + "' is not a field of " + type.getFullName());
                }
            }
            for (int j = 0; j < svc.getLoaders().size(); j++) {
                GrpcLoaderConfig loader = svc.getLoaders().get(j);
                String where = base + ".loaders[" + j + "] (rpc '" + loader.getRpc() + "')";
                String problem = loaderProblem(loader, ctx);
                if (problem != null) problems.add(where + ": " + problem);
            }
        }
        if (problems.isEmpty()) return;
        if (!started) throw new IllegalStateException(String.join("\n", problems));
        problems.forEach(log::error);
    }

    /** The REST loaders the YAML declares: the type for a by-id one, {@code Type#by:field} for a grouped one. */
    private Set<String> restLoaderNames() {
        Set<String> names = new HashSet<>();
        OpenApiRelationshipIndex openApi = openApiIndex;
        if (openApi != null) {
            for (OpenApiRelationshipIndex.Entry entry : openApi.entries()) {
                for (var loader : entry.extensions().loaders()) {
                    RestBatchLoaderConfig l = loader.config();
                    if (l.getGroupBy() != null && !l.getGroupBy().isBlank()) {
                        names.add(groupedName(l.getType(), l.getGroupBy()));
                    } else {
                        names.add(l.getType());
                    }
                }
            }
        }
        if (properties == null || properties.getServices() == null) return names;
        for (ServiceDefinition svc : properties.getServices()) {
            for (RestBatchLoaderConfig l : svc.getRestBatchLoaders()) {
                // A REST loader with group-by is one-to-many: it serves relationships, not by-id loads
                if (l.getGroupBy() != null && !l.getGroupBy().isBlank()) {
                    if (l.getType() != null) names.add(groupedName(l.getType(), l.getGroupBy()));
                } else {
                    names.add(l.getType());
                }
            }
        }
        return names;
    }

    /**
     * Checks the {@code x-shiguchi-*} extensions of the loaded REST_DOWNSTREAM documents the way
     * {@link #checkYaml} checks the YAML: a malformed or dangling declaration (unknown schema or
     * property, no loader for the target) fails startup naming the extension and where it sits; a
     * reload logs it.
     */
    private void checkExtensions(Map<String, Map<String, RouteDefinition>> candidates) {
        OpenApiRelationshipIndex openApi = openApiIndex;
        if (openApi == null) return;
        List<OpenApiRelationshipIndex.Entry> entries = openApi.entries();
        if (entries.isEmpty()) return;
        Set<String> loaders = restLoaderNames();
        loaders.addAll(beanLoaderNames);
        List<String> problems = new java.util.ArrayList<>();
        for (OpenApiRelationshipIndex.Entry entry : entries) {
            String service = " (service " + entry.serviceName() + ")";
            entry.extensions().problems().forEach(p -> problems.add(p + service));
            Map<String, Descriptor> types = new HashMap<>();
            for (var fd : entry.context().getFileDescriptors().values()) collectMessages(fd.getMessageTypes(), types);
            Set<String> seen = new HashSet<>();
            for (var ref : entry.extensions().references()) {
                RelationshipConfig rel = new RelationshipConfig();
                rel.setFrom(ref.parentType() + "." + OpenApiToDescriptor.protoFieldName(ref.property()));
                rel.setTo(ref.targetType());
                rel.setField(ref.field());
                addExtensionProblem(problems, ref.location() + service, rel, types, candidates, loaders, seen);
            }
            for (var many : entry.extensions().hasMany()) {
                RelationshipConfig rel = new RelationshipConfig();
                rel.setFrom(many.parentType() + "." + OpenApiToDescriptor.protoFieldName(many.property()));
                rel.setTo(many.targetType());
                rel.setField(many.field());
                rel.setMany(true);
                rel.setGroupBy(many.groupBy());
                addExtensionProblem(problems, many.location() + service, rel, types, candidates, loaders, seen);
            }
        }
        if (problems.isEmpty()) return;
        if (!started) throw new IllegalStateException(String.join("\n", problems));
        problems.forEach(log::error);
    }

    private static void addExtensionProblem(
            List<String> problems,
            String where,
            RelationshipConfig rel,
            Map<String, Descriptor> types,
            Map<String, Map<String, RouteDefinition>> candidates,
            Set<String> loaders,
            Set<String> seen) {
        String problem = relationshipProblem(rel, types, candidates, loaders, seen);
        if (problem == null) return;
        if (problem.startsWith("no loader")) {
            problem += "; or put x-shiguchi-loader on the operation that lists " + rel.getTo()
                    + " in the OpenAPI document";
        }
        problems.add(where + ": " + problem);
    }

    /**
     * Checks the {@link RelationshipDefinition} beans against what the services expose, as
     * {@link #checkYaml} does for the YAML: the parent type and field, the companion name and the
     * loader of the target (a service's, a REST one or a {@link BatchLoaderDefinition} bean). A bad one
     * fails startup, naming the bean; a reload logs it. While a configured service has not loaded
     * yet a type that is missing may only be late, so that one is left for the scan that sees it.
     */
    private void checkBeans(Map<String, Map<String, RouteDefinition>> candidates) {
        List<RelationshipDefinition> definitions = beanRelationships;
        if (definitions.isEmpty()) return;
        Map<String, Descriptor> types = new HashMap<>();
        for (ServiceEndpointContext ctx : registry.contextsInStableOrder()) {
            for (var fd : ctx.getFileDescriptors().values()) collectMessages(fd.getMessageTypes(), types);
        }
        boolean allLoaded = true;
        if (properties != null && properties.getServices() != null) {
            Set<String> loaded = registry.contextsInStableOrder().stream()
                    .map(c -> c.getConfig().getName())
                    .collect(Collectors.toSet());
            for (ServiceDefinition svc : properties.getServices()) {
                if (svc.getName() != null && !loaded.contains(svc.getName())) allLoaded = false;
            }
        }
        Set<String> loaders = restLoaderNames();
        loaders.addAll(beanLoaderNames);
        List<String> problems = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RelationshipDefinition definition : definitions) {
            RelationshipConfig rel = definition.toConfig();
            String[] from = YamlRelationshipSource.split(rel.getFrom());
            if (!allLoaded && from != null && !types.containsKey(from[0])) continue;
            String problem = relationshipProblem(rel, types, candidates, loaders, seen);
            if (problem != null) problems.add("RelationshipDefinition bean (from '" + rel.getFrom() + "'): " + problem);
        }
        if (problems.isEmpty()) return;
        if (!started) throw new IllegalStateException(String.join("\n", problems));
        problems.forEach(log::error);
    }

    private static String relationshipProblem(
            RelationshipConfig rel,
            Map<String, Descriptor> types,
            Map<String, Map<String, RouteDefinition>> candidates,
            Set<String> restTypes,
            Set<String> seen) {
        String[] from = YamlRelationshipSource.split(rel.getFrom());
        if (from == null) return "from must be 'Type.proto_field'";
        Descriptor parent = types.get(from[0]);
        if (parent == null) return "type " + from[0] + " is not a message type of the service";
        FieldDescriptor field = findField(parent, from[1]);
        if (field == null) return "'" + from[1] + "' is not a field of " + parent.getFullName();
        if (rel.getTo() == null || rel.getTo().isBlank()) return "to is required";
        var declared = YamlRelationshipSource.toRelationship(rel, parent.getName(), field.getJsonName());
        if (!seen.add(declared.parentType() + "." + declared.fieldName())) {
            return "duplicate: " + declared.parentType() + "." + declared.fieldName()
                    + " is declared twice; give each relationship from the same field its own 'field' name";
        }
        for (FieldDescriptor existing : parent.getFields()) {
            if (existing.getJsonName().equals(declared.fieldName())) {
                return "companion field '" + declared.fieldName() + "' collides with a field of " + parent.getFullName()
                        + "; set 'field' to another name";
            }
        }
        if (rel.isMany()) {
            if (rel.getGroupBy() == null || rel.getGroupBy().isBlank()) return "many: true needs group-by";
            Descriptor child = types.get(rel.getTo());
            if (child != null && findField(child, rel.getGroupBy()) == null) {
                return "group-by '" + rel.getGroupBy() + "' is not a field of " + child.getFullName();
            }
            String loader = groupedName(rel.getTo(), rel.getGroupBy());
            if (!candidates.containsKey(loader) && !restTypes.contains(loader)) {
                return "no loader for " + rel.getTo() + " with group_by '" + rel.getGroupBy()
                        + "'; declare one in loaders[] or with loader_for/group_by in the proto,"
                        + " or in rest-batch-loaders[] with group-by for a REST_DOWNSTREAM service";
            }
        } else if (!candidates.containsKey(rel.getTo()) && !restTypes.contains(rel.getTo())) {
            return "no loader for type " + rel.getTo() + "; declare one in loaders[] or with loader_for in the proto";
        }
        return null;
    }

    private static String loaderProblem(GrpcLoaderConfig loader, ServiceEndpointContext ctx) {
        if (loader.getType() == null || loader.getType().isBlank()) return "type is required";
        String rpc = loader.getRpc();
        int slash = rpc == null ? -1 : rpc.indexOf('/');
        if (slash <= 0 || slash == rpc.length() - 1) return "rpc must be 'package.Service/Method'";
        if (ctx.lookupMethod(rpc.substring(0, slash), rpc.substring(slash + 1)).isEmpty()) {
            return "the service has no rpc " + rpc;
        }
        return null;
    }

    private static FieldDescriptor findField(Descriptor type, String name) {
        String camel = snakeToCamel(name);
        for (FieldDescriptor f : type.getFields()) {
            if (f.getName().equals(name) || f.getJsonName().equals(camel)) return f;
        }
        return null;
    }

    private static void collectMessages(List<Descriptor> messages, Map<String, Descriptor> into) {
        for (Descriptor d : messages) {
            into.putIfAbsent(d.getName(), d);
            collectMessages(d.getNestedTypes(), into);
        }
    }

    /** The endpoint whose loader serves {@code type}, or null when none (a rejected conflict). */
    private String chooseOwner(String type, Map<String, RouteDefinition> byEndpoint, Set<String> conflictsNow) {
        String pin = pinFor(type);
        if (pin != null && byEndpoint.containsKey(pin)) {
            byEndpoint.keySet().stream()
                    .filter(e -> !e.equals(pin))
                    .forEach(e -> log.info(
                            "Type {}: the loader of endpoint {} is not used, the pin selects endpoint {}",
                            type,
                            e,
                            pin));
            return pin;
        }
        if (pin != null) {
            // A pin whose endpoint has no loader: checkPins reports it, and the type has no owner
            return null;
        }
        if (byEndpoint.size() == 1) {
            return byEndpoint.keySet().iterator().next();
        }
        String message = "GraphQL type " + type + " has a loader_for from several endpoints "
                + byEndpoint.keySet() + "; set shiguchi.graphql.data-loader.types." + type
                + ".endpoint to the one that owns the loader";
        if (!started) {
            throw new IllegalStateException(message);
        }
        String existing = ownersByType.get(type);
        String key = type + "@" + byEndpoint.keySet();
        conflictsNow.add(key);
        if (reportedConflicts.add(key)) {
            log.error("{}; the new loader is rejected{}", message, existing == null ? "" : ", " + existing + " stays");
            if (meters != null) {
                Counter.builder("shiguchi.graphql.loader.conflicts")
                        .description("Loaders rejected because another endpoint already owns the type")
                        .tag("type", type)
                        .register(meters)
                        .increment();
            }
        }
        return existing != null && byEndpoint.containsKey(existing) ? existing : null;
    }

    /** A pin naming an endpoint without a loader for its type is a typo: startup fails, a reload logs it. */
    private void checkPins(Map<String, Map<String, RouteDefinition>> candidates) {
        for (Map.Entry<String, DataLoaderConfig.TypeConfig> entry :
                config.getTypes().entrySet()) {
            String type = entry.getKey();
            String pin = pinFor(type);
            if (pin == null) continue;
            Map<String, RouteDefinition> byEndpoint = candidates.get(type);
            if (byEndpoint != null && byEndpoint.containsKey(pin)) continue;
            String message = "shiguchi.graphql.data-loader.types." + type + ".endpoint is " + pin
                    + ", but that endpoint has no loader_for " + type;
            if (!started) throw new IllegalStateException(message);
            log.error(message);
        }
    }

    private String pinFor(String type) {
        DataLoaderConfig.TypeConfig typeConfig = config.getTypes().get(type);
        if (typeConfig == null || typeConfig.getEndpoint() == null) return null;
        String pin = typeConfig.getEndpoint().trim();
        return pin.isEmpty() ? null : pin;
    }

    /** The endpoint that owns the loader of {@code type}, or empty when the type has no loader. */
    synchronized Optional<String> ownerOf(String type) {
        return Optional.ofNullable(ownersByType.get(type));
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
                .orElse(RelationshipResolver.DEFAULT_BATCH_KEY);

        var loader = new GrpcBatchLoader(
                route, ctx, dispatcher, batchKeyJsonName, batchResultJsonName, entityKeyJsonName, timeout);

        log.debug(
                "Registered batch loader for type {} (route: {}, entity key: {})",
                route.getLoaderFor(),
                route.getGraphQlName(),
                entityKeyJsonName);
        return loader;
    }

    private GroupedOutcome.Loader createGroupedLoader(String name, RouteDefinition route) {
        var ctx = registry.contextFor(route);
        var methodOptional = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName());
        if (methodOptional.isEmpty()) {
            log.warn("Could not find method descriptor for batch route {}", route.getGraphQlName());
            return null;
        }
        String batchKeyJsonName = snakeToCamel(route.getLoaderIdsField());
        String batchResultJsonName = snakeToCamel(route.getLoaderResultField());
        // The by-id key is unused here: the items are grouped by the group_by field instead
        var source = new GrpcBatchLoader(route, ctx, dispatcher, batchKeyJsonName, batchResultJsonName, "id", timeout);
        log.debug("Registered one-to-many batch loader {} (route: {})", name, route.getGraphQlName());
        return new GrpcGroupedBatchLoader(
                source, snakeToCamel(route.getLoaderGroupBy()), config.getMaxChildrenPerKey());
    }

    /**
     * The entity a loader returns: the repeated message field {@code resultField} of a unary
     * response, or the response itself for a server stream.
     */
    private static Optional<Descriptor> entityDescriptor(MethodDescriptor method, String resultField) {
        Descriptor output = method.getOutputType();
        if (resultField != null && !resultField.isBlank()) {
            String camel = snakeToCamel(resultField);
            for (FieldDescriptor field : output.getFields()) {
                if (field.getName().equals(resultField) || field.getJsonName().equals(camel)) {
                    if (field.isRepeated() && field.getMessageType() != null) {
                        return Optional.of(field.getMessageType());
                    }
                    break;
                }
            }
        }
        return Optional.of(output);
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
        return relationshipResolver.batchKey(entityType).map(key -> snakeToCamel(key.protoField()));
    }

    /**
     * Converts snake_case strings to camelCase.
     *
     * @param snake the snake_case string
     * @return the camelCase equivalent
     */
    static String snakeToCamel(String snake) {
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

    /** The one-to-many loaders keyed by name ({@code Type#by:groupField}), apart from {@link #getLoaders()}. */
    Map<String, GroupedOutcome.Loader> getGroupedLoaders() {
        return Collections.unmodifiableMap(groupedLoaders);
    }
}
