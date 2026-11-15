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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenApiConfig.class);

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

            applySecurity(openApi);
        };
    }

    /**
     * Seeds Swagger UI's global {@code initOAuth} and logs configuration hints at startup.
     *
     * <ul>
     *   <li>With exactly one OAuth2 scheme that sets one client id, that id becomes the global
     *       default (never overriding {@code springdoc.swagger-ui.oauth.client-id}). With several
     *       schemes the global value stays untouched, because {@code initOAuth} would offer one
     *       provider's id to the others; {@link SwaggerClientIds} supplies the id per scheme.
     *   <li>When a scheme has an authorization-code flow with a client id (a public client), PKCE is
     *       switched on unless {@code springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant}
     *       is set.
     *   <li>Schemes without global security get one WARN, see {@link #warnsAboutMissingGlobalSecurity}.
     * </ul>
     */
    @Bean
    public org.springframework.beans.factory.SmartInitializingSingleton swaggerOAuthClientIdDefault(
            org.springframework.beans.factory.ObjectProvider<org.springdoc.core.properties.SwaggerUiOAuthProperties>
                    oauth) {
        return () -> {
            if (warnsAboutMissingGlobalSecurity(props)) {
                LOG.warn(
                        "shiguchi.openapi.security-schemes declares {} but shiguchi.openapi.global-security is empty: "
                                + "Swagger UI's Authorize dialog collects a token, yet \"Try it out\" sends none until "
                                + "global-security lists a scheme id (or the operations carry their own security). "
                                + "Set shiguchi.openapi.global-security to the scheme ids that apply to every operation.",
                        props.getOpenapi().getSecuritySchemes().keySet());
            }
            var p = oauth.getIfAvailable();
            if (p == null) return;
            var oauth2 = props.getOpenapi().getSecuritySchemes().values().stream()
                    .filter(s -> "oauth2".equalsIgnoreCase(s.getType()))
                    .toList();
            if (oauth2.size() == 1) {
                var ids = flowClientIds(oauth2.get(0));
                if (ids.size() == 1
                        && (p.getClientId() == null || p.getClientId().isBlank()))
                    p.setClientId(ids.iterator().next());
            }
            if (usesPublicAuthorizationCodeClient(props) && p.getUsePkceWithAuthorizationCodeGrant() == null)
                p.setUsePkceWithAuthorizationCodeGrant(true);
        };
    }

    /** True when schemes are declared but nothing applies them to operations by default. */
    static boolean warnsAboutMissingGlobalSecurity(GatewayProperties props) {
        return !props.getOpenapi().getSecuritySchemes().isEmpty()
                && props.getOpenapi().getGlobalSecurity().isEmpty();
    }

    /** True when some scheme has an authorization-code flow with a client id (a public client). */
    static boolean usesPublicAuthorizationCodeClient(GatewayProperties props) {
        return props.getOpenapi().getSecuritySchemes().values().stream().anyMatch(s -> {
            var f = s.getAuthorizationCode();
            return f != null && f.getClientId() != null && !f.getClientId().isBlank();
        });
    }

    /** Distinct non-blank client ids across a scheme's flows. */
    private static java.util.Set<String> flowClientIds(SecuritySchemeConfig s) {
        var ids = new java.util.LinkedHashSet<String>();
        for (var flow : java.util.Arrays.asList(
                s.getAuthorizationCode(), s.getClientCredentials(), s.getImplicit(), s.getPassword())) {
            if (flow != null
                    && flow.getClientId() != null
                    && !flow.getClientId().isBlank()) ids.add(flow.getClientId());
        }
        return ids;
    }

    /**
     * {schemeId: clientId} for every oauth2 scheme whose flows agree on one non-blank client id.
     * A scheme whose flows disagree is left out: one dialog could not tell which id to offer.
     */
    static java.util.Map<String, String> clientIdsByScheme(GatewayProperties props) {
        var out = new LinkedHashMap<String, String>();
        props.getOpenapi().getSecuritySchemes().forEach((id, s) -> {
            if (!"oauth2".equalsIgnoreCase(s.getType())) return;
            var ids = flowClientIds(s);
            if (ids.size() == 1) out.put(id, ids.iterator().next());
        });
        return out;
    }

    /**
     * Swagger UI plugin that gives each OAuth2 scheme its own client id. {@code initOAuth} is global,
     * so the plugin wraps the {@code oauth2} component and answers {@code authSelectors.getConfigs()}
     * with that scheme's id; the component reads it once, in its constructor, and an id already
     * stored for the scheme still wins.
     */
    static final class SwaggerClientIds {
        private static final String PLUGIN_NAME = "shiguchiOAuthClientIds";
        private static final String PLUGINS_MARKER = "plugins: [";
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private SwaggerClientIds() {}

        /** The initializer with the plugin defined and registered; unchanged when there is nothing to add. */
        static String inject(String initializerJs, java.util.Map<String, String> ids) {
            if (ids.isEmpty()) return initializerJs;
            int at = initializerJs.indexOf(PLUGINS_MARKER);
            if (at < 0) {
                LOG.warn("swagger-initializer.js has no 'plugins: [' entry: per-scheme client ids are not applied");
                return initializerJs;
            }
            int end = at + PLUGINS_MARKER.length();
            return pluginScript(ids)
                    + initializerJs.substring(0, end)
                    + PLUGIN_NAME
                    + ", "
                    + initializerJs.substring(end);
        }

        static String pluginScript(java.util.Map<String, String> ids) {
            String json;
            try {
                json = JSON.writeValueAsString(ids);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("cannot serialise the client id map", e);
            }
            // Jackson escapes quotes and backslashes; neutralise what could end a script or a string literal.
            json = json.replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            return "const " + PLUGIN_NAME + " = (function () {\n"
                    + "  const ids = " + json + ";\n"
                    + "  return function (system) {\n"
                    + "    const h = system.React.createElement;\n"
                    + "    return { wrapComponents: { oauth2: function (Original) {\n"
                    + "      return function (props) {\n"
                    + "        const id = Object.prototype.hasOwnProperty.call(ids, props.name) ? ids[props.name] : null;\n"
                    + "        if (!id) return h(Original, props);\n"
                    + "        const sel = props.authSelectors;\n"
                    + "        const authSelectors = Object.assign({}, sel, {\n"
                    + "          getConfigs: function () { return Object.assign({}, sel.getConfigs(), { clientId: id }); }\n"
                    + "        });\n"
                    + "        return h(Original, Object.assign({}, props, { authSelectors: authSelectors }));\n"
                    + "      };\n"
                    + "    } } };\n"
                    + "  };\n"
                    + "})();\n";
        }
    }

    /**
     * Decorates springdoc's index transformer so the generated {@code swagger-initializer.js} also
     * registers {@link SwaggerClientIds}. springdoc's own output (configUrl, redirect URL, initOAuth,
     * validator, presets) is kept as it is; this only adds the plugin. The id map is read per request,
     * so the decorator needs no early access to the properties.
     */
    @Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            name = "org.springdoc.webflux.ui.SwaggerIndexTransformer")
    static class SwaggerClientIdsConfiguration {
        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor swaggerClientIdsPostProcessor(
                org.springframework.beans.factory.ObjectProvider<GatewayProperties> properties) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof org.springdoc.webflux.ui.SwaggerIndexTransformer delegate)
                        return new ClientIdInjectingTransformer(delegate, properties);
                    return bean;
                }
            };
        }
    }

    static final class ClientIdInjectingTransformer implements org.springdoc.webflux.ui.SwaggerIndexTransformer {
        private final org.springdoc.webflux.ui.SwaggerIndexTransformer delegate;
        private final org.springframework.beans.factory.ObjectProvider<GatewayProperties> properties;

        ClientIdInjectingTransformer(
                org.springdoc.webflux.ui.SwaggerIndexTransformer delegate,
                org.springframework.beans.factory.ObjectProvider<GatewayProperties> properties) {
            this.delegate = delegate;
            this.properties = properties;
        }

        @Override
        public reactor.core.publisher.Mono<org.springframework.core.io.Resource> transform(
                org.springframework.web.server.ServerWebExchange exchange,
                org.springframework.core.io.Resource resource,
                org.springframework.web.reactive.resource.ResourceTransformerChain chain) {
            return delegate.transform(exchange, resource, chain).map(out -> {
                if (!"swagger-initializer.js".equals(out.getFilename())) return out;
                var gp = properties.getIfAvailable();
                if (gp == null) return out;
                var ids = clientIdsByScheme(gp);
                if (ids.isEmpty()) return out;
                try {
                    var js = new String(out.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    var injected = SwaggerClientIds.inject(js, ids);
                    return injected.equals(js)
                            ? out
                            : new org.springframework.web.reactive.resource.TransformedResource(
                                    out, injected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    /** Exposes /v3/api-docs.yaml (springdoc only serves JSON by default on WebFlux). */
    /**
     * Declares the configured security schemes (they drive Swagger UI's Authorize button) and applies the
     * global requirements. Shared by springdoc's main document and the per-backend documents.
     */
    void applySecurity(io.swagger.v3.oas.models.OpenAPI openApi) {
        var configuredSchemes = props.getOpenapi().getSecuritySchemes();
        if (!configuredSchemes.isEmpty()) {
            if (openApi.getComponents() == null) openApi.setComponents(new Components());
            if (openApi.getComponents().getSecuritySchemes() == null)
                openApi.getComponents().setSecuritySchemes(new LinkedHashMap<>());
            configuredSchemes.forEach(
                    (id, cfg) -> openApi.getComponents().getSecuritySchemes().putIfAbsent(id, toSecurityScheme(cfg)));
        }
        props.getOpenapi()
                .getGlobalSecurity()
                .forEach(schemeId -> openApi.addSecurityItem(new SecurityRequirement().addList(schemeId)));
    }

    /** One document per backend, for the Swagger UI dropdown: {@code /v3/api-docs/backends/{name}}. */
    @Bean
    public RouterFunction<ServerResponse> backendOpenApiRoute() {
        return RouterFunctions.route(GET("/v3/api-docs/backends/{name}"), req -> {
            String json = openApiCache.backendJson(req.pathVariable("name"), this::decorateBackendSpec);
            return json == null
                    ? ServerResponse.notFound().build()
                    : ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(json);
        });
    }

    /** A copy of the backend document with security added, leaving the shared builder output untouched. */
    private io.swagger.v3.oas.models.OpenAPI decorateBackendSpec(io.swagger.v3.oas.models.OpenAPI source) {
        var copy = new io.swagger.v3.oas.models.OpenAPI()
                .info(source.getInfo())
                .tags(source.getTags())
                .paths(source.getPaths());
        var components = new Components();
        if (source.getComponents() != null) mergeComponents(source.getComponents(), components);
        copy.setComponents(components);
        applySecurity(copy);
        return copy;
    }

    /**
     * Fills Swagger UI's document dropdown ("All services" plus one entry per backend) unless the
     * application configured {@code springdoc.swagger-ui.urls} itself. Needs two or more backends.
     */
    @Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnClass(
            name = "org.springdoc.core.properties.SwaggerUiConfigProperties")
    static class SwaggerBackendUrlsConfiguration {
        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor swaggerBackendUrlsPostProcessor(
                org.springframework.beans.factory.ObjectProvider<GatewayOpenApiBuilder> builder,
                org.springframework.beans.factory.ObjectProvider<GatewayProperties> properties) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof org.springdoc.core.properties.SwaggerUiConfigProperties ui) {
                        var gp = properties.getIfAvailable();
                        var b = builder.getIfAvailable();
                        if (gp != null && b != null) addBackendUrls(ui, gp, b);
                    }
                    return bean;
                }
            };
        }
    }

    static void addBackendUrls(
            org.springdoc.core.properties.SwaggerUiConfigProperties ui,
            GatewayProperties gp,
            GatewayOpenApiBuilder builder) {
        if (!gp.getOpenapi().isGroupByBackend()) return;
        if (ui.getUrls() != null && !ui.getUrls().isEmpty()) return;
        var names = builder.backendNames();
        if (names.size() < 2) return;
        var urls = new java.util.LinkedHashSet<
                org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl>();
        urls.add(new org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl(
                "All services", "/v3/api-docs", "All services"));
        names.forEach(n -> urls.add(new org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl(
                n, "/v3/api-docs/backends/" + n, n)));
        ui.setUrls(urls);
        ui.setUrlsPrimaryName("All services");
    }

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
