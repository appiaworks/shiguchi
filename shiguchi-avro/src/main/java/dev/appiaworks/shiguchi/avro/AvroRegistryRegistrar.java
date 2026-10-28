package dev.appiaworks.shiguchi.avro;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Registers the REST surface's derived Avro schemas with the configured schema registry whenever
 * the routes are (re)loaded: every gRPC-backed route's request and response message. It runs as a
 * {@link SchemaReloadListener}, i.e. after the loaders have registered their contexts, so the
 * per-request codec only ever reads ids the load already fetched.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.avro", name = "schema-registry-url")
public class AvroRegistryRegistrar implements SchemaReloadListener {

    private static final Logger log = LoggerFactory.getLogger(AvroRegistryRegistrar.class);

    private final AvroRestBodyCodec codec;
    private final GrpcRouteRegistry registry;

    public AvroRegistryRegistrar(AvroRestBodyCodec codec, GrpcRouteRegistry registry) {
        this.codec = codec;
        this.registry = registry;
    }

    @Override
    public void onSchemaReloaded() {
        try {
            int registered = codec.registerSchemas(messageTypes().values());
            log.info("Registered {} Avro schema(s) for the REST surface with the schema registry", registered);
        } catch (RuntimeException e) {
            // Must not throw: a registry outage on reload keeps the previous ids, and callers asking
            // for Confluent framing of an unregistered schema get a clear error per request instead.
            log.error("Registering REST-surface Avro schemas with the schema registry failed: {}", e.toString());
        }
    }

    /** Request and response types of every transcoded (non-passthrough) route, by full name. */
    Map<String, Descriptor> messageTypes() {
        Map<String, Descriptor> types = new LinkedHashMap<>();
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            for (RouteDefinition route : ctx.getRoutes()) {
                if (route.isPassthrough()) continue;
                MethodDescriptor md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                        .orElse(null);
                if (md == null) continue;
                types.putIfAbsent(md.getInputType().getFullName(), md.getInputType());
                types.putIfAbsent(md.getOutputType().getFullName(), md.getOutputType());
            }
        }
        return types;
    }
}
