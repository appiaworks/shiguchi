package dev.appiaworks.shiguchi.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AuthorizationRequestTest {

    private static final RouteDefinition ROUTE = RouteDefinition.builder(
                    "svc.pkg.Service", "Method", MethodDescriptor.MethodType.UNARY, "localhost:50051")
            .httpBinding("GET", "/v1/test")
            .build();
    private static final UsernamePasswordAuthenticationToken AUTH = new UsernamePasswordAuthenticationToken("a", null);

    @Test
    @DisplayName("GIVEN the old constructor and of() WHEN used SHOULD yield empty headers and no surface")
    void given_oldFactories_when_used_should_haveNoHeadersOrSurface() {
        var viaCtor = new AuthorizationRequest(ROUTE, AUTH, Map.of("k", "v"), "{}");
        var viaOf = AuthorizationRequest.of(ROUTE, AUTH, Map.of());
        assertThat(viaCtor.headers()).isEmpty();
        assertThat(viaCtor.surface()).isNull();
        assertThat(viaOf.headers()).isEmpty();
        assertThat(viaOf.surface()).isNull();
    }

    @Test
    @DisplayName("GIVEN headers with authorization and cookie WHEN built SHOULD drop them, case-insensitively")
    void given_sensitiveHeaders_when_built_should_redactAndBeCaseInsensitive() {
        var request = new AuthorizationRequest(
                ROUTE,
                AUTH,
                Map.of(),
                null,
                Map.of("AUTHORIZATION", List.of("x"), "Cookie", List.of("y"), "X-Tenant", List.of("acme")),
                AuthorizationRequest.Surface.REST);
        assertThat(request.headers()).containsOnlyKeys("X-Tenant");
        assertThat(request.headers().get("x-tenant")).containsExactly("acme");
        assertThatThrownBy(() -> request.headers().put("a", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("GIVEN a fractional or tiny retry-after WHEN asked SHOULD round up to at least 1 second")
    void given_retryAfter_when_asked_should_roundUp() {
        assertThat(new ThrottledException(Duration.ofMillis(10)).retryAfterSeconds())
                .isEqualTo(1);
        assertThat(new ThrottledException(Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
        assertThat(new ThrottledException(Duration.ofMillis(2001)).retryAfterSeconds())
                .isEqualTo(3);
        assertThat(new ThrottledException(null).retryAfterSeconds()).isEqualTo(1);
    }
}
