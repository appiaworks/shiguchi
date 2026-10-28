package dev.appiaworks.shiguchi.security;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.SecurityConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

/**
 * Default security configuration for the gateway.
 *
 * <p>Behaviour is driven by {@code shiguchi.security.mode} plus the per-surface
 * overrides in {@code shiguchi.security.surfaces.*}:
 * <ul>
 *   <li>{@code PERMIT_ALL} (default) — all exchanges permitted so the gateway boots and
 *       serves without an identity provider. A loud WARN is logged at startup. Individual
 *       surfaces can still be gated: {@code surfaces.ui.require-auth=true} (or the legacy
 *       {@code shiguchi.ui.require-auth}) protects the console + GraphiQL,
 *       {@code surfaces.graphql} / {@code surfaces.openapi} / {@code surfaces.rest}
 *       protect their paths. The {@code gateway-refresh} actuator endpoint, and the writes of
 *       {@code gateway-traffic} (which move calls between backend versions), always require
 *       authentication.</li>
 *   <li>{@code AUTHENTICATED} — every exchange requires an authenticated principal via an
 *       OAuth2 JWT resource server. Surfaces can be carved back open with
 *       {@code surfaces.<surface>.require-auth=false} (e.g. keep machine-facing REST open
 *       behind an authenticating ingress while browser surfaces stay gated). Fails fast at
 *       startup when auth is required anywhere but no {@link ReactiveJwtDecoder} bean and
 *       no {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} are present, so
 *       the gateway never silently 401s every request.</li>
 * </ul>
 *
 * <p>Surface → path mapping: {@code ui} = {@code <ui.path>/**} + {@code /graphiql};
 * {@code graphql} = {@code /graphql}; {@code openapi} = {@code /v3/api-docs/**} +
 * Swagger UI; {@code rest} = everything else (the transcoded routes).
 *
 * <p>CORS: {@code http.cors()} is always enabled and picks up the
 * {@code CorsConfigurationSource} built from {@code shiguchi.cors.*} when present
 * (no-op otherwise), so preflight requests succeed on authenticated surfaces.
 *
 * <p>{@link GatewaySecurityCustomizer} beans extend this chain (extra rules, filters, another
 * security-context repository) while keeping its surface rules. Defining your own
 * {@link SecurityWebFilterChain} bean fully overrides this configuration (see
 * {@link ConditionalOnMissingBean}).
 *
 * <p>Lives in {@code shiguchi-rest}, under the {@code dev.appiaworks.shiguchi} package every
 * auto-configuration scans, so the posture is the same whichever starter registered the scan —
 * the gateway's or the UI starter on its own. It must be registered before Boot's reactive
 * security auto-configurations (their chain and default user are {@code @ConditionalOnMissingBean}
 * too); {@link SecurityAutoConfigurationNames} carries the ordering both lines use.
 */
@Configuration
public class GatewaySecurityConfig implements org.springframework.context.EnvironmentAware {

    /** Read for the push surface's settings, which live in a module this one does not see. */
    private org.springframework.core.env.Environment environment;

    @Override
    public void setEnvironment(org.springframework.core.env.Environment environment) {
        this.environment = environment;
    }

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityConfig.class);

    /** How a JWT becomes the caller's authentication; push resolves the same one. */
    private JwtPrincipalConverter jwtConverter = new JwtPrincipalConverter(null);

    @Autowired
    void setJwtConverters(
            ObjectProvider<ReactiveJwtAuthenticationConverter> reactive,
            ObjectProvider<Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>>> any) {
        this.jwtConverter = JwtPrincipalConverter.resolve(reactive, any);
    }

    /** Extensions applied to the chain, in {@code @Order}; none by default. */
    private ObjectProvider<GatewaySecurityCustomizer> customizerBeans;

    @Autowired
    void setCustomizers(ObjectProvider<GatewaySecurityCustomizer> customizers) {
        this.customizerBeans = customizers;
    }

    /**
     * Binds gRPC-Web server streams and GraphQL subscriptions opened with the BFF session cookie
     * to their session. Always present; without a {@link CookieSessions} bean it binds nothing.
     */
    @Bean
    @ConditionalOnMissingBean
    public CookieSessionWatch cookieSessionWatch(
            GatewayProperties properties, ObjectProvider<CookieSessions> sessions) {
        return new CookieSessionWatch(
                sessions.getIfAvailable(), properties.getSecurity().getSessionCheckInterval(), null);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    public SecurityWebFilterChain securityFilterChain(
            ServerHttpSecurity http,
            GatewayProperties properties,
            ObjectProvider<ReactiveJwtDecoder> jwtDecoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${management.endpoints.web.base-path:/actuator}") String actuatorBasePath) {

        List<GatewaySecurityCustomizer> customizers = customizerBeans == null
                ? List.of()
                : customizerBeans.orderedStream().toList();

        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .cors(Customizer.withDefaults())
                // Stateless: callers are anonymous or carry a JWT, so nothing is ever kept in a
                // WebSession. Spring Security's default repository would still look one up (and
                // create one) on every request, and InMemoryWebSessionStore builds it on
                // boundedElastic then hands it on through parallel: two thread hand-offs per
                // request, ~100 us of latency on a request that otherwise never leaves the
                // event loop. The no-op repository never touches the session.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                // No saved request to replay (nothing redirects to a login page; the cache filter
                // stays but holds the no-op cache) and no logout endpoint: LogoutWebFilter would be
                // one more filter per request, and would claim POST /logout from a transcoded
                // route that happens to use it.
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable);

        boolean modeAuth = properties.getSecurity().getMode() == SecurityConfig.Mode.AUTHENTICATED;
        SecurityConfig.SurfacesConfig surfaces = properties.getSecurity().getSurfaces();

        // Legacy shiguchi.ui.require-auth keeps working as an alias for surfaces.ui.
        boolean uiAuth =
                surfaces.getUi().effective(modeAuth) || properties.getUi().isRequireAuth();
        boolean graphqlAuth = surfaces.getGraphql().effective(modeAuth);
        boolean openapiAuth = surfaces.getOpenapi().effective(modeAuth);
        boolean restAuth = surfaces.getRest().effective(modeAuth);
        boolean anyAuth = uiAuth || graphqlAuth || openapiAuth || restAuth;

        boolean jwtConfigured = jwtDecoder.getIfAvailable() != null || !issuerUri.isBlank() || !jwkSetUri.isBlank();
        boolean customizerAuthenticates = customizers.stream().anyMatch(GatewaySecurityCustomizer::authenticates);
        if (anyAuth && !jwtConfigured && !customizerAuthenticates) {
            throw new IllegalStateException("Authentication is enabled on at least one gateway surface, which "
                    + "requires an OAuth2 JWT decoder. Set "
                    + "spring.security.oauth2.resourceserver.jwt.issuer-uri (or jwk-set-uri), "
                    + "or define a ReactiveJwtDecoder bean. Refusing to start in a "
                    + "configuration that would reject those requests with 401.");
        }

        String uiBase = properties.getUi().getPath();
        String refreshPattern = actuatorBasePath + "/gateway-refresh";
        String[] trafficPaths = {actuatorBasePath + "/gateway-traffic", actuatorBasePath + "/gateway-traffic/**"};
        // The push surface's admin endpoint: reads like any actuator read, writes like gateway-traffic's.
        String[] pushAdminPaths = {actuatorBasePath + "/gateway-push", actuatorBasePath + "/gateway-push/**"};
        boolean pushOn =
                environment != null && environment.getProperty("shiguchi.cursus.enabled", Boolean.class, false);
        // The BFF's session admin endpoint: it shows and ends other users' sessions, so every method needs a caller.
        String[] bffAdminPaths = {
            actuatorBasePath + "/gateway-bff-sessions", actuatorBasePath + "/gateway-bff-sessions/**"
        };
        boolean bffOn = environment != null && environment.getProperty("shiguchi.bff.enabled", Boolean.class, false);
        String[] uiPaths = {uiBase + "/**", "/graphiql"};
        String[] graphqlPaths = {"/graphql"};
        String[] openapiPaths = {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

        if (!restAuth && properties.getSecurity().isOpenRestBypassesChain()) {
            // The chain matches only what it still gates or serves; an open transcoded route never
            // enters it. CORS and gRPC-Web paths stay in, since their handling lives in the chain.
            java.util.List<String> chainPaths = new java.util.ArrayList<>(java.util.List.of(refreshPattern));
            java.util.Collections.addAll(chainPaths, trafficPaths);
            java.util.Collections.addAll(chainPaths, uiPaths);
            java.util.Collections.addAll(chainPaths, graphqlPaths);
            java.util.Collections.addAll(chainPaths, openapiPaths);
            // Push (shiguchi-cursus) authorizes per namespace from the principal the chain
            // resolved, so its paths stay in even when the transcoded routes leave.
            if (pushOn) {
                java.util.Collections.addAll(chainPaths, pushAdminPaths);
                String pushPath =
                        environment.getProperty("shiguchi.cursus.path", "/push").trim();
                if (!pushPath.startsWith("/")) pushPath = "/" + pushPath;
                while (pushPath.length() > 1 && pushPath.endsWith("/")) {
                    pushPath = pushPath.substring(0, pushPath.length() - 1);
                }
                chainPaths.add(pushPath + "/**");
            }
            if (bffOn) java.util.Collections.addAll(chainPaths, bffAdminPaths);
            customizers.forEach(c -> chainPaths.addAll(c.chainPaths()));
            if (properties.getCors().isEnabled()) {
                chainPaths.addAll(properties.getCors().getPaths());
                if (properties.getGrpcWeb().isEnabled()
                        && properties.getCors().getPaths().isEmpty()) {
                    chainPaths.add(dev.appiaworks.shiguchi.gateway.GrpcWebFilter.normalizePrefix(
                                    properties.getGrpcWeb().getPathPrefix())
                            + "/**");
                }
            }
            http.securityMatcher(
                    org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers(
                            chainPaths.toArray(String[]::new)));
            log.info("gRPC gateway security: open REST routes bypass the security chain; it applies to {}", chainPaths);
        }

        http.authorizeExchange(authz -> {
            authz.pathMatchers(refreshPattern).authenticated();
            // Reading the split is like reading the routes; changing it moves production traffic.
            authz.pathMatchers(org.springframework.http.HttpMethod.POST, trafficPaths)
                    .authenticated();
            authz.pathMatchers(org.springframework.http.HttpMethod.DELETE, trafficPaths)
                    .authenticated();
            if (pushOn) {
                authz.pathMatchers(org.springframework.http.HttpMethod.POST, pushAdminPaths)
                        .authenticated();
                authz.pathMatchers(org.springframework.http.HttpMethod.DELETE, pushAdminPaths)
                        .authenticated();
            }
            if (bffOn) authz.pathMatchers(bffAdminPaths).authenticated();
            // After the always-gated actuator writes, so a customizer cannot reopen them.
            customizers.forEach(c -> c.authorize(authz));
            rule(authz, uiAuth, uiPaths);
            rule(authz, graphqlAuth, graphqlPaths);
            rule(authz, openapiAuth, openapiPaths);
            if (restAuth) {
                authz.anyExchange().authenticated();
            } else {
                authz.anyExchange().permitAll();
            }
        });

        // Wire the JWT resource server whenever a decoder is configured, so gated surfaces
        // (and the always-gated refresh endpoint) can actually authenticate.
        if (jwtConfigured) {
            ReactiveJwtDecoder decoder = jwtDecoder.getIfAvailable();
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                if (decoder != null) jwt.jwtDecoder(decoder);
                jwt.jwtAuthenticationConverter(jwtConverter);
            }));
        } else if (customizerAuthenticates) {
            // No resource server: without an entry point Spring answers 401 with a Basic
            // challenge, and a browser would then show its own login dialog.
            http.exceptionHandling(e -> e.authenticationEntryPoint(
                    new HttpStatusServerEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)));
        }

        if (!anyAuth) {
            log.warn("""
                    gRPC gateway security: PERMIT_ALL — the gateway does NOT authenticate callers. \
                    All REST/GraphQL/OpenAPI/UI endpoints are open at the transport layer. \
                    Only run this inside a trusted network or behind an authenticating proxy. \
                    Set shiguchi.security.mode=AUTHENTICATED, gate individual surfaces via \
                    shiguchi.security.surfaces.*, or provide your own SecurityWebFilterChain \
                    bean to harden. See SECURITY.md.""");
        } else if (!restAuth) {
            log.warn(
                    "gRPC gateway security: partial — surfaces gated: ui={}, graphql={}, openapi={}, "
                            + "but the transcoded REST routes are OPEN. Ensure an authenticating "
                            + "ingress fronts them or set security.surfaces.rest.require-auth=true.",
                    uiAuth,
                    graphqlAuth,
                    openapiAuth);
        } else {
            log.info(
                    "gRPC gateway security: authenticated (ui={}, graphql={}, openapi={}, rest={}).",
                    uiAuth,
                    graphqlAuth,
                    openapiAuth,
                    restAuth);
        }

        customizers.forEach(c -> c.customize(http));
        return http.build();
    }

    private static void rule(ServerHttpSecurity.AuthorizeExchangeSpec authz, boolean requireAuth, String... patterns) {
        if (requireAuth) {
            authz.pathMatchers(patterns).authenticated();
        } else {
            authz.pathMatchers(patterns).permitAll();
        }
    }

    /**
     * Suppresses Spring Boot's auto-configured random-password user. Without this the
     * UserDetailsServiceAutoConfiguration would print a generated password and create a
     * default user that has no role in this gateway's auth model.
     */
    @Bean
    @ConditionalOnMissingBean
    public MapReactiveUserDetailsService userDetailsService() {
        return new MapReactiveUserDetailsService(
                User.withUsername("__unused__").password("{noop}unused").roles().build());
    }
}
