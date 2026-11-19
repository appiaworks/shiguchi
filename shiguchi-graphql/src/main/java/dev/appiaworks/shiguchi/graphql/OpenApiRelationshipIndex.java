package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.routing.DownstreamOpenApiDocument;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.OpenApiRelationshipExtensions;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code x-shiguchi-*} declarations of the REST_DOWNSTREAM documents loaded now. The gateway
 * keeps each document's raw bytes on its service context; this re-reads them (once per fetched
 * document, not per call) so the extensions follow a reload without the REST module caching them.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
class OpenApiRelationshipIndex {

    private static final Logger log = LoggerFactory.getLogger(OpenApiRelationshipIndex.class);

    /** One loaded REST_DOWNSTREAM service with its extensions. */
    record Entry(ServiceEndpointContext context, OpenApiRelationshipExtensions.Result extensions) {
        String serviceName() {
            return context.getConfig().getName();
        }
    }

    private record Cached(byte[] raw, String prefix, OpenApiRelationshipExtensions.Result result) {}

    private final GrpcRouteRegistry registry;
    private final Map<String, Cached> cache = new HashMap<>();

    OpenApiRelationshipIndex(GrpcRouteRegistry registry) {
        this.registry = registry;
    }

    /** The loaded REST_DOWNSTREAM services whose document declares at least one extension or problem. */
    synchronized List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        Map<String, Cached> live = new HashMap<>();
        for (ServiceEndpointContext ctx : registry.contextsInStableOrder()) {
            if (!ctx.isRestDownstream() || ctx.getRawDownstreamSpec() == null) continue;
            String name = ctx.getConfig().getName();
            String prefix = ctx.getConfig().getGraphqlTypePrefix();
            Cached cached = cache.get(name);
            if (cached == null
                    || cached.raw() != ctx.getRawDownstreamSpec()
                    || !cached.prefix().equals(prefix)) {
                cached = new Cached(ctx.getRawDownstreamSpec(), prefix, read(name, ctx, prefix));
            }
            live.put(name, cached);
            if (!cached.result().isEmpty() || !cached.result().problems().isEmpty()) {
                out.add(new Entry(ctx, cached.result()));
            }
        }
        cache.clear();
        cache.putAll(live);
        return out;
    }

    /** The loaders the extensions declare for {@code serviceName}, empty when it has none. */
    List<RestBatchLoaderConfig> loaders(String serviceName) {
        for (Entry entry : entries()) {
            if (serviceName != null && serviceName.equals(entry.serviceName())) {
                return entry.extensions().loaders().stream()
                        .map(OpenApiRelationshipExtensions.Loader::config)
                        .toList();
            }
        }
        return List.of();
    }

    private static OpenApiRelationshipExtensions.Result read(String name, ServiceEndpointContext ctx, String prefix) {
        try {
            DownstreamOpenApiDocument doc =
                    DownstreamOpenApiDocument.parse(ctx.getRawDownstreamSpec(), ctx.getDownstreamSpecMediaType());
            return OpenApiRelationshipExtensions.read(doc, prefix);
        } catch (RuntimeException e) {
            log.warn("[OpenApiRelationshipIndex] Cannot read the OpenAPI document of '{}': {}", name, e.getMessage());
            return OpenApiRelationshipExtensions.Result.EMPTY;
        }
    }
}
