package dev.appiaworks.shiguchi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

class RateLimitRulesPolicyTest {

    private static final RouteDefinition ORDERS_GET = route("acme.Orders", "Get", List.of());
    private static final RouteDefinition ORDERS_PLACE = route("acme.Orders", "Place", List.of("write"));
    private static final RouteDefinition USERS_GET = route("acme.Users", "Get", List.of("write"));

    private static RouteDefinition route(String service, String method, List<String> tags) {
        return RouteDefinition.builder(service, method, MethodDescriptor.MethodType.UNARY, "localhost:1")
                .httpBinding("GET", "/v1/" + service + "/" + method)
                .tags(tags)
                .build();
    }

    private static InboundRateLimitConfig.Rule rule(String id, String match, String tag, String key, int burst) {
        InboundRateLimitConfig.Rule r = new InboundRateLimitConfig.Rule();
        r.setId(id);
        r.setMatch(match);
        r.setTag(tag);
        if (key != null) r.setKey(key);
        r.setReplenishRate(1.0);
        r.setBurstCapacity(burst);
        return r;
    }

    private static final long[] NOW = {0};

    private static RateLimitRulesPolicy policy(InboundRateLimitConfig.Rule... rules) {
        NOW[0] = 0;
        return new RateLimitRulesPolicy(
                List.of(rules),
                1.0,
                10,
                "X-Forwarded-For",
                (id, rate, burst) -> new InMemoryRateLimiter(rate, burst, () -> NOW[0]));
    }

    private static Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of());
    }

    private static final Authentication ANON = new UsernamePasswordAuthenticationToken("anon", null);

    private static AuthorizationRequest req(RouteDefinition route, Authentication auth, String ip) {
        return new AuthorizationRequest(
                route,
                auth,
                Map.of(),
                null,
                ip == null ? Map.of() : Map.of("X-Forwarded-For", List.of(ip)),
                AuthorizationRequest.Surface.REST);
    }

    private static boolean allowed(RateLimitRulesPolicy p, AuthorizationRequest r) {
        try {
            p.check(r).block();
            return true;
        } catch (ThrottledException e) {
            return false;
        }
    }

    @Test
    @DisplayName("GIVEN a method glob rule WHEN other methods are called SHOULD never throttle them")
    void given_methodGlob_when_otherMethod_should_notApply() {
        RateLimitRulesPolicy p = policy(rule("r", "acme.Orders/*", null, null, 1));
        assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isTrue();
        assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isFalse();
        for (int i = 0; i < 5; i++)
            assertThat(allowed(p, req(USERS_GET, user("a"), null))).isTrue();
    }

    @Test
    @DisplayName("GIVEN a tag rule WHEN a tagged and an untagged route are called SHOULD throttle only the tagged one")
    void given_tagRule_when_called_should_matchOnlyTagged() {
        RateLimitRulesPolicy p = policy(rule("r", null, "write", null, 1));
        assertThat(allowed(p, req(ORDERS_PLACE, user("a"), null))).isTrue();
        assertThat(allowed(p, req(ORDERS_PLACE, user("a"), null))).isFalse();
        for (int i = 0; i < 5; i++)
            assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isTrue();
    }

    @Test
    @DisplayName("GIVEN a per-principal rule WHEN two principals call SHOULD keep independent buckets")
    void given_principalKey_when_twoUsers_should_beIndependent() {
        RateLimitRulesPolicy p = policy(rule("r", "acme.Orders/*", null, "principal", 1));
        assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isTrue();
        assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isFalse();
        assertThat(allowed(p, req(ORDERS_GET, user("b"), null))).isTrue();
    }

    @Test
    @DisplayName("GIVEN an anonymous caller WHEN key is principal SHOULD fall back to the client ip")
    void given_anonymous_when_principalKey_should_fallBackToIp() {
        RateLimitRulesPolicy p = policy(rule("r", "acme.Orders/*", null, "principal", 1));
        assertThat(allowed(p, req(ORDERS_GET, ANON, "1.1.1.1, 10.0.0.1"))).isTrue();
        assertThat(allowed(p, req(ORDERS_GET, ANON, "1.1.1.1"))).isFalse();
        assertThat(allowed(p, req(ORDERS_GET, ANON, "2.2.2.2"))).isTrue();
    }

    @Test
    @DisplayName("GIVEN a claim key WHEN two users share the claim value SHOULD share one bucket")
    void given_claimKey_when_sameClaim_should_shareBucket() {
        RateLimitRulesPolicy p = policy(rule("r", "acme.Orders/*", null, "claim:tenant", 1));
        assertThat(allowed(p, req(ORDERS_GET, jwt("u1", "t1"), null))).isTrue();
        assertThat(allowed(p, req(ORDERS_GET, jwt("u2", "t1"), null))).isFalse();
        assertThat(allowed(p, req(ORDERS_GET, jwt("u3", "t2"), null))).isTrue();
    }

    private static Authentication jwt(String sub, String tenant) {
        Jwt token = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub)
                .claim("tenant", tenant)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(token, List.of(), sub);
    }

    @Test
    @DisplayName("GIVEN an exhausted bucket WHEN called SHOULD throw ThrottledException with retryAfter above zero")
    void given_exhausted_when_called_should_throttleWithRetryAfter() {
        RateLimitRulesPolicy p = policy(rule("r", "acme.Orders/*", null, null, 1));
        allowed(p, req(ORDERS_GET, user("a"), null));
        assertThatThrownBy(() -> p.check(req(ORDERS_GET, user("a"), null)).block())
                .isInstanceOfSatisfying(
                        ThrottledException.class,
                        e -> assertThat(e.retryAfterSeconds()).isGreaterThan(0));
    }

    @Test
    @DisplayName("GIVEN cost 3 and burst 5 WHEN called twice SHOULD refuse the second call")
    void given_cost_when_called_should_takeThatManyTokens() {
        InboundRateLimitConfig.Rule r = rule("r", "acme.Orders/*", null, null, 5);
        r.setCost(3);
        RateLimitRulesPolicy p = policy(r);
        assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isTrue();
        assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isFalse();
    }

    @Test
    @DisplayName("GIVEN two matching rules WHEN the stricter is exhausted SHOULD throttle with the longer retryAfter")
    void given_twoRules_when_called_should_strictestWin() {
        RateLimitRulesPolicy p =
                policy(rule("loose", "acme.Orders/*", null, null, 100), rule("strict", null, "write", null, 1));
        assertThat(allowed(p, req(ORDERS_PLACE, user("a"), null))).isTrue();
        assertThat(allowed(p, req(ORDERS_PLACE, user("a"), null))).isFalse();
        // The loose rule alone still lets other routes through.
        assertThat(allowed(p, req(ORDERS_GET, user("a"), null))).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a limiter that refuses with a longer wait WHEN rules disagree SHOULD report the longest retryAfter")
    void given_disagreeingRules_when_called_should_reportLongestRetry() {
        RateLimitRulesPolicy p = new RateLimitRulesPolicy(
                List.of(rule("short", "acme.Orders/*", null, null, 1), rule("long", "acme.Orders/*", null, null, 1)),
                1.0,
                1,
                null,
                (id, rate, burst) ->
                        (key, tokens) -> Mono.just(RateLimiter.Decision.refuse(id.equals("long") ? 30 : 2)));
        assertThatThrownBy(() -> p.check(req(ORDERS_GET, user("a"), null)).block())
                .isInstanceOfSatisfying(
                        ThrottledException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(30));
    }

    @Test
    @DisplayName("GIVEN a failing backend WHEN the limiter fails open SHOULD allow, and a closed one SHOULD refuse")
    void given_backendError_when_called_should_followLimiterMode() {
        RateLimitRulesPolicy open = new RateLimitRulesPolicy(
                List.of(rule("r", "acme.Orders/*", null, null, 1)),
                1.0,
                1,
                null,
                (id, rate, burst) -> (key, tokens) -> Mono.just(RateLimiter.Decision.allow(0)));
        RateLimitRulesPolicy closed = new RateLimitRulesPolicy(
                List.of(rule("r", "acme.Orders/*", null, null, 1)),
                1.0,
                1,
                null,
                (id, rate, burst) -> (key, tokens) -> Mono.just(RateLimiter.Decision.refuse(1)));
        assertThat(allowed(open, req(ORDERS_GET, user("a"), null))).isTrue();
        assertThat(allowed(closed, req(ORDERS_GET, user("a"), null))).isFalse();
    }

    private static RateLimitRulesPolicy failing(InboundRateLimitConfig.BackendError mode, boolean throwSync) {
        return new RateLimitRulesPolicy(
                List.of(rule("r", "acme.Orders/*", null, null, 1)),
                1.0,
                1,
                null,
                (id, rate, burst) -> (key, tokens) -> {
                    if (throwSync) throw new IllegalStateException("backend down");
                    return Mono.<RateLimiter.Decision>error(new IllegalStateException("backend down"));
                },
                mode);
    }

    @Test
    @DisplayName("GIVEN a limiter that errors WHEN no mode is configured SHOULD allow the request (fail open)")
    void given_erroringLimiter_when_defaultMode_should_allow() {
        assertThat(allowed(failing(null, false), req(ORDERS_GET, user("a"), null)))
                .isTrue();
        assertThat(allowed(failing(InboundRateLimitConfig.BackendError.ALLOW, false), req(ORDERS_GET, user("a"), null)))
                .isTrue();
        assertThat(new InboundRateLimitConfig().getRulesOnBackendError())
                .isEqualTo(InboundRateLimitConfig.BackendError.ALLOW);
    }

    @Test
    @DisplayName("GIVEN a limiter that errors WHEN the mode is DENY SHOULD throttle with retryAfter 1")
    void given_erroringLimiter_when_denyMode_should_throttle() {
        for (boolean sync : new boolean[] {false, true}) {
            RateLimitRulesPolicy p = failing(InboundRateLimitConfig.BackendError.DENY, sync);
            assertThatThrownBy(() -> p.check(req(ORDERS_GET, user("a"), null)).block())
                    .isInstanceOfSatisfying(
                            ThrottledException.class,
                            e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
        }
    }

    @Test
    @DisplayName("GIVEN a limiter that throws synchronously WHEN the mode is ALLOW SHOULD allow the request")
    void given_throwingLimiter_when_allowMode_should_allow() {
        assertThat(allowed(failing(InboundRateLimitConfig.BackendError.ALLOW, true), req(ORDERS_GET, user("a"), null)))
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN an invalid rule WHEN built SHOULD fail with a message naming the rule")
    void given_invalidRules_when_built_should_failFast() {
        assertThatThrownBy(() -> policy(rule("r", null, null, null, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match, tag or both");
        assertThatThrownBy(() -> policy(rule("r", "x/*", null, "cookie", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key must be");
        assertThatThrownBy(() -> policy(rule("r", "x/*", null, null, 1), rule("r", "y/*", null, null, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate id");
    }
}
