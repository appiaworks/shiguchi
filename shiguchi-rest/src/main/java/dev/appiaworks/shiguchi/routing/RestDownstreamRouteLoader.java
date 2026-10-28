package dev.appiaworks.shiguchi.routing;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import dev.appiaworks.shiguchi.rest.UpstreamBodyCodec;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import io.grpc.MethodDescriptor;
import jakarta.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Populates {@link GrpcRouteRegistry} at startup for every {@code REST_DOWNSTREAM} service.
 *
 * <p>For each service:
 * <ol>
 *   <li>GET the OpenAPI document from {@code openapi-url}.</li>
 *   <li>Hash the raw bytes (change detection on refresh) and keep them on the context so the
 *       openapi module can merge the document into the consolidated spec.</li>
 *   <li>Parse the operations and register one HTTP passthrough {@link RouteDefinition} per
 *       operation that passes {@code include-methods} / {@code exclude-methods}, under the
 *       service's {@code path-prefix}.</li>
 *   <li>Register the context; {@link ScgRouteRegistrar} turns each route into a Spring Cloud
 *       Gateway route with the backend as its URI.</li>
 * </ol>
 *
 * <p>The resulting context has no channel and no descriptors. Nothing downstream of the registry
 * touches either for a passthrough route: the REST filter lets the request fall through to SCG's
 * own proxying, and the OpenAPI builder reads the raw document instead of descriptors.
 *
 * <p>A backend that is down at startup is retried once on {@link ApplicationReadyEvent}, matching
 * {@link DynamicRouteLoader}; after that the refresh scheduler picks it up.
 */
@Component
public class RestDownstreamRouteLoader implements ServiceLoader {

    private static final Logger log = LoggerFactory.getLogger(RestDownstreamRouteLoader.class);

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final Optional<ScgRouteRegistrar> scgRegistrar;
    private final WebClient.Builder webClientBuilder;

    /**
     * Resolved lazily, at retry time. Injecting the listeners eagerly forms a cycle: the OpenAPI
     * cache listener needs the OpenAPI builder, which {@code @DependsOn} this loader.
     */
    private final java.util.function.Supplier<List<SchemaReloadListener>> reloadListeners;

    /** Tier 2 serializer precompilation for the synthetic messages; optional, Tier 3 works without. */
    private volatile DynamicSerializerFactory tier2;

    /** Non-JSON body codecs (Avro, ...) that learn each backend's schemas at load time. */
    private volatile List<UpstreamBodyCodec> codecs = List.of();

    private final List<ServiceDefinition> pendingRetry = new ArrayList<>();

    /** Where load outcomes are recorded; a private instance until Spring injects the shared one. */
    private volatile ServiceLoadStatus loadStatus = new ServiceLoadStatus();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLoadStatus(ServiceLoadStatus loadStatus) {
        if (loadStatus != null) this.loadStatus = loadStatus;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RestDownstreamRouteLoader(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            Optional<WebClient.Builder> webClientBuilder,
            org.springframework.beans.factory.ObjectProvider<SchemaReloadListener> reloadListeners,
            org.springframework.beans.factory.ObjectProvider<DynamicSerializerFactory> tier2,
            org.springframework.beans.factory.ObjectProvider<UpstreamBodyCodec> codecs) {
        this(
                properties,
                registry,
                scgRegistrar,
                webClientBuilder,
                () -> reloadListeners.orderedStream().toList());
        this.tier2 = tier2.getIfAvailable();
        this.codecs = codecs.orderedStream().toList();
    }

    /** Body codecs consulted at load time (tests / manual wiring); the Spring constructor collects the beans. */
    public void setCodecs(List<UpstreamBodyCodec> codecs) {
        this.codecs = codecs != null ? List.copyOf(codecs) : List.of();
    }

    /** Enables Tier 2 serializer precompilation for the synthetic messages (tests / manual wiring). */
    public void setTier2(DynamicSerializerFactory tier2) {
        this.tier2 = tier2;
    }

    /** Listeners supplied on demand; tests pass a fixed list, Spring an {@code ObjectProvider}. */
    public RestDownstreamRouteLoader(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            Optional<WebClient.Builder> webClientBuilder,
            java.util.function.Supplier<List<SchemaReloadListener>> reloadListeners) {
        this.properties = properties;
        this.registry = registry;
        this.scgRegistrar = scgRegistrar;
        this.webClientBuilder = webClientBuilder.orElseGet(WebClient::builder);
        this.reloadListeners = reloadListeners != null ? reloadListeners : List::of;
    }

    /** Convenience for tests and manual wiring: no reload listeners. */
    public RestDownstreamRouteLoader(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            Optional<WebClient.Builder> webClientBuilder) {
        this(properties, registry, scgRegistrar, webClientBuilder, (java.util.function.Supplier<
                        List<SchemaReloadListener>>)
                List::of);
    }

    @PostConstruct
    public void load() {
        for (ServiceDefinition svcDef : properties.getServices()) {
            if (!svcDef.isRestDownstream()) continue;
            try {
                ServiceEndpointContext ctx = buildContext(svcDef);
                registry.registerContext(ctx);
                scgRegistrar.ifPresent(r -> r.register(ctx));
                loadStatus.recordLoaded(svcDef.getName());
            } catch (Exception e) {
                log.warn(
                        "Downstream OpenAPI load deferred for service '{}': {} — will retry after startup",
                        svcDef.getName(),
                        e.getMessage());
                loadStatus.recordFailure(svcDef.getName(), "startup", e);
                pendingRetry.add(svcDef);
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void retryFailedOnReady() {
        if (pendingRetry.isEmpty()) return;
        log.info("Retrying downstream OpenAPI load for {} deferred service(s)", pendingRetry.size());
        boolean registeredAny = false;
        for (ServiceDefinition svcDef : pendingRetry) {
            try {
                ServiceEndpointContext ctx = buildContext(svcDef);
                registry.registerContext(ctx);
                scgRegistrar.ifPresent(r -> r.register(ctx));
                registeredAny = true;
                loadStatus.recordLoaded(svcDef.getName());
                log.info("Deferred downstream load succeeded for service '{}'", svcDef.getName());
            } catch (Exception e) {
                log.error(
                        "Failed to load downstream OpenAPI for service '{}': {}", svcDef.getName(), e.getMessage(), e);
                loadStatus.recordFailure(svcDef.getName(), "ready-retry", e);
            }
        }
        pendingRetry.clear();
        // The generated documents were built at startup without these services; rebuild them.
        if (registeredAny) SchemaReloadListeners.notifyAll(reloadListeners.get(), log);
    }

    /**
     * Fetches and parses the service's OpenAPI document and builds a fully populated context.
     * Called at startup and by the refresh scheduler.
     *
     * @throws IllegalStateException when {@code openapi-url} is missing
     * @throws IllegalArgumentException when the document cannot be parsed
     */
    @Override
    public boolean supports(ServiceDefinition service) {
        return service.isRestDownstream();
    }

    @Override
    public ServiceEndpointContext buildContext(ServiceDefinition svcDef) throws Exception {
        String url = svcDef.getOpenapiUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "Service '" + svcDef.getName() + "' has mode REST_DOWNSTREAM but no openapi-url configured");
        }

        ResponseEntity<byte[]> response = webClientBuilder
                .build()
                .get()
                .uri(url)
                .header(HttpHeaders.ACCEPT, "application/json, application/yaml;q=0.9, */*;q=0.1")
                .retrieve()
                .toEntity(byte[].class)
                .block(svcDef.getOpenapiFetchTimeout());
        if (response == null || response.getBody() == null || response.getBody().length == 0) {
            throw new IllegalStateException("Empty OpenAPI document from " + url);
        }
        byte[] raw = response.getBody();
        String mediaType = DownstreamOpenApiDocument.detectMediaType(
                raw, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));

        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(raw, mediaType);

        // Body codecs (Avro, ...) learn the document's schemas now, so negotiation at request
        // time is a lookup. Re-registering on refresh replaces the earlier state.
        for (UpstreamBodyCodec codec : codecs) {
            try {
                Set<String> bound = codec.registerService(svcDef.getName(), svcDef, doc.componentSchemas());
                if (!bound.isEmpty()) {
                    log.info(
                            "{} bound {} schema(s) of REST_DOWNSTREAM service '{}': {}",
                            codec.mediaType(),
                            bound.size(),
                            svcDef.getName(),
                            bound);
                }
            } catch (Exception e) {
                log.warn(
                        "{} codec could not register schemas of service '{}': {}",
                        codec.mediaType(),
                        svcDef.getName(),
                        e.getMessage());
            }
        }

        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, null);
        ctx.setRawDownstreamSpec(raw, mediaType);
        ctx.setDescriptorHash(MessageDigest.getInstance("SHA-256").digest(raw));

        String baseUrl = svcDef.effectiveBaseUrl();
        String prefix = svcDef.getPathPrefix() != null ? svcDef.getPathPrefix().trim() : "";
        boolean restEnabled = svcDef.isRestEnabled(properties.getRest().isEnabled());
        boolean graphqlEnabled = svcDef.isGraphqlEnabled(properties.getGraphql().isEnabled());

        // The GraphQL surface works from descriptors, so the document is projected onto a synthetic
        // proto file: one message per schema, one unary method per operation. Skipped when GraphQL is
        // off for this service; the REST passthrough needs no descriptors at all.
        String serviceFqn = OpenApiToDescriptor.serviceFqnFor(svcDef.getName());
        Map<String, OpenApiToDescriptor.Method> methods = Map.of();
        if (graphqlEnabled) {
            OpenApiToDescriptor.Result synthetic =
                    OpenApiToDescriptor.build(svcDef.getName(), svcDef.getGraphqlTypePrefix(), doc);
            FileDescriptor file = synthetic.file();
            serviceFqn = synthetic.serviceFqn();
            methods = synthetic.methodsByOperationId();

            Map<String, com.google.protobuf.Descriptors.ServiceDescriptor> serviceByFqn = new HashMap<>();
            Map<String, com.google.protobuf.Descriptors.MethodDescriptor> methodByKey = new HashMap<>();
            for (var sd : file.getServices()) {
                serviceByFqn.put(sd.getFullName(), sd);
                for (var md : sd.getMethods()) methodByKey.put(sd.getFullName() + "/" + md.getName(), md);
            }
            ctx.setFileDescriptors(Map.of(file.getName(), file));
            ctx.setServiceByFqn(serviceByFqn);
            ctx.setMethodByKey(methodByKey);
            JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
            for (var msg : file.getMessageTypes()) types.add(msg);
            ctx.setTypeRegistry(types.build());
            for (var m : methods.values()) ctx.putHttpBinding(m.name(), m.binding());
            ctx.setEnumValueOriginals(synthetic.enumValueOriginals());
        }

        int hidden = 0;
        for (DownstreamOpenApiDocument.Operation op : doc.operations()) {
            if (!svcDef.isMethodExposed(svcDef.getName(), op.operationId())) {
                hidden++;
                continue;
            }
            OpenApiToDescriptor.Method method = methods.get(op.operationId());
            String methodName = method != null ? method.name() : op.operationId();
            var builder = RouteDefinition.builder(serviceFqn, methodName, MethodDescriptor.MethodType.UNARY, baseUrl)
                    .httpUpstream(baseUrl)
                    .authScopes(resolveScopes(svcDef, op))
                    .upstreamTimeout(svcDef.effectiveHttpTimeout(op.operationId(), properties.getClient()));
            if (restEnabled) {
                builder.httpBinding(op.httpMethod(), prefix + op.path());
            }
            if (method != null) {
                // GET reads become queries, everything else mutations; an array response is plucked
                // from the wrapper message so the field returns a list directly.
                builder.graphQl(
                        graphQlFieldName(methodName),
                        "GET".equals(op.httpMethod())
                                ? RouteDefinition.GraphQlOperationType.QUERY
                                : RouteDefinition.GraphQlOperationType.MUTATION);
                if (op.summary() != null) builder.description(op.summary());
                if (method.binding().responseIsArray()) builder.responsePluck(HttpOperationBinding.ARRAY_FIELD);
                else if (method.binding().responseIsScalar()) builder.responsePluck(HttpOperationBinding.SCALAR_FIELD);
            }
            ctx.addRoute(builder.build());
        }

        if (graphqlEnabled && tier2 != null) {
            try {
                tier2.precompile(ctx);
            } catch (Exception e) {
                log.debug(
                        "Tier 2 precompile skipped for REST_DOWNSTREAM service '{}': {}",
                        svcDef.getName(),
                        e.getMessage());
            }
        }

        Set<String> declared = new java.util.HashSet<>();
        for (DownstreamOpenApiDocument.Operation op : doc.operations()) declared.add(op.operationId());
        for (String key : svcDef.getOperationTimeouts().keySet()) {
            if (!declared.contains(key)) {
                log.warn(
                        "operation-timeouts of REST_DOWNSTREAM service '{}' names '{}', which matches no operationId in its OpenAPI document",
                        svcDef.getName(),
                        key);
            }
        }

        log.info(
                "Loaded {} passthrough routes for REST_DOWNSTREAM service '{}' → {} ({} hidden by filters{})",
                ctx.getRoutes().size(),
                svcDef.getName(),
                baseUrl,
                hidden,
                graphqlEnabled ? ", GraphQL schema derived from the document" : "");
        return ctx;
    }

    /** {@code getItem} stays; {@code GetItem} → {@code getItem}; {@code list_items} → {@code listItems}. */
    static String graphQlFieldName(String methodName) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : methodName.toCharArray()) {
            if (c == '_') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        if (sb.isEmpty()) return methodName;
        sb.setCharAt(0, Character.toLowerCase(sb.charAt(0)));
        return sb.toString();
    }

    /** YAML {@code operation-scopes} wins over the document's {@code x-gateway-auth-scopes}. */
    private static List<String> resolveScopes(ServiceDefinition svcDef, DownstreamOpenApiDocument.Operation op) {
        List<String> fromYaml = svcDef.getOperationScopes().get(op.operationId());
        if (fromYaml != null && !fromYaml.isEmpty()) return fromYaml;
        return op.gatewayAuthScopes();
    }
}
