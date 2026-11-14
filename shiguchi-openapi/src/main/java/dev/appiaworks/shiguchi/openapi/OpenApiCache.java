package dev.appiaworks.shiguchi.openapi;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Caches the serialized OpenAPI spec as JSON and YAML strings so that repeated
 * requests to /v3/api-docs don't re-serialize the entire object graph each time.
 *
 * invalidate() is called by GatewayRefreshScheduler after proto changes; the next
 * call to json()/yaml() triggers a lazy rebuild.
 */
@Component
public class OpenApiCache implements dev.appiaworks.shiguchi.routing.SchemaReloadListener {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCache.class);

    private final GatewayOpenApiBuilder builder;
    private volatile String cachedJson;
    private volatile String cachedYaml;

    public OpenApiCache(GatewayOpenApiBuilder builder) {
        this.builder = builder;
    }

    @PostConstruct
    public void init() {
        populate(builder.getSpec());
    }

    public String json() {
        if (cachedJson == null) {
            populate(builder.getSpec());
        }
        return cachedJson;
    }

    public String yaml() {
        if (cachedYaml == null) {
            populate(builder.getSpec());
        }
        return cachedYaml;
    }

    /** Rebuilds the spec from the registry and drops the serialized copies. */
    @Override
    public void onSchemaReloaded() {
        builder.rebuild();
        invalidate();
    }

    /** Called after a downstream proto refresh to force re-serialization. */
    public void invalidate() {
        cachedJson = null;
        cachedYaml = null;
        log.info("OpenAPI cache invalidated; will rebuild on next request");
    }

    private void populate(OpenAPI spec) {
        try {
            cachedJson = toJson(spec);
            cachedYaml = toYaml(spec);
        } catch (Exception e) {
            log.error("Failed to serialize OpenAPI spec", e);
            cachedJson = "{}";
            cachedYaml = "{}";
        }
    }

    /**
     * Serializes with the 3.1 mapper, which writes each schema by its own spec version: a schema bound from a
     * 3.1 downstream document keeps its {@code types}, one built for 3.0 keeps {@code type}. The 3.0 mapper
     * drops {@code types}, which left every 3.1 downstream schema without a type.
     */
    static String toJson(OpenAPI spec) throws com.fasterxml.jackson.core.JsonProcessingException {
        return Json31.pretty(spec);
    }

    static String toYaml(OpenAPI spec) throws com.fasterxml.jackson.core.JsonProcessingException {
        return Yaml31.pretty(spec);
    }
}
