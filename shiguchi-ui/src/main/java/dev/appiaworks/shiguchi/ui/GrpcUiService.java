package dev.appiaworks.shiguchi.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.OpenApiConfig;
import dev.appiaworks.shiguchi.gateway.GatewayErrorDetails;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The embedded gRPC UI's logic, independent of the web stack: link and schema lookups, unary and
 * streaming invocations, and the OAuth2 client-credentials exchange.
 *
 * <p>The WebFlux adapter ({@link GrpcUiController}) and the servlet adapter
 * ({@code dev.appiaworks.shiguchi.ui.servlet}) own everything HTTP: they read the request, build the
 * call's {@link Metadata} (principal, Authorization, forwarded headers) their own way, and render
 * the results. Nothing here touches a request, a response or a security context, so this class
 * loads on a classpath with only one of the two web stacks. The token exchange uses the JDK's
 * asynchronous {@link HttpClient}, which does not block on either stack.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class GrpcUiService {

    private static final Logger log = LoggerFactory.getLogger(GrpcUiService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(30);

    /** One server-sent event of a streaming invocation: {@code data}, {@code error} or {@code done}. */
    public record StreamEvent(String event, String data) {}

    // Self-contained client, created on first use: not sourced from the Spring context so this class
    // works on either web stack and in applications without any HTTP client auto-configuration.
    private static final class HttpClientHolder {
        static final HttpClient INSTANCE =
                HttpClient.newBuilder().connectTimeout(TOKEN_TIMEOUT).build();
    }

    private final GrpcUiSchemaBuilder schemaBuilder;
    private final ReactiveGrpcDispatcher dispatcher;
    private final GrpcRouteRegistry registry;
    private final GatewayProperties gatewayProperties;
    private final String swaggerUiPath;
    private final String graphiqlPath;
    private final boolean pushEnabled;
    private final String pushPath;
    private final String pushAdminPath;

    public GrpcUiService(
            GrpcUiSchemaBuilder schemaBuilder,
            ReactiveGrpcDispatcher dispatcher,
            GrpcRouteRegistry registry,
            GatewayProperties gatewayProperties,
            @Value("${springdoc.swagger-ui.path:/swagger-ui.html}") String swaggerUiPath,
            @Value("${dgs.graphql.graphiql.path:/graphiql}") String graphiqlPath,
            @Value("${shiguchi.cursus.enabled:false}") boolean pushEnabled,
            @Value("${shiguchi.cursus.path:/push}") String pushPath,
            @Value("${management.endpoints.web.base-path:/actuator}") String actuatorBasePath) {
        this.schemaBuilder = schemaBuilder;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.gatewayProperties = gatewayProperties;
        this.swaggerUiPath = swaggerUiPath;
        this.graphiqlPath = graphiqlPath;
        this.pushEnabled = pushEnabled;
        this.pushPath = pushPath;
        this.pushAdminPath = actuatorBasePath + "/gateway-push";
    }

    /** The enabled UI links and auth providers. */
    public GrpcUiSchemaDto.GatewayUiLinks config() {
        List<GrpcUiSchemaDto.AuthProviderDto> authProviders =
                gatewayProperties.getOpenapi().getSecuritySchemes().entrySet().stream()
                        .map(e -> toAuthProviderDto(e.getKey(), e.getValue()))
                        .toList();
        return new GrpcUiSchemaDto.GatewayUiLinks(
                gatewayProperties.getOpenapi().isEnabled(),
                swaggerUiPath,
                gatewayProperties.getGraphql().isEnabled(),
                graphiqlPath,
                gatewayProperties.getGrpc().isEnabled(),
                gatewayProperties.getGrpc().getPort(),
                authProviders,
                pushEnabled,
                pushPath,
                pushAdminPath);
    }

    /** All services, methods and input schemas. */
    public List<GrpcUiSchemaDto.ServiceSchema> schema() {
        return schemaBuilder.getSchema();
    }

    /** Whether a service of that fully qualified name is registered (with or without a gRPC channel). */
    public boolean isRegistered(String serviceName) {
        return registry.contextByServiceFqn(serviceName).isPresent();
    }

    /**
     * A unary or client-streaming call. Always emits exactly one {@link GrpcUiSchemaDto.InvokeResponse}
     * and never an error: a failed call is reported in the response's gRPC status.
     *
     * @param baseMetadata the call's metadata as the adapter builds it; subscribed only once the
     *     service is known to be reachable. Custom entries of the request are merged on top.
     */
    public Mono<GrpcUiSchemaDto.InvokeResponse> invoke(
            GrpcUiSchemaDto.InvokeRequest invokeReq, Mono<Metadata> baseMetadata) {
        long start = System.currentTimeMillis();
        var ctx = registry.contextByServiceFqn(invokeReq.serviceName()).orElse(null);
        if (ctx == null || ctx.getChannel() == null) {
            // A REST_DOWNSTREAM service has synthetic descriptors but no gRPC channel.
            return Mono.just(new GrpcUiSchemaDto.InvokeResponse(
                    null,
                    "NOT_FOUND",
                    "Service not registered as a gRPC backend: " + invokeReq.serviceName(),
                    System.currentTimeMillis() - start));
        }
        // A client-streaming method takes every message the console collected; everything
        // else takes the single payload, so an older console keeps working unchanged.
        boolean clientStreaming = isClientStreaming(ctx, invokeReq);
        return baseMetadata
                .flatMap(metadata -> {
                    mergeCustomMetadata(metadata, invokeReq.metadata());
                    return clientStreaming
                            ? dispatcher.dispatchClientStreamingDirect(
                                    ctx,
                                    invokeReq.serviceName(),
                                    invokeReq.methodName(),
                                    invokeReq.messages(),
                                    metadata)
                            : dispatcher.dispatchDirect(
                                    ctx,
                                    invokeReq.serviceName(),
                                    invokeReq.methodName(),
                                    invokeReq.payload(),
                                    metadata);
                })
                .map(response ->
                        new GrpcUiSchemaDto.InvokeResponse(response, "OK", null, System.currentTimeMillis() - start))
                .onErrorResume(StatusRuntimeException.class, ex -> {
                    String code = ex.getStatus().getCode().name();
                    String msg = ex.getStatus().getDescription();
                    log.debug("UI invoke {}/{} -> {} {}", invokeReq.serviceName(), invokeReq.methodName(), code, msg);
                    GatewayErrorDetails details = GatewayErrorDetails.from(ex, ctx.getTypeRegistry());
                    return Mono.just(new GrpcUiSchemaDto.InvokeResponse(
                            null,
                            code,
                            msg,
                            details.isEmpty() ? null : details.asJson(),
                            System.currentTimeMillis() - start));
                })
                .onErrorResume(Exception.class, ex -> {
                    log.warn(
                            "UI invoke {}/{} -> error: {}",
                            invokeReq.serviceName(),
                            invokeReq.methodName(),
                            ex.getMessage());
                    return Mono.just(new GrpcUiSchemaDto.InvokeResponse(
                            null, "INTERNAL", ex.getMessage(), System.currentTimeMillis() - start));
                });
    }

    /**
     * A server-streaming call as UI events: one {@code data} event per chunk, an {@code error} event
     * if the call fails (code, message, details), and a final {@code done} event carrying {@code
     * durationMs}. The flux itself does not fail once the metadata is built. The service must be
     * registered ({@link #isRegistered}).
     */
    public Flux<StreamEvent> stream(GrpcUiSchemaDto.InvokeRequest invokeReq, Mono<Metadata> baseMetadata) {
        long start = System.currentTimeMillis();
        var ctx = registry.contextByServiceFqn(invokeReq.serviceName()).orElse(null);
        if (ctx == null) {
            return Flux.error(new IllegalArgumentException("Service not registered: " + invokeReq.serviceName()));
        }
        return baseMetadata
                .flatMapMany(metadata -> {
                    mergeCustomMetadata(metadata, invokeReq.metadata());
                    return dispatcher.dispatchStreamingDirect(
                            ctx, invokeReq.serviceName(), invokeReq.methodName(), invokeReq.payload(), metadata);
                })
                .map(chunk -> new StreamEvent("data", chunk))
                .onErrorResume(StatusRuntimeException.class, ex -> {
                    String code = ex.getStatus().getCode().name();
                    String msg = ex.getStatus().getDescription();
                    log.debug("UI stream {}/{} -> {} {}", invokeReq.serviceName(), invokeReq.methodName(), code, msg);
                    return Flux.just(new StreamEvent(
                            "error", errorEvent(code, msg, GatewayErrorDetails.from(ex, ctx.getTypeRegistry()))));
                })
                .onErrorResume(Exception.class, ex -> {
                    log.warn(
                            "UI stream {}/{} -> error: {}",
                            invokeReq.serviceName(),
                            invokeReq.methodName(),
                            ex.getMessage());
                    return Flux.just(new StreamEvent(
                            "error", "{\"code\":\"INTERNAL\",\"message\":" + jsonStr(ex.getMessage()) + "}"));
                })
                .concatWith(Flux.defer(() -> Flux.just(
                        new StreamEvent("done", "{\"durationMs\":" + (System.currentTimeMillis() - start) + "}"))));
    }

    /** Whether the scheme exists and configures an OAuth2 client-credentials flow. */
    public boolean hasClientCredentials(String schemeId) {
        var scheme = gatewayProperties.getOpenapi().getSecuritySchemes().get(schemeId);
        return scheme != null && scheme.getClientCredentials() != null;
    }

    /**
     * Server-side OAuth2 {@code client_credentials} exchange: posts to the configured provider's
     * token URL so the client secret never leaves the server boundary. Fails the mono with a
     * message-carrying exception when the provider rejects the request or is unreachable, or with
     * an {@link IllegalArgumentException} when {@link #hasClientCredentials} is false.
     */
    public Mono<GrpcUiSchemaDto.TokenResponse> clientCredentials(
            String schemeId, GrpcUiSchemaDto.ClientCredentialsRequest body) {
        if (!hasClientCredentials(schemeId)) {
            return Mono.error(new IllegalArgumentException("No client_credentials flow for scheme " + schemeId));
        }
        String tokenUrl = gatewayProperties
                .getOpenapi()
                .getSecuritySchemes()
                .get(schemeId)
                .getClientCredentials()
                .getTokenUrl();

        List<String> form = new ArrayList<>();
        form.add(formPair("grant_type", "client_credentials"));
        form.add(formPair("client_id", body.clientId()));
        form.add(formPair("client_secret", body.clientSecret()));
        if (body.scopes() != null && !body.scopes().isEmpty()) {
            form.add(formPair("scope", String.join(" ", body.scopes())));
        }
        return Mono.defer(() -> {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                            .timeout(TOKEN_TIMEOUT)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(String.join("&", form)))
                            .build();
                    return Mono.fromFuture(
                            HttpClientHolder.INSTANCE.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
                })
                .map(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        throw new IllegalStateException(
                                "Token endpoint returned " + resp.statusCode() + ": " + resp.body());
                    }
                    try {
                        Map<String, Object> r = JSON.readValue(resp.body(), MAP_TYPE);
                        return new GrpcUiSchemaDto.TokenResponse(
                                (String) r.get("access_token"),
                                r.containsKey("expires_in") ? ((Number) r.get("expires_in")).longValue() : 3600L,
                                (String) r.getOrDefault("token_type", "Bearer"));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Token endpoint returned an unreadable body", e);
                    }
                })
                .doOnError(ex ->
                        log.warn("Client credentials exchange failed for scheme {}: {}", schemeId, ex.getMessage()));
    }

    // -------------------------------------------------------------------------

    private static String formPair(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Whether the named method takes a stream of request messages. */
    private static boolean isClientStreaming(ServiceEndpointContext ctx, GrpcUiSchemaDto.InvokeRequest request) {
        return ctx.lookupMethod(request.serviceName(), request.methodName())
                .map(com.google.protobuf.Descriptors.MethodDescriptor::isClientStreaming)
                .orElse(false);
    }

    /** The SSE {@code error} event payload: {@code {"code","message","details"?}}. */
    private static String errorEvent(String code, String message, GatewayErrorDetails details) {
        ObjectNode node = JSON.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        if (!details.isEmpty()) node.set("details", JSON.valueToTree(details.asJson()));
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{\"code\":\"" + code + "\",\"message\":" + jsonStr(message) + "}";
        }
    }

    /** Adds the console's custom metadata entries (keys lowercased and trimmed) on top of the call's own. */
    static void mergeCustomMetadata(Metadata metadata, List<GrpcUiSchemaDto.MetadataEntry> entries) {
        if (entries == null) return;
        for (var e : entries) {
            if (e.key() != null && !e.key().isBlank()) {
                metadata.put(
                        Metadata.Key.of(e.key().toLowerCase().trim(), Metadata.ASCII_STRING_MARSHALLER),
                        e.value() != null ? e.value() : "");
            }
        }
    }

    private static GrpcUiSchemaDto.AuthProviderDto toAuthProviderDto(
            String id, OpenApiConfig.SecuritySchemeConfig cfg) {
        GrpcUiSchemaDto.OAuthFlowDto authCode = null;
        GrpcUiSchemaDto.OAuthFlowDto clientCreds = null;
        if (cfg.getAuthorizationCode() != null) {
            var f = cfg.getAuthorizationCode();
            authCode = new GrpcUiSchemaDto.OAuthFlowDto(f.getAuthorizationUrl(), f.getTokenUrl(), f.getScopes());
        }
        if (cfg.getClientCredentials() != null) {
            var f = cfg.getClientCredentials();
            clientCreds = new GrpcUiSchemaDto.OAuthFlowDto(null, f.getTokenUrl(), f.getScopes());
        }
        return new GrpcUiSchemaDto.AuthProviderDto(
                id,
                cfg.getType(),
                cfg.getScheme(),
                cfg.getDescription(),
                cfg.getIn(),
                cfg.getName(),
                authCode,
                clientCreds);
    }
}
