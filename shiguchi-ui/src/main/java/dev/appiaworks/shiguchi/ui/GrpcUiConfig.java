package dev.appiaworks.shiguchi.ui;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Registers the gRPC UI RouterFunction when shiguchi.ui.enabled=true.
 *
 * API routes:
 *   GET  {path}/api/schema        — proto schema for all registered services
 *   POST {path}/api/invoke        — unary gRPC call
 *   POST {path}/api/invoke/stream — server-streaming via SSE
 *
 * The UI SPA is served from classpath:/static/grpc-ui/ under {path}/**, so a custom
 * shiguchi.ui.path works. The root path redirects (context-path aware) to index.html;
 * hash-based routing (/#/...) handles all in-app navigation without server-side fallback.
 * WebFlux applications only; {@code dev.appiaworks.shiguchi.ui.servlet} is the servlet counterpart.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class GrpcUiConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcUiConfig.class);

    @Value("${server.port:8080}")
    private int serverPort;

    @EventListener(ApplicationReadyEvent.class)
    public void logUiUrl(ApplicationReadyEvent event) {
        String path = event.getApplicationContext()
                .getBean(GatewayProperties.class)
                .getUi()
                .getPath();
        log.info("gRPC UI available at http://localhost:{}{}/", serverPort, path);
    }

    @Bean
    public RouterFunction<ServerResponse> grpcUiRouterFunction(GatewayProperties props, GrpcUiController controller) {

        String path = props.getUi().getPath();

        return RouterFunctions.route()
                .GET(path + "/api/config", controller::handleGetConfig)
                .GET(path + "/api/schema", controller::handleGetSchema)
                .POST(path + "/api/invoke", controller::handleInvoke)
                .POST(path + "/api/invoke/stream", controller::handleStream)
                .POST(path + "/api/auth/{scheme}/token", controller::handleClientCredentials)
                .GET(path, req -> redirectToIndex(req, path))
                .GET(path + "/", req -> redirectToIndex(req, path))
                .resources(path + "/**", new ClassPathResource("static/grpc-ui/"))
                .build();
    }

    /** Redirects to the UI's index page, keeping the application's servlet/WebFlux context path. */
    private static Mono<ServerResponse> redirectToIndex(ServerRequest request, String path) {
        String contextPath = request.requestPath().contextPath().value();
        return ServerResponse.temporaryRedirect(URI.create(contextPath + path + "/index.html"))
                .build();
    }
}
