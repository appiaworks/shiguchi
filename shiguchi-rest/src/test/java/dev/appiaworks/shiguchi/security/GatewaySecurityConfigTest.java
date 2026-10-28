package dev.appiaworks.shiguchi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.SecurityConfig;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.LogoutWebFilter;
import org.springframework.security.web.server.authorization.AuthorizationWebFilter;

/**
 * Behavioural tests for {@link GatewaySecurityConfig#securityFilterChain}. The chain factory
 * is exercised directly (no application context) so the assertions hold regardless of the
 * Spring Boot version the gateway is composed with.
 *
 * <p>Verified behaviour:
 * <ul>
 *   <li>the default ({@code PERMIT_ALL}, no IdP) produces a working chain;</li>
 *   <li>{@code AUTHENTICATED} without a JWT decoder fails fast rather than 401-ing everything;</li>
 *   <li>{@code AUTHENTICATED} with a decoder produces a chain;</li>
 *   <li>{@code ui.require-auth=true} without a JWT decoder fails fast.</li>
 * </ul>
 */
@DisplayName("GatewaySecurityConfig.securityFilterChain")
class GatewaySecurityConfigTest {

    private final GatewaySecurityConfig config = new GatewaySecurityConfig();

    private static ServerHttpSecurity http() {
        return ServerHttpSecurity.http();
    }

    /** An ObjectProvider that reports no ReactiveJwtDecoder bean is present. */
    private static ObjectProvider<ReactiveJwtDecoder> noJwtDecoder() {
        return jwtDecoderProvider(null);
    }

    /**
     * An ObjectProvider exposing the given decoder (or none when null). In a real app
     * Spring Boot's resource-server auto-config supplies a decoder built from issuer-uri/
     * jwk-set-uri; here we inject one directly so the chain can be built in isolation.
     */
    private static ObjectProvider<ReactiveJwtDecoder> jwtDecoderProvider(ReactiveJwtDecoder decoder) {
        return new ObjectProvider<>() {
            @Override
            public ReactiveJwtDecoder getObject() {
                if (decoder == null) throw new IllegalStateException("none");
                return decoder;
            }

            @Override
            public ReactiveJwtDecoder getObject(Object... args) {
                return getObject();
            }

            @Override
            public ReactiveJwtDecoder getIfAvailable() {
                return decoder;
            }

            @Override
            public ReactiveJwtDecoder getIfUnique() {
                return decoder;
            }

            @Override
            public Stream<ReactiveJwtDecoder> stream() {
                return decoder == null ? Stream.empty() : Stream.of(decoder);
            }
        };
    }

    @Test
    @DisplayName("GIVEN default PERMIT_ALL and no IdP WHEN building the chain THEN a chain is returned")
    void givenPermitAllNoIdp_whenBuildingChain_shouldReturnChain() {
        GatewayProperties props = new GatewayProperties();

        SecurityWebFilterChain chain = config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        assertThat(chain).isNotNull();
    }

    @Test
    @DisplayName("GIVEN mode=AUTHENTICATED but no JWT decoder WHEN building the chain THEN it fails fast")
    void givenAuthenticatedNoDecoder_whenBuildingChain_shouldFailFast() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setMode(SecurityConfig.Mode.AUTHENTICATED);

        assertThatThrownBy(() -> config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an OAuth2 JWT decoder");
    }

    @Test
    @DisplayName(
            "GIVEN mode=AUTHENTICATED and an available JWT decoder WHEN building the chain THEN a chain is returned")
    void givenAuthenticatedWithDecoder_whenBuildingChain_shouldReturnChain() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setMode(SecurityConfig.Mode.AUTHENTICATED);
        ReactiveJwtDecoder decoder = token -> reactor.core.publisher.Mono.empty();

        SecurityWebFilterChain chain = config.securityFilterChain(
                http(), props, jwtDecoderProvider(decoder), "https://idp.example.com", "", "/actuator");

        assertThat(chain).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN mode=AUTHENTICATED with only a jwk-set-uri and no decoder bean WHEN building the chain THEN it does not fail fast")
    void givenAuthenticatedWithJwkUriOnly_whenBuildingChain_shouldNotFailFast() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setMode(SecurityConfig.Mode.AUTHENTICATED);

        // jwk-set-uri present but no decoder bean: the fail-fast guard must NOT trip (the real
        // app's auto-config builds the decoder). Building the chain here would need that bean,
        // so we assert only that the startup guard accepts the configuration.
        assertThatThrownBy(() -> config.securityFilterChain(
                        http(), props, noJwtDecoder(), "", "https://idp.example.com/jwks", "/actuator"))
                .isNotInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("GIVEN ui.require-auth=true but no JWT decoder WHEN building the chain THEN it fails fast")
    void givenUiRequireAuthNoDecoder_whenBuildingChain_shouldFailFast() {
        GatewayProperties props = new GatewayProperties();
        props.getUi().setRequireAuth(true);

        assertThatThrownBy(() -> config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an OAuth2 JWT decoder");
    }

    // -------------------------------------------------------------------------
    // Per-surface require-auth tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN mode=PERMIT_ALL + surfaces.graphql.require-auth=true + no JWT decoder WHEN building chain SHOULD throw IllegalStateException")
    void GIVEN_permitAllWithGraphqlRequireAuth_WHEN_buildingChain_SHOULD_failFastNeedingDecoder() {
        // Arrange
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setMode(SecurityConfig.Mode.PERMIT_ALL);
        props.getSecurity().getSurfaces().getGraphql().setRequireAuth(true); // Override to require auth

        // Act & Assert
        assertThatThrownBy(() -> config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an OAuth2 JWT decoder");
    }

    @Test
    @DisplayName(
            "GIVEN mode=AUTHENTICATED + all four surfaces.*.require-auth=false + no JWT decoder WHEN building chain SHOULD build successfully")
    void GIVEN_authenticatedWithAllSurfacesOpen_WHEN_noJwtDecoder_SHOULD_buildSuccessfully() {
        // Arrange
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setMode(SecurityConfig.Mode.AUTHENTICATED);
        // Override all surfaces to NOT require auth
        props.getSecurity().getSurfaces().getUi().setRequireAuth(false);
        props.getSecurity().getSurfaces().getGraphql().setRequireAuth(false);
        props.getSecurity().getSurfaces().getOpenapi().setRequireAuth(false);
        props.getSecurity().getSurfaces().getRest().setRequireAuth(false);

        // Act & Assert — should NOT throw even though JWT decoder is absent (no surface requires auth)
        SecurityWebFilterChain chain = config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        assertThat(chain).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN mode=AUTHENTICATED + surfaces.rest.require-auth=false + decoder present WHEN building chain SHOULD build successfully")
    void GIVEN_authenticatedWithRestOpen_WHEN_decoderPresent_SHOULD_buildSuccessfully() {
        // Arrange
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setMode(SecurityConfig.Mode.AUTHENTICATED);
        // Only REST is open (other surfaces inherit AUTHENTICATED default = require auth)
        props.getSecurity().getSurfaces().getRest().setRequireAuth(false);

        ReactiveJwtDecoder decoder = token -> reactor.core.publisher.Mono.empty();

        // Act & Assert
        SecurityWebFilterChain chain = config.securityFilterChain(
                http(), props, jwtDecoderProvider(decoder), "https://idp.example.com", "", "/actuator");

        assertThat(chain).isNotNull();
    }

    @Test
    @DisplayName("GIVEN default PERMIT_ALL WHEN building the chain SHOULD carry no logout filter")
    void GIVEN_permitAll_WHEN_buildingChain_SHOULD_excludeLogout() {
        // Arrange
        GatewayProperties props = new GatewayProperties();
        SecurityWebFilterChain chain = config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        // Act
        List<Class<?>> filters =
                chain.getWebFilters().map(Object::getClass).collectList().block();

        // Assert
        assertThat(filters).isNotEmpty();
        assertThat(filters).doesNotContain(LogoutWebFilter.class);
        assertThat(filters).contains(AuthorizationWebFilter.class);
    }

    @Test
    @DisplayName(
            "GIVEN default properties with openRestBypassesChain=false WHEN building the chain SHOULD match transcoded and gated routes")
    void GIVEN_openRestBypassesChainFalse_WHEN_buildingChain_SHOULD_matchAll() {
        // Arrange
        GatewayProperties props = new GatewayProperties();
        // Verify bypass is off (default)
        assertThat(props.getSecurity().isOpenRestBypassesChain()).isFalse();

        SecurityWebFilterChain chain = config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        // Act & Assert — chain should match all paths including transcoded routes
        var transcodeRequest = org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(
                        "/bench.Orders/Get")
                .build();
        var transcodeExchange = org.springframework.mock.web.server.MockServerWebExchange.from(transcodeRequest);
        assertThat(chain.matches(transcodeExchange).block()).isTrue();

        var refreshRequest = org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(
                        "/actuator/gateway-refresh")
                .build();
        var refreshExchange = org.springframework.mock.web.server.MockServerWebExchange.from(refreshRequest);
        assertThat(chain.matches(refreshExchange).block()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN mode=PERMIT_ALL + openRestBypassesChain=true WHEN building the chain SHOULD exclude transcoded routes from the chain")
    void GIVEN_openRestBypassesChainTrue_WHEN_buildingChain_SHOULD_bypassTranscoded() {
        // Arrange
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setOpenRestBypassesChain(true);

        SecurityWebFilterChain chain = config.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        // Act & Assert — transcoded route should NOT match (bypassed); gated/browser paths should match
        var transcodeRequest = org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(
                        "/bench.Orders/Get")
                .build();
        var transcodeExchange = org.springframework.mock.web.server.MockServerWebExchange.from(transcodeRequest);
        assertThat(chain.matches(transcodeExchange).block()).isFalse();

        var refreshRequest = org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(
                        "/actuator/gateway-refresh")
                .build();
        var refreshExchange = org.springframework.mock.web.server.MockServerWebExchange.from(refreshRequest);
        assertThat(chain.matches(refreshExchange).block()).isTrue();

        var trafficRequest = org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(
                        "/actuator/gateway-traffic")
                .build();
        var trafficExchange = org.springframework.mock.web.server.MockServerWebExchange.from(trafficRequest);
        assertThat(chain.matches(trafficExchange).block()).isTrue();

        var graphqlRequest = org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/graphql")
                .build();
        var graphqlExchange = org.springframework.mock.web.server.MockServerWebExchange.from(graphqlRequest);
        assertThat(chain.matches(graphqlExchange).block()).isTrue();

        var swaggerRequest = org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(
                        "/swagger-ui/index.html")
                .build();
        var swaggerExchange = org.springframework.mock.web.server.MockServerWebExchange.from(swaggerRequest);
        assertThat(chain.matches(swaggerExchange).block()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN openRestBypassesChain=true and push enabled WHEN building the chain SHOULD keep the push paths in the chain")
    void GIVEN_bypassWithPush_WHEN_buildingChain_SHOULD_keepPushPaths() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setOpenRestBypassesChain(true);
        GatewaySecurityConfig withPush = new GatewaySecurityConfig();
        withPush.setEnvironment(new org.springframework.mock.env.MockEnvironment()
                .withProperty("shiguchi.cursus.enabled", "true")
                .withProperty("shiguchi.cursus.path", "events/"));

        SecurityWebFilterChain chain = withPush.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        var subscribe = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/events/subscribe?topic=a:b")
                        .build());
        assertThat(chain.matches(subscribe).block()).isTrue();
        var transcoded = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/bench.Orders/Get")
                        .build());
        assertThat(chain.matches(transcoded).block()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN push enabled WHEN building the chain under the REST bypass SHOULD keep the gateway-push endpoint's paths in the chain")
    void GIVEN_pushEnabled_WHEN_buildingChain_SHOULD_gatePushAdminWrites() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setOpenRestBypassesChain(true);
        GatewaySecurityConfig withPush = new GatewaySecurityConfig();
        withPush.setEnvironment(
                new org.springframework.mock.env.MockEnvironment().withProperty("shiguchi.cursus.enabled", "true"));

        SecurityWebFilterChain chain = withPush.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        var adminDelete = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.delete(
                                "/actuator/gateway-push/connections/abc")
                        .build());
        assertThat(chain.matches(adminDelete).block()).isTrue();
        var adminRead = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/actuator/gateway-push/topics")
                        .build());
        assertThat(chain.matches(adminRead).block()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN the BFF enabled WHEN building the chain under the REST bypass SHOULD keep the gateway-bff-sessions paths in the chain")
    void GIVEN_bffEnabled_WHEN_buildingChain_SHOULD_keepBffSessionsAdminPaths() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setOpenRestBypassesChain(true);
        GatewaySecurityConfig withBff = new GatewaySecurityConfig();
        withBff.setEnvironment(
                new org.springframework.mock.env.MockEnvironment().withProperty("shiguchi.bff.enabled", "true"));

        SecurityWebFilterChain chain = withBff.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        var revoke = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.delete(
                                "/actuator/gateway-bff-sessions/idp/alice")
                        .build());
        assertThat(chain.matches(revoke).block()).isTrue();
        var read = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(
                                "/actuator/gateway-bff-sessions/idp/alice")
                        .build());
        assertThat(chain.matches(read).block()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN the BFF disabled WHEN building the chain under the REST bypass SHOULD leave the gateway-bff-sessions paths out")
    void GIVEN_bffDisabled_WHEN_buildingChain_SHOULD_leaveBffSessionsAdminPathsOut() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setOpenRestBypassesChain(true);
        GatewaySecurityConfig noBff = new GatewaySecurityConfig();
        noBff.setEnvironment(new org.springframework.mock.env.MockEnvironment());

        SecurityWebFilterChain chain = noBff.securityFilterChain(http(), props, noJwtDecoder(), "", "", "/actuator");

        var revoke = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.delete(
                                "/actuator/gateway-bff-sessions/idp/alice")
                        .build());
        assertThat(chain.matches(revoke).block()).isFalse();
    }
}
