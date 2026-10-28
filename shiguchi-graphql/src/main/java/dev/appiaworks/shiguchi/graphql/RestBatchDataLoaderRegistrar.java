package dev.appiaworks.shiguchi.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Scans REST_DOWNSTREAM service definitions at startup and registers an
 * {@link OpenApiBatchLoader} for each declared {@link RestBatchLoaderConfig} entry.
 *
 * <p>These loaders participate in:
 * <ul>
 *   <li>DGS {@link BatchLoaderRegistry} (so GraphQL field resolvers using DataLoader work).</li>
 *   <li>Apollo Federation {@code _entities} resolution via {@link GrpcBatchDataLoaderRegistrar},
 *       which merges REST loaders into its combined loader map.</li>
 * </ul>
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
    private final BatchLoaderRegistry batchLoaderRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, BatchLoader> loadersByType = Collections.synchronizedMap(new LinkedHashMap<>());

    private final GrpcMetadataPopulator metadataPopulator;

    /** As the constructor Spring uses, without a populator: only the inbound Authorization header is relayed. */
    public RestBatchDataLoaderRegistrar(GatewayProperties properties, BatchLoaderRegistry batchLoaderRegistry) {
        this(properties, batchLoaderRegistry, null);
    }

    @Autowired
    public RestBatchDataLoaderRegistrar(
            GatewayProperties properties,
            BatchLoaderRegistry batchLoaderRegistry,
            GrpcMetadataPopulator metadataPopulator) {
        this.properties = properties;
        this.batchLoaderRegistry = batchLoaderRegistry;
        this.metadataPopulator = metadataPopulator;
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

                batchLoaderRegistry
                        .<String, Map<String, Object>>forName(cfg.getType())
                        .registerMappedBatchLoader(
                                (keys, env) -> GraphQlMetadata.build(env.getContext(), metadataPopulator)
                                        .flatMap(metadata -> loader.loadMapped(new ArrayList<>(keys), metadata)));

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

    /** Same base URL the proxy routes use: {@code base-url}, else the authority of {@code openapi-url}, else host:port. */
    private static String resolveBaseUrl(ServiceDefinition svcDef) {
        return svcDef.effectiveBaseUrl();
    }
}
