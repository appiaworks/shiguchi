package dev.appiaworks.shiguchi.openapi;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.OpenApiConfig.SecuritySchemeConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Wires the generated proto-derived OpenAPI spec into springdoc.
 *
 * Registers an OpenApiCustomizer that merges proto-derived paths and component schemas,
 * and injects any configured security schemes, into the springdoc-managed OpenAPI object.
 * Also adds a direct /v3/api-docs.yaml endpoint.
 *
 * Enabled only when shiguchi.openapi.enabled=true (default true).
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.openapi", name = "enabled", matchIfMissing = true)
public class OpenApiConfig {

    private final OpenApiCache openApiCache;
    private final GatewayOpenApiBuilder openApiBuilder;
    private final GatewayProperties props;

    public OpenApiConfig(OpenApiCache openApiCache, GatewayOpenApiBuilder openApiBuilder, GatewayProperties props) {
        this.openApiCache = openApiCache;
        this.openApiBuilder = openApiBuilder;
        this.props = props;
    }

    /**
     * Merges proto-derived paths, component schemas, and configured security schemes
     * into springdoc's OpenAPI object. springdoc calls this after building its own spec.
     */
    @Bean
    public OpenApiCustomizer gatewayOpenApiCustomizer() {
        return openApi -> {
            var protoSpec = openApiBuilder.getSpec();

            // Merge paths
            if (protoSpec.getPaths() != null) {
                protoSpec.getPaths().forEach((path, item) -> {
                    if (openApi.getPaths() == null) openApi.paths(new io.swagger.v3.oas.models.Paths());
                    openApi.getPaths().addPathItem(path, item);
                });
            }

            // Merge components of every kind. Proto-derived routes only produce schemas, but a
            // merged REST_DOWNSTREAM document can carry parameters, responses, request bodies,
            // headers, examples, links and callbacks (and, when inherited, security schemes).
            if (protoSpec.getComponents() != null) {
                if (openApi.getComponents() == null) openApi.setComponents(new Components());
                mergeComponents(protoSpec.getComponents(), openApi.getComponents());
            }

            // Inject configured security schemes (drives Swagger UI's Authorize button)
            var configuredSchemes = props.getOpenapi().getSecuritySchemes();
            if (!configuredSchemes.isEmpty()) {
                if (openApi.getComponents() == null) openApi.setComponents(new Components());
                if (openApi.getComponents().getSecuritySchemes() == null)
                    openApi.getComponents().setSecuritySchemes(new LinkedHashMap<>());
                configuredSchemes.forEach((id, cfg) ->
                        openApi.getComponents().getSecuritySchemes().putIfAbsent(id, toSecurityScheme(cfg)));
            }

            // Apply global security requirements (applies listed schemes to every operation)
            props.getOpenapi()
                    .getGlobalSecurity()
                    .forEach(schemeId -> openApi.addSecurityItem(new SecurityRequirement().addList(schemeId)));
        };
    }

    /** Exposes /v3/api-docs.yaml (springdoc only serves JSON by default on WebFlux). */
    @Bean
    public RouterFunction<ServerResponse> openApiYamlRoute() {
        return RouterFunctions.route(
                GET("/v3/api-docs.yaml"),
                req -> ServerResponse.ok()
                        .contentType(MediaType.parseMediaType("application/yaml"))
                        .bodyValue(openApiCache.yaml()));
    }

    /** First-wins merge of every component kind; springdoc's own entries keep precedence. */
    static void mergeComponents(Components from, Components to) {
        to.setSchemas(mergeMap(from.getSchemas(), to.getSchemas()));
        to.setResponses(mergeMap(from.getResponses(), to.getResponses()));
        to.setParameters(mergeMap(from.getParameters(), to.getParameters()));
        to.setExamples(mergeMap(from.getExamples(), to.getExamples()));
        to.setRequestBodies(mergeMap(from.getRequestBodies(), to.getRequestBodies()));
        to.setHeaders(mergeMap(from.getHeaders(), to.getHeaders()));
        to.setSecuritySchemes(mergeMap(from.getSecuritySchemes(), to.getSecuritySchemes()));
        to.setLinks(mergeMap(from.getLinks(), to.getLinks()));
        to.setCallbacks(mergeMap(from.getCallbacks(), to.getCallbacks()));
    }

    private static <T> java.util.Map<String, T> mergeMap(java.util.Map<String, T> from, java.util.Map<String, T> to) {
        if (from == null || from.isEmpty()) return to;
        java.util.Map<String, T> out = to != null ? to : new LinkedHashMap<>();
        from.forEach(out::putIfAbsent);
        return out;
    }

    private SecurityScheme toSecurityScheme(SecuritySchemeConfig cfg) {
        var scheme = new SecurityScheme();
        if (cfg.getType() != null)
            scheme.setType(SecurityScheme.Type.valueOf(cfg.getType().toUpperCase()));
        scheme.setScheme(cfg.getScheme());
        scheme.setBearerFormat(cfg.getBearerFormat());
        if (cfg.getIn() != null)
            scheme.setIn(SecurityScheme.In.valueOf(cfg.getIn().toUpperCase()));
        scheme.setName(cfg.getName());
        scheme.setDescription(cfg.getDescription());
        scheme.setOpenIdConnectUrl(cfg.getOpenIdConnectUrl());

        if (cfg.getAuthorizationCode() != null
                || cfg.getClientCredentials() != null
                || cfg.getImplicit() != null
                || cfg.getPassword() != null) {
            var flows = new OAuthFlows();
            if (cfg.getAuthorizationCode() != null) flows.setAuthorizationCode(toOAuthFlow(cfg.getAuthorizationCode()));
            if (cfg.getClientCredentials() != null) flows.setClientCredentials(toOAuthFlow(cfg.getClientCredentials()));
            if (cfg.getImplicit() != null) flows.setImplicit(toOAuthFlow(cfg.getImplicit()));
            if (cfg.getPassword() != null) flows.setPassword(toOAuthFlow(cfg.getPassword()));
            scheme.setFlows(flows);
        }

        return scheme;
    }

    private OAuthFlow toOAuthFlow(SecuritySchemeConfig.OAuthFlowConfig cfg) {
        var flow = new OAuthFlow();
        flow.setAuthorizationUrl(cfg.getAuthorizationUrl());
        flow.setTokenUrl(cfg.getTokenUrl());
        flow.setRefreshUrl(cfg.getRefreshUrl());
        if (!cfg.getScopes().isEmpty()) {
            var scopes = new Scopes();
            cfg.getScopes().forEach(scopes::addString);
            flow.setScopes(scopes);
        }
        return flow;
    }
}
