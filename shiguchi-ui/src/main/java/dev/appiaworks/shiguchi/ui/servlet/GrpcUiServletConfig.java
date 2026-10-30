package dev.appiaworks.shiguchi.ui.servlet;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Registers the gRPC UI's Spring MVC router function when {@code shiguchi.ui.enabled=true} in a
 * servlet application: the same routes as the WebFlux {@code GrpcUiConfig}, the SPA served from
 * {@code classpath:/static/grpc-ui/} under {@code {path}/**}, and a context-path-aware redirect
 * from {@code {path}} to its {@code index.html}.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.function.RouterFunction")
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class GrpcUiServletConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcUiServletConfig.class);

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
    public RouterFunction<ServerResponse> grpcUiServletRouterFunction(
            GatewayProperties props, GrpcUiServletHandler handler) {

        String path = props.getUi().getPath();

        return RouterFunctions.route()
                .GET(path + "/api/config", handler::handleGetConfig)
                .GET(path + "/api/schema", handler::handleGetSchema)
                .POST(path + "/api/invoke", handler::handleInvoke)
                .POST(path + "/api/invoke/stream", handler::handleStream)
                .POST(path + "/api/auth/{scheme}/token", handler::handleClientCredentials)
                .GET(path, req -> redirectToIndex(req, path))
                .GET(path + "/", req -> redirectToIndex(req, path))
                .resources(path + "/**", new ClassPathResource("static/grpc-ui/"))
                .build();
    }

    /** Redirects to the UI's index page, keeping the application's context path. */
    private static ServerResponse redirectToIndex(ServerRequest request, String path) {
        String contextPath = request.requestPath().contextPath().value();
        return ServerResponse.temporaryRedirect(URI.create(contextPath + path + "/index.html"))
                .build();
    }
}
