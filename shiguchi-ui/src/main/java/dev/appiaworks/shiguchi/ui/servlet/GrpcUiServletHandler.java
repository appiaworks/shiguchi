package dev.appiaworks.shiguchi.ui.servlet;

import dev.appiaworks.shiguchi.ui.GrpcUiSchemaDto;
import dev.appiaworks.shiguchi.ui.GrpcUiService;
import io.grpc.Metadata;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

/**
 * Spring MVC handler methods for the embedded gRPC UI API: a thin adapter over {@link
 * GrpcUiService} that reads the request, builds the call metadata on the request thread (the
 * security context is thread-bound) and renders the result. Unary calls complete asynchronously
 * ({@link ServerResponse#async}), so no servlet thread waits on the backend; a server stream is
 * written as server-sent events, with the same {@code data}, {@code error} and {@code done} events
 * as the WebFlux adapter.
 *
 * <p>Routes are registered by {@link GrpcUiServletConfig}. Security is the host application's:
 * the gateway's {@code requireAuth} is not applied here.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.function.RouterFunction")
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class GrpcUiServletHandler {

    private static final Logger log = LoggerFactory.getLogger(GrpcUiServletHandler.class);

    /**
     * Upper bound for one call, unary or streaming; the console's calls are interactive and
     * short-lived. Explicit so a slow unary call is not cut off by the container's async default
     * (30 s on Tomcat), which the WebFlux adapter does not have.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofHours(1);

    private final GrpcUiService service;
    private final ServletGrpcMetadata metadata;

    public GrpcUiServletHandler(GrpcUiService service, ServletGrpcMetadata metadata) {
        this.service = service;
        this.metadata = metadata;
    }

    /** GET {path}/api/config: enabled UI links and auth providers. */
    ServerResponse handleGetConfig(ServerRequest req) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.config(req.requestPath().contextPath().value()));
    }

    /** GET {path}/api/schema: all services, methods and input schemas. */
    ServerResponse handleGetSchema(ServerRequest req) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(service.schema());
    }

    /** POST {path}/api/invoke: unary or client-streaming call, returns InvokeResponse (always HTTP 200). */
    ServerResponse handleInvoke(ServerRequest req) throws Exception {
        var invokeReq = req.body(GrpcUiSchemaDto.InvokeRequest.class);
        Metadata callMetadata = metadata.build(req.servletRequest());
        CompletableFuture<ServerResponse> response = service.invoke(invokeReq, Mono.just(callMetadata))
                .map(r -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(r))
                .toFuture();
        return ServerResponse.async(response, CALL_TIMEOUT);
    }

    /**
     * POST {path}/api/invoke/stream: server-streaming call as SSE. Each {@code data} event carries
     * one JSON chunk, a failure becomes an {@code error} event, and a final {@code done} event
     * carries the duration. The subscription is disposed when the client goes away.
     */
    ServerResponse handleStream(ServerRequest req) throws Exception {
        var invokeReq = req.body(GrpcUiSchemaDto.InvokeRequest.class);
        if (!service.isRegistered(invokeReq.serviceName())) {
            return ServerResponse.notFound().build();
        }
        Metadata callMetadata = metadata.build(req.servletRequest());
        return ServerResponse.sse(
                builder -> {
                    // swap(): disposing before the subscription is set (an event failing to send
                    // during subscribe) still cancels it once it is.
                    Disposable.Swap subscription = Disposables.swap();
                    Runnable cancel = subscription::dispose;
                    builder.onTimeout(cancel);
                    builder.onError(t -> cancel.run());
                    builder.onComplete(cancel);
                    Disposable d = service.stream(invokeReq, Mono.just(callMetadata))
                            .subscribe(
                                    event -> {
                                        try {
                                            synchronized (builder) {
                                                builder.event(event.event());
                                                builder.data(event.data());
                                                builder.send();
                                            }
                                        } catch (IOException | RuntimeException ex) {
                                            // The client went away: stop the backend call.
                                            log.debug("UI stream send failed, cancelling: {}", ex.getMessage());
                                            cancel.run();
                                        }
                                    },
                                    err -> {
                                        log.warn("UI stream failed: {}", err.getMessage());
                                        builder.error(err);
                                    },
                                    builder::complete);
                    subscription.update(d);
                },
                CALL_TIMEOUT);
    }

    /**
     * POST {path}/api/auth/{scheme}/token: server-side OAuth2 client_credentials exchange, so the
     * client secret never leaves the server boundary.
     */
    ServerResponse handleClientCredentials(ServerRequest req) throws Exception {
        String schemeId = req.pathVariable("scheme");
        if (!service.hasClientCredentials(schemeId)) {
            return ServerResponse.notFound().build();
        }
        var body = req.body(GrpcUiSchemaDto.ClientCredentialsRequest.class);
        CompletableFuture<ServerResponse> response = service.clientCredentials(schemeId, body)
                .map(tok -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(tok))
                .onErrorResume(
                        Exception.class,
                        ex -> Mono.just(ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Collections.singletonMap("error", String.valueOf(ex.getMessage())))))
                .toFuture();
        return ServerResponse.async(response);
    }
}
