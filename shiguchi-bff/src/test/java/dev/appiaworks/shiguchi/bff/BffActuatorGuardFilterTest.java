package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

@DisplayName("BffActuatorGuardFilter: browser sessions never reach actuator endpoints")
class BffActuatorGuardFilterTest {

    private static Authentication cookieUser() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "n/a");
        auth.setDetails(new CookieAuthenticated(null, "handle", "eve"));
        return auth;
    }

    private static Authentication bearerUser() {
        return new TestingAuthenticationToken("bob", "n/a");
    }

    private static MockServerWebExchange run(
            BffActuatorGuardFilter filter,
            MockServerHttpRequest.BaseBuilder<?> request,
            Authentication auth,
            AtomicBoolean chained) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        filter.filter(exchange, e -> {
                    chained.set(true);
                    return Mono.empty();
                })
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();
        return exchange;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    @DisplayName("GIVEN a cookie caller WHEN calling an actuator path with any method SHOULD answer 403 and not chain")
    void given_cookie_when_actuator_should_forbid(String method) {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                new BffActuatorGuardFilter("/actuator"),
                MockServerHttpRequest.method(
                        org.springframework.http.HttpMethod.valueOf(method),
                        "/actuator/gateway-bff-sessions/idp/alice"),
                cookieUser(),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .isEqualTo("{\"error\":\"cookie sessions cannot call actuator endpoints\"}");
    }

    @Test
    @DisplayName("GIVEN a bearer caller WHEN calling an actuator path SHOULD pass")
    void given_bearer_when_actuator_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                new BffActuatorGuardFilter("/actuator"),
                MockServerHttpRequest.delete("/actuator/gateway-push/x"),
                bearerUser(),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller WHEN calling a path outside the actuator base SHOULD pass, also a look-alike prefix")
    void given_cookie_when_other_path_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();
        BffActuatorGuardFilter filter = new BffActuatorGuardFilter("/actuator");

        run(filter, MockServerHttpRequest.get("/v1/things"), cookieUser(), chained);
        assertThat(chained).isTrue();
        chained.set(false);
        run(filter, MockServerHttpRequest.get("/actuators/x"), cookieUser(), chained);
        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a custom management base path WHEN a cookie caller uses it SHOULD answer 403 and the default path passes")
    void given_custom_base_path_should_guard_that_path() {
        BffActuatorGuardFilter filter = new BffActuatorGuardFilter("/manage/");
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange blocked = run(filter, MockServerHttpRequest.get("/manage/health"), cookieUser(), chained);
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(chained).isFalse();

        run(filter, MockServerHttpRequest.get("/actuator/health"), cookieUser(), chained);
        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN an anonymous caller WHEN calling an actuator path SHOULD pass to the chain's own rules")
    void given_anonymous_when_actuator_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        new BffActuatorGuardFilter("/actuator")
                .filter(exchange, e -> {
                    chained.set(true);
                    return Mono.empty();
                })
                .block();

        assertThat(chained).isTrue();
    }
}
