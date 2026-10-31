package dev.appiaworks.shiguchi.ui;

import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import java.util.Collections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebFlux handler methods for the embedded gRPC UI API: a thin adapter over {@link GrpcUiService}
 * that reads the request, builds the call metadata from the reactive security context and renders
 * the result. Routes are registered by {@link GrpcUiConfig}; the servlet counterpart lives in
 * {@code dev.appiaworks.shiguchi.ui.servlet}.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class GrpcUiController {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final GrpcUiService service;
    private final GrpcMetadataPopulator metadataPopulator;

    public GrpcUiController(GrpcUiService service, GrpcMetadataPopulator metadataPopulator) {
        this.service = service;
        this.metadataPopulator = metadataPopulator;
    }

    /** GET /grpc-ui/api/config — returns enabled UI links and auth providers. */
    Mono<ServerResponse> handleGetConfig(ServerRequest req) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(service.config(req.requestPath().contextPath().value()));
    }

    /** GET /grpc-ui/api/schema — returns all services, methods and input schemas. */
    Mono<ServerResponse> handleGetSchema(ServerRequest req) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(service.schema());
    }

    /** POST /grpc-ui/api/invoke — unary gRPC call, returns InvokeResponse (always HTTP 200). */
    Mono<ServerResponse> handleInvoke(ServerRequest req) {
        return req.bodyToMono(GrpcUiSchemaDto.InvokeRequest.class)
                .flatMap(invokeReq -> service.invoke(
                        invokeReq, metadataPopulator.build(req.exchange().getRequest())))
                .flatMap(invokeResp -> ServerResponse.ok().bodyValue(invokeResp));
    }

    /**
     * POST /grpc-ui/api/invoke/stream — server-streaming gRPC call via SSE.
     * The React frontend reads this via the Fetch API (not EventSource, which only supports GET).
     * Each SSE event contains one JSON response chunk; a final "done" event carries the duration.
     */
    Mono<ServerResponse> handleStream(ServerRequest req) {
        return req.bodyToMono(GrpcUiSchemaDto.InvokeRequest.class).flatMap(invokeReq -> {
            if (!service.isRegistered(invokeReq.serviceName())) {
                return ServerResponse.notFound().build();
            }
            Flux<ServerSentEvent<String>> events = service.stream(
                            invokeReq, metadataPopulator.build(req.exchange().getRequest()))
                    .map(e -> ServerSentEvent.<String>builder()
                            .event(e.event())
                            .data(e.data())
                            .build());
            return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(events, SSE_TYPE);
        });
    }

    /**
     * POST /grpc-ui/api/auth/{scheme}/token — server-side OAuth2 client_credentials exchange.
     * Proxies the token request to the configured provider's tokenUrl so the client_secret
     * never leaves the server boundary.
     */
    Mono<ServerResponse> handleClientCredentials(ServerRequest req) {
        String schemeId = req.pathVariable("scheme");
        if (!service.hasClientCredentials(schemeId)) {
            return ServerResponse.notFound().build();
        }
        return req.bodyToMono(GrpcUiSchemaDto.ClientCredentialsRequest.class)
                .flatMap(body -> service.clientCredentials(schemeId, body))
                .flatMap(tok -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(tok))
                .onErrorResume(
                        Exception.class,
                        ex -> ServerResponse.badRequest()
                                .bodyValue(Collections.singletonMap("error", ex.getMessage())));
    }
}
