package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.CacheVisibility;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CachePolicy")
class CachePolicyTest {

    private static RouteDefinition route(int cacheTtlSeconds) {
        return RouteDefinition.builder("example.Service", "Get", io.grpc.MethodDescriptor.MethodType.UNARY, "")
                .httpBinding("GET", "/v1/example")
                .cacheTtlSeconds(cacheTtlSeconds)
                .build();
    }

    private static ServiceDefinition.CacheRule rule(Duration ttl) {
        var r = new ServiceDefinition.CacheRule();
        r.setMethods(java.util.List.of("*"));
        r.setTtl(ttl);
        return r;
    }

    private static ServiceDefinition.CacheRule rule(
            Duration ttl, CacheVisibility visibility, Duration sharedMaxAge, Duration staleWhileRevalidate) {
        var r = rule(ttl);
        r.setVisibility(visibility);
        r.setSharedMaxAge(sharedMaxAge);
        r.setStaleWhileRevalidate(staleWhileRevalidate);
        return r;
    }

    private static ServiceDefinition service(ServiceDefinition.CacheRule rule) {
        var s = new ServiceDefinition();
        s.setName("example");
        s.setMode(dev.appiaworks.shiguchi.config.GatewayMode.DYNAMIC);
        s.setHost("localhost");
        s.setPort(9090);
        if (rule != null) s.setCache(java.util.List.of(rule));
        return s;
    }

    @Test
    @DisplayName("GIVEN a matching cache rule WHEN resolved SHOULD use the rule over the proto TTL")
    void given_cacheRule_when_resolved_should_useRule() {
        var rule = rule(Duration.ofSeconds(30));
        var svc = service(rule);
        var route = route(60);

        var policy = CachePolicy.resolve(route, svc, true);

        assertThat(policy).isNotNull();
        assertThat(policy.ttl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("GIVEN a cache rule with ttl 0 WHEN resolved SHOULD return null even with proto TTL")
    void given_ruleTtlZero_when_resolved_should_returnNull() {
        var rule = rule(Duration.ZERO);
        var svc = service(rule);
        var route = route(60);

        var policy = CachePolicy.resolve(route, svc, true);

        assertThat(policy).isNull();
    }

    @Test
    @DisplayName("GIVEN no rule but proto cacheTtlSeconds > 0 WHEN resolved SHOULD return AUTO policy with that TTL")
    void given_noRuleButProtoTtl_when_resolved_should_returnAutoPolicy() {
        var svc = service(null);
        var route = route(45);

        var policy = CachePolicy.resolve(route, svc, true);

        assertThat(policy).isNotNull();
        assertThat(policy.ttl()).isEqualTo(Duration.ofSeconds(45));
        assertThat(policy.visibility()).isEqualTo(CacheVisibility.AUTO);
    }

    @Test
    @DisplayName("GIVEN no rule and proto cacheTtlSeconds <= 0 WHEN resolved SHOULD return null")
    void given_noRuleAndNoProtoTtl_when_resolved_should_returnNull() {
        var svc = service(null);
        var route = route(0);

        var policy = CachePolicy.resolve(route, svc, true);

        assertThat(policy).isNull();
    }

    @Test
    @DisplayName("GIVEN a rule with serverCache null WHEN resolved SHOULD inherit the default flag")
    void given_ruleWithServerCacheNull_when_resolved_should_inheritDefault() {
        var rule = rule(Duration.ofSeconds(30));
        rule.setServerCache(null);
        var svc = service(rule);
        var route = route(0);

        var policy = CachePolicy.resolve(route, svc, true);

        assertThat(policy.serverCache()).isTrue();

        policy = CachePolicy.resolve(route, svc, false);
        assertThat(policy.serverCache()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a policy with AUTO visibility and identified request WHEN cacheControl called SHOULD return private, max-age=N")
    void given_autoVisibilityAndIdentified_when_cacheControl_should_returnPrivateMaxAge() {
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);

        assertThat(policy.cacheControl(true)).isEqualTo("private, max-age=60");
    }

    @Test
    @DisplayName(
            "GIVEN a policy with AUTO visibility and anonymous request WHEN cacheControl called SHOULD return max-age=N with s-maxage when set")
    void given_autoVisibilityAndAnonymous_when_cacheControl_should_returnMaxAgeAndSMaxAge() {
        var policy = new CachePolicy(
                Duration.ofSeconds(60), CacheVisibility.AUTO, Duration.ofSeconds(300), Duration.ZERO, true);

        assertThat(policy.cacheControl(false)).contains("max-age=60").contains("s-maxage=300");
    }

    @Test
    @DisplayName("GIVEN a PRIVATE visibility policy WHEN cacheControl called SHOULD never include s-maxage")
    void given_privateVisibility_when_cacheControl_should_excludeSMaxAge() {
        var policy = new CachePolicy(
                Duration.ofSeconds(60), CacheVisibility.PRIVATE, Duration.ofSeconds(300), Duration.ZERO, true);

        assertThat(policy.cacheControl(true)).startsWith("private, max-age=60");
        assertThat(policy.cacheControl(true)).doesNotContain("s-maxage");
    }

    @Test
    @DisplayName(
            "GIVEN a PUBLIC visibility policy WHEN cacheControl called SHOULD include public, max-age, s-maxage and stale-while-revalidate")
    void given_publicVisibility_when_cacheControl_should_includeFull() {
        var policy = new CachePolicy(
                Duration.ofSeconds(60), CacheVisibility.PUBLIC, Duration.ofSeconds(300), Duration.ofSeconds(120), true);

        String control = policy.cacheControl(false);
        assertThat(control).contains("public, max-age=60");
        assertThat(control).contains("s-maxage=300");
        assertThat(control).contains("stale-while-revalidate=120");
    }

    @Test
    @DisplayName("GIVEN perCaller called on PUBLIC visibility WHEN called SHOULD return false")
    void given_publicVisibility_when_perCaller_should_returnFalse() {
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.PUBLIC, null, Duration.ZERO, true);

        assertThat(policy.perCaller()).isFalse();
    }

    @Test
    @DisplayName("GIVEN perCaller called on AUTO or PRIVATE visibility WHEN called SHOULD return true")
    void given_autoOrPrivateVisibility_when_perCaller_should_returnTrue() {
        var autoPolicy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);
        var privatePolicy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.PRIVATE, null, Duration.ZERO, true);

        assertThat(autoPolicy.perCaller()).isTrue();
        assertThat(privatePolicy.perCaller()).isTrue();
    }
}
