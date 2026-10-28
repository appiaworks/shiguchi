package dev.appiaworks.shiguchi.ui;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Registers the gRPC UI RouterFunction when shiguchi.ui.enabled=true.
 *
 * API routes:
 *   GET  {path}/api/schema        — proto schema for all registered services
 *   POST {path}/api/invoke        — unary gRPC call
 *   POST {path}/api/invoke/stream — server-streaming via SSE
 *
 * The UI SPA is served from src/main/resources/static/grpc-ui/ by Spring Boot's
 * static resource handler. The root path redirects to index.html; hash-based
 * routing (/#/...) handles all in-app navigation without server-side fallback.
 */
@Configuration
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
        URI indexUri = URI.create(path + "/index.html");

        return RouterFunctions.route()
                .GET(path + "/api/config", controller::handleGetConfig)
                .GET(path + "/api/schema", controller::handleGetSchema)
                .POST(path + "/api/invoke", controller::handleInvoke)
                .POST(path + "/api/invoke/stream", controller::handleStream)
                .POST(path + "/api/auth/{scheme}/token", controller::handleClientCredentials)
                .GET(path, req -> ServerResponse.temporaryRedirect(indexUri).build())
                .GET(
                        path + "/",
                        req -> ServerResponse.temporaryRedirect(indexUri).build())
                .build();
    }
}
