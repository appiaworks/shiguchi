package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.serde.JsonFactories;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final Map<String, BatchLoader> loadersByType = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, RestBatchLoaderConfig> configsByType = Collections.synchronizedMap(new LinkedHashMap<>());

    public RestBatchDataLoaderRegistrar(GatewayProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void scan() {
        for (ServiceDefinition svcDef : properties.getServices()) {
            if (!svcDef.isRestDownstream()) continue;
            if (svcDef.getRestBatchLoaders().isEmpty()) continue;

            String baseUrl = resolveBaseUrl(svcDef);
            WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();

            for (RestBatchLoaderConfig cfg : svcDef.getRestBatchLoaders()) {
                if (cfg.getType() == null || cfg.getType().isBlank()) {
                    log.warn(
                            "[RestBatchLoaderRegistrar] Skipping loader with blank type in service '{}'",
                            svcDef.getName());
                    continue;
                }
                OpenApiBatchLoader loader = new OpenApiBatchLoader(webClient, cfg, objectMapper);
                loadersByType.put(cfg.getType(), loader);
                configsByType.put(cfg.getType(), cfg);

                log.debug(
                        "[RestBatchLoaderRegistrar] Registered REST batch loader: type={} baseUrl={} endpoint={}",
                        cfg.getType(),
                        baseUrl,
                        cfg.getEndpoint());
            }
        }

        if (!loadersByType.isEmpty()) {
            log.info(
                    "[RestBatchLoaderRegistrar] Registered {} REST batch loaders: {}",
                    loadersByType.size(),
                    loadersByType.keySet());
        }
    }

    public Map<String, BatchLoader> getLoaders() {
        return Collections.unmodifiableMap(loadersByType);
    }

    /** The declaring {@link RestBatchLoaderConfig} per type. */
    Map<String, RestBatchLoaderConfig> getConfigs() {
        return Collections.unmodifiableMap(configsByType);
    }

    /** Same base URL the proxy routes use: {@code base-url}, else the authority of {@code openapi-url}, else host:port. */
    private static String resolveBaseUrl(ServiceDefinition svcDef) {
        return svcDef.effectiveBaseUrl();
    }
}
