package dev.appiaworks.shiguchi.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.OpenApiConfig;
import dev.appiaworks.shiguchi.gateway.GatewayErrorDetails;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handler methods for the embedded gRPC UI API.
 * Routes are registered by GrpcUiConfig.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class GrpcUiController {

    private static final Logger log = LoggerFactory.getLogger(GrpcUiController.class);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ObjectMapper JSON = new ObjectMapper();

    // Self-contained WebClient — not sourced from the Spring context so that this class
    // works whether or not WebClientAutoConfiguration is active (e.g. non-web gRPC services).
    private static final WebClient WEB_CLIENT = WebClient.create();

    private final GrpcUiSchemaBuilder schemaBuilder;
    private final ReactiveGrpcDispatcher dispatcher;
    private final GrpcRouteRegistry registry;
    private final GrpcMetadataPopulator metadataPopulator;
    private final GatewayProperties gatewayProperties;
    private final String swaggerUiPath;
    private final String graphiqlPath;
    private final boolean pushEnabled;
    private final String pushPath;
    private final String pushAdminPath;

    public GrpcUiController(
            GrpcUiSchemaBuilder schemaBuilder,
            ReactiveGrpcDispatcher dispatcher,
            GrpcRouteRegistry registry,
            GrpcMetadataPopulator metadataPopulator,
            GatewayProperties gatewayProperties,
            @Value("${springdoc.swagger-ui.path:/swagger-ui.html}") String swaggerUiPath,
            @Value("${dgs.graphql.graphiql.path:/graphiql}") String graphiqlPath,
            @Value("${shiguchi.cursus.enabled:false}") boolean pushEnabled,
            @Value("${shiguchi.cursus.path:/push}") String pushPath,
            @Value("${management.endpoints.web.base-path:/actuator}") String actuatorBasePath) {
        this.schemaBuilder = schemaBuilder;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.metadataPopulator = metadataPopulator;
        this.gatewayProperties = gatewayProperties;
        this.swaggerUiPath = swaggerUiPath;
        this.graphiqlPath = graphiqlPath;
        this.pushEnabled = pushEnabled;
        this.pushPath = pushPath;
        this.pushAdminPath = actuatorBasePath + "/gateway-push";
    }

    /** GET /grpc-ui/api/config — returns enabled UI links and auth providers. */
    Mono<ServerResponse> handleGetConfig(ServerRequest req) {
        List<GrpcUiSchemaDto.AuthProviderDto> authProviders =
                gatewayProperties.getOpenapi().getSecuritySchemes().entrySet().stream()
                        .map(e -> toAuthProviderDto(e.getKey(), e.getValue()))
                        .toList();

        var links = new GrpcUiSchemaDto.GatewayUiLinks(
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
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(links);
    }

    /** GET /grpc-ui/api/schema — returns all services, methods and input schemas. */
    Mono<ServerResponse> handleGetSchema(ServerRequest req) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(schemaBuilder.getSchema());
    }

    /** POST /grpc-ui/api/invoke — unary gRPC call, returns InvokeResponse (always HTTP 200). */
    Mono<ServerResponse> handleInvoke(ServerRequest req) {
        long start = System.currentTimeMillis();
        return req.bodyToMono(GrpcUiSchemaDto.InvokeRequest.class).flatMap(invokeReq -> {
            var ctx = registry.contextByServiceFqn(invokeReq.serviceName()).orElse(null);
            if (ctx == null || ctx.getChannel() == null) {
                // A REST_DOWNSTREAM service has synthetic descriptors but no gRPC channel.
                return ServerResponse.ok()
                        .bodyValue(new GrpcUiSchemaDto.InvokeResponse(
                                null,
                                "NOT_FOUND",
                                "Service not registered as a gRPC backend: " + invokeReq.serviceName(),
                                System.currentTimeMillis() - start));
            }
            // A client-streaming method takes every message the console collected; everything
            // else takes the single payload, so an older console keeps working unchanged.
            boolean clientStreaming = isClientStreaming(ctx, invokeReq);
            return metadataPopulator
                    .build(req.exchange().getRequest())
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
                    .map(response -> new GrpcUiSchemaDto.InvokeResponse(
                            response, "OK", null, System.currentTimeMillis() - start))
                    .onErrorResume(StatusRuntimeException.class, ex -> {
                        String code = ex.getStatus().getCode().name();
                        String msg = ex.getStatus().getDescription();
                        log.debug(
                                "UI invoke {}/{} → {} {}", invokeReq.serviceName(), invokeReq.methodName(), code, msg);
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
                                "UI invoke {}/{} → error: {}",
                                invokeReq.serviceName(),
                                invokeReq.methodName(),
                                ex.getMessage());
                        return Mono.just(new GrpcUiSchemaDto.InvokeResponse(
                                null, "INTERNAL", ex.getMessage(), System.currentTimeMillis() - start));
                    })
                    .flatMap(invokeResp -> ServerResponse.ok().bodyValue(invokeResp));
        });
    }

    /**
     * POST /grpc-ui/api/invoke/stream — server-streaming gRPC call via SSE.
     * The React frontend reads this via the Fetch API (not EventSource, which only supports GET).
     * Each SSE event contains one JSON response chunk; a final "done" event carries the duration.
     */
    Mono<ServerResponse> handleStream(ServerRequest req) {
        long start = System.currentTimeMillis();
        return req.bodyToMono(GrpcUiSchemaDto.InvokeRequest.class).flatMap(invokeReq -> {
            var ctx = registry.contextByServiceFqn(invokeReq.serviceName()).orElse(null);
            if (ctx == null) {
                return ServerResponse.notFound().build();
            }
            return metadataPopulator.build(req.exchange().getRequest()).flatMap(metadata -> {
                mergeCustomMetadata(metadata, invokeReq.metadata());
                Flux<ServerSentEvent<String>> events = dispatcher
                        .dispatchStreamingDirect(
                                ctx, invokeReq.serviceName(), invokeReq.methodName(), invokeReq.payload(), metadata)
                        .map(chunk -> ServerSentEvent.<String>builder()
                                .event("data")
                                .data(chunk)
                                .build())
                        .onErrorResume(StatusRuntimeException.class, ex -> {
                            String code = ex.getStatus().getCode().name();
                            String msg = ex.getStatus().getDescription();
                            log.debug(
                                    "UI stream {}/{} → {} {}",
                                    invokeReq.serviceName(),
                                    invokeReq.methodName(),
                                    code,
                                    msg);
                            return Flux.just(ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data(errorEvent(code, msg, GatewayErrorDetails.from(ex, ctx.getTypeRegistry())))
                                    .build());
                        })
                        .onErrorResume(Exception.class, ex -> {
                            log.warn(
                                    "UI stream {}/{} → error: {}",
                                    invokeReq.serviceName(),
                                    invokeReq.methodName(),
                                    ex.getMessage());
                            return Flux.just(ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data("{\"code\":\"INTERNAL\",\"message\":" + jsonStr(ex.getMessage()) + "}")
                                    .build());
                        })
                        .concatWith(Flux.defer(() -> Flux.just(ServerSentEvent.<String>builder()
                                .event("done")
                                .data("{\"durationMs\":" + (System.currentTimeMillis() - start) + "}")
                                .build())));

                return ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(events, SSE_TYPE);
            });
        });
    }

    /**
     * POST /grpc-ui/api/auth/{scheme}/token — server-side OAuth2 client_credentials exchange.
     * Proxies the token request to the configured provider's tokenUrl so the client_secret
     * never leaves the server boundary.
     */
    Mono<ServerResponse> handleClientCredentials(ServerRequest req) {
        String schemeId = req.pathVariable("scheme");
        var scheme = gatewayProperties.getOpenapi().getSecuritySchemes().get(schemeId);
        if (scheme == null || scheme.getClientCredentials() == null) {
            return ServerResponse.notFound().build();
        }
        String tokenUrl = scheme.getClientCredentials().getTokenUrl();

        return req.bodyToMono(GrpcUiSchemaDto.ClientCredentialsRequest.class).flatMap(body -> {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", body.clientId());
            form.add("client_secret", body.clientSecret());
            if (body.scopes() != null && !body.scopes().isEmpty()) {
                form.add("scope", String.join(" ", body.scopes()));
            }
            return WEB_CLIENT
                    .post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .onStatus(
                            status -> !status.is2xxSuccessful(),
                            resp -> resp.bodyToMono(String.class)
                                    .map(b -> new RuntimeException(
                                            "Token endpoint returned " + resp.statusCode() + ": " + b)))
                    .bodyToMono(MAP_TYPE)
                    .map(r -> new GrpcUiSchemaDto.TokenResponse(
                            (String) r.get("access_token"),
                            r.containsKey("expires_in") ? ((Number) r.get("expires_in")).longValue() : 3600L,
                            (String) r.getOrDefault("token_type", "Bearer")))
                    .flatMap(tok -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(tok))
                    .onErrorResume(Exception.class, ex -> {
                        log.warn("Client credentials exchange failed for scheme {}: {}", schemeId, ex.getMessage());
                        return ServerResponse.badRequest()
                                .bodyValue(Collections.singletonMap("error", ex.getMessage()));
                    });
        });
    }

    // -------------------------------------------------------------------------

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Whether the named method takes a stream of request messages. */
    private static boolean isClientStreaming(
            dev.appiaworks.shiguchi.routing.ServiceEndpointContext ctx, GrpcUiSchemaDto.InvokeRequest request) {
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

    private void mergeCustomMetadata(Metadata metadata, List<GrpcUiSchemaDto.MetadataEntry> entries) {
        if (entries == null) return;
        for (var e : entries) {
            if (e.key() != null && !e.key().isBlank()) {
                metadata.put(
                        Metadata.Key.of(e.key().toLowerCase().trim(), Metadata.ASCII_STRING_MARSHALLER),
                        e.value() != null ? e.value() : "");
            }
        }
    }

    private GrpcUiSchemaDto.AuthProviderDto toAuthProviderDto(String id, OpenApiConfig.SecuritySchemeConfig cfg) {
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
