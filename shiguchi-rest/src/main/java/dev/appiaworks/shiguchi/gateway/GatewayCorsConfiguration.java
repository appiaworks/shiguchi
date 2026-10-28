package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.config.CorsConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Builds the reactive {@link CorsConfigurationSource} from {@code shiguchi.cors.*}.
 *
 * <p>The source is consumed by the gateway's default {@code SecurityWebFilterChain}
 * (via {@code http.cors()}), so preflight requests succeed even on surfaces that require
 * authentication. When {@code paths} is empty, CORS applies only to the browser-facing
 * surfaces (UI console, GraphiQL, GraphQL, Swagger) — transcoded REST routes stay
 * CORS-free unless explicitly listed.
 *
 * <p>With {@code shiguchi.grpc-web.enabled}, the gRPC-Web method paths are a browser surface
 * too: a path that names a loaded service and method gets the same grant, plus the
 * {@code grpc-status}, {@code grpc-message} and {@code grpc-status-details-bin} response headers
 * exposed so the client can read a trailers-only failure. Other two-segment paths — a REST route
 * such as {@code /v1/books} — stay CORS-free.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.cors", name = "enabled", havingValue = "true")
public class GatewayCorsConfiguration {

    @Bean
    @ConditionalOnMissingBean(CorsConfigurationSource.class)
    public CorsConfigurationSource gatewayCorsConfigurationSource(
            GatewayProperties properties, ObjectProvider<GrpcRouteRegistry> registry) {
        CorsConfig cors = properties.getCors();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.getAllowedOrigins());
        config.setAllowedMethods(cors.getAllowedMethods());
        config.setAllowedHeaders(cors.getAllowedHeaders());
        config.setAllowCredentials(cors.isAllowCredentials());
        config.setMaxAge(cors.getMaxAge().toSeconds());

        List<String> paths = cors.getPaths().isEmpty() ? defaultBrowserPaths(properties) : cors.getPaths();

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        for (String path : paths) {
            source.registerCorsConfiguration(path, config);
        }
        if (!properties.getGrpcWeb().isEnabled() || !cors.getPaths().isEmpty()) {
            return source;
        }
        CorsConfiguration grpcWeb = new CorsConfiguration(config);
        grpcWeb.setExposedHeaders(GRPC_WEB_EXPOSED_HEADERS);
        String prefix = GrpcWebFilter.normalizePrefix(properties.getGrpcWeb().getPathPrefix());
        return exchange -> {
            CorsConfiguration matched = source.getCorsConfiguration(exchange);
            if (matched != null) return matched;
            String[] method =
                    GrpcWebFilter.methodPath(exchange.getRequest().getPath().value(), prefix);
            GrpcRouteRegistry routes = registry.getIfAvailable();
            boolean known = method != null
                    && routes != null
                    && routes.contextByServiceFqn(method[0])
                            .flatMap(ctx -> ctx.lookupMethod(method[0], method[1]))
                            .isPresent();
            return known ? grpcWeb : null;
        };
    }

    static final List<String> GRPC_WEB_EXPOSED_HEADERS =
            List.of("grpc-status", "grpc-message", "grpc-status-details-bin");

    /**
     * The surfaces a browser actually loads: the console, GraphiQL, GraphQL, and Swagger. This is
     * the one definition of "browser surface" in the gateway — CORS scopes to it, and so do the
     * security response headers — so the two cannot drift apart.
     *
     * <p>Transcoded REST routes are deliberately absent. They are an API for programs, where a
     * framing or content-type header protects nobody and a stray CORS grant is a real risk.
     */
    public static List<String> defaultBrowserPaths(GatewayProperties properties) {
        String uiPath = properties.getUi().getPath();
        return List.of(
                uiPath + "/**", "/graphiql", "/graphql", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");
    }
}
