package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayPolicy;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer;
import dev.appiaworks.shiguchi.grpc.ProxyCallGuard;
import dev.appiaworks.shiguchi.routing.AuthzRuleDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/** The bearer, scope, policy and authz-rule decisions of the gRPC proxy's ENFORCE guard. */
class GrpcProxyEnforcerTest {

    private static final String SVC = "dev.example.Greeter";
    private static final String METHOD = "SayHello";
    private static final String FULL = SVC + "/" + METHOD;
    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final GatewayProperties properties = new GatewayProperties();
    private final GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
    private final List<GatewayPolicy> policies = new ArrayList<>();
    private Instant expiry = Instant.now().plusSeconds(300);
    private List<String> grantedScopes = List.of("read");

    private ReactiveJwtDecoder decoder;
    private GrpcProxyEnforcer guard;

    @BeforeEach
    void setUp() {
        decoder = token -> {
            if (!token.equals("good")) return Mono.error(new OAuth2AuthenticationException("bad token"));
            return Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("alice")
                    .issuedAt(Instant.now().minusSeconds(1))
                    .expiresAt(expiry)
                    .build());
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayPolicy> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> policies.stream());
        when(provider.stream()).thenAnswer(i -> policies.stream());
        GatewayAuthorizationEnforcer enforcer =
                new GatewayAuthorizationEnforcer(new ScopeGatewayAuthorizer(), provider);
        guard = new GrpcProxyEnforcer(
                properties,
                registry,
                enforcer,
                decoder,
                jwt -> Mono.<AbstractAuthenticationToken>just(new JwtAuthenticationToken(
                        jwt,
                        grantedScopes.stream()
                                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                                .toList(),
                        jwt.getSubject())));
        route(List.of(), List.of());
    }

    private void route(List<String> scopes, List<AuthzRuleDefinition> rules) {
        RouteDefinition route = RouteDefinition.builder(
                        SVC, METHOD, MethodDescriptor.MethodType.UNARY, "localhost:9090")
                .authScopes(scopes)
                .authzRules(rules)
                .build();
        when(registry.findBySourceMethod(SVC, METHOD)).thenReturn(Optional.of(route));
    }

    private static Metadata bearer(String token) {
        Metadata m = new Metadata();
        m.put(AUTH, "Bearer " + token);
        return m;
    }

    @Test
    @DisplayName("Given no authorization header, when admitted, should answer UNAUTHENTICATED")
    void GIVEN_noToken_WHEN_admit_SHOULD_unauthenticated() {
        ProxyCallGuard.Decision d = guard.admit(FULL, new Metadata());

        assertThat(d.allowed()).isFalse();
        assertThat(d.denial().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("Given a session cookie only, when admitted, should answer UNAUTHENTICATED")
    void GIVEN_cookieOnly_WHEN_admit_SHOULD_unauthenticated() {
        Metadata m = new Metadata();
        m.put(Metadata.Key.of("cookie", Metadata.ASCII_STRING_MARSHALLER), "SESSION=abc");

        assertThat(guard.admit(FULL, m).denial().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("Given a token the decoder rejects, when admitted, should answer UNAUTHENTICATED")
    void GIVEN_badToken_WHEN_admit_SHOULD_unauthenticated() {
        ProxyCallGuard.Decision d = guard.admit(FULL, bearer("forged"));

        assertThat(d.denial().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName(
            "Given a valid token and a route without requirements, when admitted, should allow with principal and expiry")
    void GIVEN_validToken_WHEN_admit_SHOULD_allowWithPrincipalAndExpiry() {
        ProxyCallGuard.Decision d = guard.admit(FULL, bearer("good"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.principal()).isEqualTo("alice");
        assertThat(d.expiresAt()).isBetween(expiry.minusSeconds(2), expiry.plusSeconds(2));
    }

    @Test
    @DisplayName("Given a route requiring a scope the token lacks, when admitted, should answer PERMISSION_DENIED")
    void GIVEN_missingScope_WHEN_admit_SHOULD_permissionDenied() {
        route(List.of("admin"), List.of());

        ProxyCallGuard.Decision d = guard.admit(FULL, bearer("good"));

        assertThat(d.denial().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("Given a route requiring a scope the token has, when admitted, should allow")
    void GIVEN_grantedScope_WHEN_admit_SHOULD_allow() {
        route(List.of("read"), List.of());

        assertThat(guard.admit(FULL, bearer("good")).allowed()).isTrue();
    }

    @Test
    @DisplayName(
            "Given a route with authz rules, when admitted, should answer PERMISSION_DENIED even for a valid token")
    void GIVEN_authzRuleRoute_WHEN_admit_SHOULD_permissionDenied() {
        route(List.of(), List.of(new AuthzRuleDefinition("doc", "viewer", "id")));

        ProxyCallGuard.Decision d = guard.admit(FULL, bearer("good"));

        assertThat(d.denial().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(d.denial().getDescription()).contains("authz rules");
    }

    @Test
    @DisplayName("Given a policy that denies, when admitted, should answer PERMISSION_DENIED")
    void GIVEN_policyDenies_WHEN_admit_SHOULD_permissionDenied() {
        policies.add(request -> Mono.error(new AccessDeniedException("no")));

        assertThat(guard.admit(FULL, bearer("good")).denial().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName(
            "Given a policy that throttles, when admitted, should answer RESOURCE_EXHAUSTED with a retry-after trailer")
    void GIVEN_policyThrottles_WHEN_admit_SHOULD_resourceExhaustedWithRetryAfter() {
        policies.add(request -> Mono.error(new ThrottledException(Duration.ofSeconds(3))));

        ProxyCallGuard.Decision d = guard.admit(FULL, bearer("good"));

        assertThat(d.denial().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(d.trailers().get(Metadata.Key.of("retry-after", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("3");
    }

    @Test
    @DisplayName("Given a policy, when admitted, should run it on the GRPC surface with empty arguments")
    void GIVEN_policy_WHEN_admit_SHOULD_seeGrpcSurfaceAndEmptyArguments() {
        List<AuthorizationRequest> seen = new ArrayList<>();
        policies.add(request -> {
            seen.add(request);
            return Mono.empty();
        });

        guard.admit(FULL, bearer("good"));

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).surface()).isEqualTo(AuthorizationRequest.Surface.GRPC);
        assertThat(seen.get(0).arguments()).isEqualTo(Map.of());
    }

    @Test
    @DisplayName(
            "Given ENFORCE mode and no ReactiveJwtDecoder, when the guard is built, should fail startup with a clear message")
    void GIVEN_noDecoder_WHEN_built_SHOULD_failFast() {
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(new ScopeGatewayAuthorizer());

        assertThatThrownBy(() -> new GrpcProxyEnforcer(properties, registry, enforcer, null, jwt -> Mono.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shiguchi.grpc.security.mode")
                .hasMessageContaining("ReactiveJwtDecoder");
    }
}
