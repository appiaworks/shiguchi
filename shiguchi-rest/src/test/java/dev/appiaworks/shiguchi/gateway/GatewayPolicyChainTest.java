package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.GatewayPolicy;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The {@link GatewayPolicy} chain run by {@link GatewayAuthorizationEnforcer} after the authorizer. */
class GatewayPolicyChainTest {

    private static final GatewayAuthorizer ALLOW = request -> Mono.empty();

    private static RouteDefinition route(String... scopes) {
        return RouteDefinition.builder(
                        "svc.pkg.Service", "Method", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/v1/test")
                .authScopes(List.of(scopes))
                .build();
    }

    private static ObjectProvider<GatewayPolicy> providerOf(GatewayPolicy... policies) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.setDependencyComparator(org.springframework.core.annotation.AnnotationAwareOrderComparator.INSTANCE);
        int i = 0;
        for (GatewayPolicy policy : policies) factory.registerSingleton("policy" + i++, policy);
        return factory.getBeanProvider(GatewayPolicy.class);
    }

    private static GatewayAuthorizationEnforcer enforcer(GatewayAuthorizer authorizer, GatewayPolicy... policies) {
        return new GatewayAuthorizationEnforcer(authorizer, providerOf(policies));
    }

    @Order(1)
    static class First implements GatewayPolicy {
        final List<String> calls;

        First(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Mono<Void> check(AuthorizationRequest request) {
            calls.add("first");
            return Mono.empty();
        }
    }

    @Order(2)
    static class Second implements GatewayPolicy {
        final List<String> calls;

        Second(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Mono<Void> check(AuthorizationRequest request) {
            calls.add("second");
            return Mono.empty();
        }
    }

    @Test
    @DisplayName("GIVEN no policies WHEN enforce SHOULD behave as before")
    void given_noPolicies_when_enforce_should_complete() {
        StepVerifier.create(enforcer(ALLOW).enforce(route())).expectComplete().verify();
        StepVerifier.create(new GatewayAuthorizationEnforcer(ALLOW).enforce(route()))
                .expectComplete()
                .verify();
    }

    @Test
    @DisplayName("GIVEN the default authorizer and no policy WHEN asked SHOULD NOT decide per caller")
    void given_default_authorizer_and_no_policy_when_asked_should_not_decide_per_caller() {
        assertThat(enforcer(new ScopeGatewayAuthorizer()).decidesPerCaller()).isFalse();
        assertThat(new GatewayAuthorizationEnforcer(new ScopeGatewayAuthorizer()).decidesPerCaller())
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a custom authorizer or any policy WHEN asked SHOULD decide per caller")
    void given_custom_authorizer_or_policy_when_asked_should_decide_per_caller() {
        assertThat(enforcer(ALLOW).decidesPerCaller()).isTrue();
        assertThat(enforcer(new ScopeGatewayAuthorizer(), new First(new ArrayList<>()))
                        .decidesPerCaller())
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN policies registered out of order WHEN enforce SHOULD run them in @Order order")
    void given_orderedPolicies_when_enforce_should_runInOrder() {
        List<String> calls = new CopyOnWriteArrayList<>();
        StepVerifier.create(enforcer(ALLOW, new Second(calls), new First(calls)).enforce(route()))
                .expectComplete()
                .verify();
        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    @DisplayName("GIVEN a policy that denies WHEN enforce SHOULD fail with AccessDeniedException and skip later ones")
    void given_denyingPolicy_when_enforce_should_denyAndStop() {
        List<String> calls = new CopyOnWriteArrayList<>();
        GatewayPolicy deny = new First(calls) {
            @Override
            public Mono<Void> check(AuthorizationRequest request) {
                return Mono.error(new AccessDeniedException("no"));
            }
        };
        StepVerifier.create(enforcer(ALLOW, deny, new Second(calls)).enforce(route()))
                .expectError(AccessDeniedException.class)
                .verify();
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a policy that errors WHEN enforce SHOULD deny (fail closed)")
    void given_erroringPolicy_when_enforce_should_deny() {
        GatewayPolicy broken = request -> Mono.error(new IllegalStateException("boom"));
        StepVerifier.create(enforcer(ALLOW, broken).enforce(route()))
                .expectError(AccessDeniedException.class)
                .verify();
    }

    @Test
    @DisplayName("GIVEN a policy that throws or returns null WHEN enforce SHOULD deny (fail closed)")
    void given_throwingOrNullPolicy_when_enforce_should_deny() {
        GatewayPolicy throwing = request -> {
            throw new IllegalStateException("boom");
        };
        GatewayPolicy returnsNull = request -> null;
        StepVerifier.create(enforcer(ALLOW, throwing).enforce(route()))
                .expectError(AccessDeniedException.class)
                .verify();
        StepVerifier.create(enforcer(ALLOW, returnsNull).enforce(route()))
                .expectError(AccessDeniedException.class)
                .verify();
    }

    @Test
    @DisplayName("GIVEN a policy that throttles WHEN enforce SHOULD propagate ThrottledException")
    void given_throttlingPolicy_when_enforce_should_propagateThrottle() {
        GatewayPolicy throttle = request -> Mono.error(new ThrottledException(Duration.ofMillis(1500)));
        StepVerifier.create(enforcer(ALLOW, throttle).enforce(route()))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ThrottledException.class);
                    assertThat(((ThrottledException) e).retryAfterSeconds()).isEqualTo(2);
                })
                .verify();
    }

    @Test
    @DisplayName("GIVEN a policy that allows WHEN the caller lacks the scope SHOULD still deny")
    void given_allowingPolicy_when_scopeMissing_should_stillDeny() {
        List<String> calls = new ArrayList<>();
        GatewayAuthorizationEnforcer enforcer = enforcer(new ScopeGatewayAuthorizer(), new First(calls));
        var auth =
                new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority("SCOPE_other")));
        StepVerifier.create(enforcer.enforce(route("read:books"))
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                Mono.just(new SecurityContextImpl(auth)))))
                .expectError(AccessDeniedException.class)
                .verify();
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("GIVEN headers and a surface WHEN enforce SHOULD hand them redacted to the policy")
    void given_headersAndSurface_when_enforce_should_reachPolicyRedacted() {
        AtomicReference<AuthorizationRequest> seen = new AtomicReference<>();
        GatewayPolicy capture = request -> {
            seen.set(request);
            return Mono.empty();
        };
        StepVerifier.create(enforcer(ALLOW, capture)
                        .enforce(
                                route(),
                                Map.of(),
                                null,
                                Map.of(
                                        "X-Tenant", List.of("acme"),
                                        "Authorization", List.of("Bearer secret"),
                                        "Cookie", List.of("sid=1")),
                                AuthorizationRequest.Surface.GRAPHQL))
                .expectComplete()
                .verify();
        assertThat(seen.get().surface()).isEqualTo(AuthorizationRequest.Surface.GRAPHQL);
        assertThat(seen.get().headers()).containsKey("x-tenant").doesNotContainKeys("authorization", "cookie");
    }
}
