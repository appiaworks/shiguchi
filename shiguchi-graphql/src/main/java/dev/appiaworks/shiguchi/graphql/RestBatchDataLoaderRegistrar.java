package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.serde.JsonFactories;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scans REST_DOWNSTREAM service definitions at startup and registers an
 * {@link OpenApiBatchLoader} for each declared {@link RestBatchLoaderConfig} entry.
 *
 * <p>It only builds the loaders. {@link BatchLoaderPipeline} runs them, for both the GraphQL
 * field DataLoaders ({@link GrpcDataLoaderRegistryFactory} registers them with Spring GraphQL) and
 * Apollo Federation {@code _entities} resolution.
 *
 * <p>The service base URL is derived from {@code host:port} on the {@link ServiceDefinition}.
 * If {@code openapiUrl} is set, the host/port are inferred from it — otherwise the
 * {@code host} + {@code port} fields are used directly.
 */
@DependsOn("restDownstreamRouteLoader")
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class RestBatchDataLoaderRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RestBatchDataLoaderRegistrar.class);

    private final GatewayProperties properties;
    // Jackson 3 sorts properties alphabetically and rejects trailing tokens by default; Jackson 2 did
    // neither, so both stay off. The factory carries Jackson 2's read limits and number handling.
    private final ObjectMapper objectMapper = JsonMapper.builder(JsonFactories.create())
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** What a scan built, kept so an unchanged entry keeps its loader. */
    private record Built<L>(String baseUrl, RestBatchLoaderConfig config, int maxChildren, L loader) {}

    private volatile Map<String, Built<OpenApiBatchLoader>> byId = Map.of();
    private volatile Map<String, Built<GroupedBatchLoader>> grouped = Map.of();
    // Published snapshots: the pipeline reads these on every load, so they are built once per scan
    private volatile Map<String, BatchLoader> loaderView = Map.of();
    private volatile Map<String, RestBatchLoaderConfig> configView = Map.of();
    private volatile Map<String, GroupedOutcome.Loader> groupedView = Map.of();
    private volatile Map<String, RestBatchLoaderConfig> groupedConfigView = Map.of();

    private final OpenApiRelationshipIndex extensions;

    public RestBatchDataLoaderRegistrar(GatewayProperties properties) {
        this(properties, null);
    }

    @Autowired
    RestBatchDataLoaderRegistrar(GatewayProperties properties, ObjectProvider<OpenApiRelationshipIndex> extensions) {
        this.properties = properties;
        this.extensions = extensions == null ? null : extensions.getIfAvailable();
    }

    /**
     * Builds the loaders the services declare. Runs at startup and again on every schema reload (see
     * {@link GrpcDataLoaderRegistryFactory#onSchemaReloaded}), so a service whose document loaded late
     * or whose config changed is picked up: an entry whose config and base URL are unchanged keeps its
     * loader, a new one gets one, one no longer declared is dropped. Safe to call any number of times.
     *
     * <p>An entry with {@code group-by} is a one-to-many loader, kept apart from the by-id ones: it is
     * not an entity loader, so {@code _entities} never sees it, and it may share a type with one.
     */
    @PostConstruct
    synchronized void scan() {
        int maxChildren = properties.getGraphql().getDataLoader().getMaxChildrenPerKey();
        Map<String, Built<OpenApiBatchLoader>> nextById = new LinkedHashMap<>();
        Map<String, Built<GroupedBatchLoader>> nextGrouped = new LinkedHashMap<>();
        for (ServiceDefinition svcDef : List.copyOf(properties.getServices())) {
            if (!svcDef.isRestDownstream()) continue;
            List<RestBatchLoaderConfig> declared = declaredLoaders(svcDef);
            if (declared.isEmpty()) continue;

            String baseUrl = resolveBaseUrl(svcDef);
            WebClient webClient = null;

            for (RestBatchLoaderConfig cfg : declared) {
                if (cfg.getType() == null || cfg.getType().isBlank()) {
                    log.warn(
                            "[RestBatchLoaderRegistrar] Skipping loader with blank type in service '{}'",
                            svcDef.getName());
                    continue;
                }
                boolean many = cfg.getGroupBy() != null && !cfg.getGroupBy().isBlank();
                String name = many
                        ? GrpcBatchDataLoaderRegistrar.groupedName(cfg.getType(), cfg.getGroupBy())
                        : cfg.getType();
                Built<?> previous = many ? grouped.get(name) : byId.get(name);
                int cap = many ? maxChildren : 0;
                if (previous != null
                        && previous.baseUrl().equals(baseUrl)
                        && previous.maxChildren() == cap
                        && previous.config().equals(cfg)) {
                    keep(many, name, previous, nextById, nextGrouped);
                    continue;
                }
                if (webClient == null)
                    webClient = WebClient.builder().baseUrl(baseUrl).build();
                // The config is copied so a later edit of the properties is seen as a change
                RestBatchLoaderConfig snapshot = copy(cfg);
                OpenApiBatchLoader flat = new OpenApiBatchLoader(webClient, snapshot, objectMapper);
                if (many) {
                    String groupBy = cfg.getGroupBy().trim();
                    nextGrouped.put(
                            name, new Built<>(baseUrl, snapshot, cap, new GroupedBatchLoader(flat, groupBy, cap)));
                } else {
                    nextById.put(name, new Built<>(baseUrl, snapshot, 0, flat));
                }
                log.debug(
                        "[RestBatchLoaderRegistrar] Registered REST {} loader: name={} baseUrl={} endpoint={}",
                        many ? "one-to-many" : "batch",
                        name,
                        baseUrl,
                        cfg.getEndpoint());
            }
        }

        boolean changed = !nextById.keySet().equals(byId.keySet())
                || !nextGrouped.keySet().equals(grouped.keySet());
        byId = nextById;
        grouped = nextGrouped;
        loaderView = view(nextById, Built::loader);
        configView = view(nextById, Built::config);
        groupedView = view(nextGrouped, Built::loader);
        groupedConfigView = view(nextGrouped, Built::config);
        if (changed && !(nextById.isEmpty() && nextGrouped.isEmpty())) {
            log.info(
                    "[RestBatchLoaderRegistrar] Registered {} REST batch loaders: {}",
                    nextById.size() + nextGrouped.size(),
                    java.util.stream.Stream.concat(nextById.keySet().stream(), nextGrouped.keySet().stream())
                            .toList());
        }
    }

    /**
     * The loaders of a service: its {@code rest-batch-loaders[]} entries, then those its OpenAPI
     * document declares with {@code x-shiguchi-loader}. An explicit entry wins over an extension one
     * with the same name.
     */
    private List<RestBatchLoaderConfig> declaredLoaders(ServiceDefinition svcDef) {
        List<RestBatchLoaderConfig> yaml = svcDef.getRestBatchLoaders();
        OpenApiRelationshipIndex ext = extensions;
        if (ext == null) return yaml;
        List<RestBatchLoaderConfig> fromDocument = ext.loaders(svcDef.getName());
        if (fromDocument.isEmpty()) return yaml;
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (RestBatchLoaderConfig c : yaml) taken.add(nameOf(c));
        List<RestBatchLoaderConfig> all = new java.util.ArrayList<>(yaml);
        for (RestBatchLoaderConfig c : fromDocument) {
            if (taken.add(nameOf(c))) all.add(c);
        }
        return all;
    }

    private static String nameOf(RestBatchLoaderConfig c) {
        boolean many = c.getGroupBy() != null && !c.getGroupBy().isBlank();
        return many && c.getType() != null
                ? GrpcBatchDataLoaderRegistrar.groupedName(c.getType(), c.getGroupBy())
                : c.getType();
    }

    @SuppressWarnings("unchecked")
    private static void keep(
            boolean many,
            String name,
            Built<?> previous,
            Map<String, Built<OpenApiBatchLoader>> nextById,
            Map<String, Built<GroupedBatchLoader>> nextGrouped) {
        if (many) nextGrouped.put(name, (Built<GroupedBatchLoader>) previous);
        else nextById.put(name, (Built<OpenApiBatchLoader>) previous);
    }

    private static RestBatchLoaderConfig copy(RestBatchLoaderConfig c) {
        RestBatchLoaderConfig out = new RestBatchLoaderConfig();
        out.setType(c.getType());
        out.setEndpoint(c.getEndpoint());
        out.setIdsParam(c.getIdsParam());
        out.setKeyField(c.getKeyField());
        out.setResultField(c.getResultField());
        out.setBatchEndpoint(c.isBatchEndpoint());
        out.setTimeoutSeconds(c.getTimeoutSeconds());
        out.setMaxBatchSize(c.getMaxBatchSize());
        out.setMaxConcurrency(c.getMaxConcurrency());
        out.setGroupBy(c.getGroupBy());
        return out;
    }

    /** The by-id loaders by type: the entity loaders {@code _entities} and the relationships use. */
    public Map<String, BatchLoader> getLoaders() {
        return loaderView;
    }

    /** The declaring {@link RestBatchLoaderConfig} per type. */
    Map<String, RestBatchLoaderConfig> getConfigs() {
        return configView;
    }

    /** The one-to-many loaders by name ({@code Type#by:groupField}), apart from {@link #getLoaders()}. */
    Map<String, GroupedOutcome.Loader> getGroupedLoaders() {
        return groupedView;
    }

    /** The declaring config per one-to-many loader name. */
    Map<String, RestBatchLoaderConfig> getGroupedConfigs() {
        return groupedConfigView;
    }

    private static <L, V> Map<String, V> view(Map<String, Built<L>> built, Function<Built<L>, V> value) {
        Map<String, V> out = new LinkedHashMap<>();
        built.forEach((name, b) -> out.put(name, value.apply(b)));
        return Collections.unmodifiableMap(out);
    }

    /** Same base URL the proxy routes use: {@code base-url}, else the authority of {@code openapi-url}, else host:port. */
    private static String resolveBaseUrl(ServiceDefinition svcDef) {
        return svcDef.effectiveBaseUrl();
    }
}
