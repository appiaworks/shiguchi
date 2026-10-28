package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

@DisplayName("BffOriginFilter: optional anti-CSRF header")
class BffCsrfHeaderFilterTest {

    private static final String EVE = "https://eve.example.com";
    private static final String HEADER = "X-CSRF";

    private final BffApplications applications = new BffApplications(BffTestFixtures.properties());
    private final BffOriginFilter on = new BffOriginFilter(applications, "/bff", "/graphql", HEADER, "1");
    private final BffOriginFilter off = new BffOriginFilter(applications, "/bff", "/graphql");

    private static Authentication cookieUser() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "n/a");
        auth.setDetails(new CookieAuthenticated(null, "handle", "eve"));
        return auth;
    }

    private static MockServerWebExchange run(
            BffOriginFilter filter,
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
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName(
            "GIVEN the header is required WHEN a cookie caller sends an unsafe request without it SHOULD answer 403")
    void given_headerOn_when_missing_should_forbid(String method) {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                on,
                MockServerHttpRequest.method(HttpMethod.valueOf(method), "/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser(),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("csrf header required");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"0", "11", "true"})
    @DisplayName("GIVEN the header is required WHEN its value is not exactly the configured one SHOULD answer 403")
    void given_headerOn_when_wrongValue_should_forbid(String value) {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                on,
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HEADER, value)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser(),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("GIVEN the header is required WHEN the right value arrives under any name case SHOULD pass")
    void given_headerOn_when_rightValue_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                on,
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header("x-csrf", "1")
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser(),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN the header is required WHEN the header is right but the Origin is foreign SHOULD answer 403")
    void given_headerOn_when_foreignOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                on,
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HEADER, "1"),
                cookieUser(),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("GIVEN the header is required WHEN a form logout post carries it SHOULD skip the 415 rule")
    void given_headerOn_when_formWithHeader_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                on,
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HEADER, "1")
                        .header(HttpHeaders.CONTENT_TYPE, "text/plain"),
                cookieUser(),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN the header is required WHEN a logout form post lacks it SHOULD answer 403")
    void given_headerOn_when_logoutFormWithoutHeader_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                on,
                MockServerHttpRequest.post("/bff/logout")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded"),
                cookieUser(),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("GIVEN the header is required WHEN a safe request or a WebSocket upgrade has none SHOULD pass")
    void given_headerOn_when_safeOrUpgrade_should_pass() {
        AtomicBoolean safe = new AtomicBoolean();
        AtomicBoolean upgrade = new AtomicBoolean();

        run(on, MockServerHttpRequest.get("/v1/x").header(HttpHeaders.ORIGIN, EVE), cookieUser(), safe);
        run(
                on,
                MockServerHttpRequest.get("/graphql/ws")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.UPGRADE, "websocket"),
                cookieUser(),
                upgrade);

        assertThat(safe).isTrue();
        assertThat(upgrade).isTrue();
    }

    @Test
    @DisplayName("GIVEN the header is required WHEN a bearer caller posts without it SHOULD pass untouched")
    void given_headerOn_when_bearer_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(on, MockServerHttpRequest.post("/v1/x"), new TestingAuthenticationToken("bob", "n/a"), chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN the header is off WHEN a cookie caller posts without it SHOULD behave as before, the 415 rule included")
    void given_headerOff_when_noHeader_should_behaveAsBefore() {
        AtomicBoolean ok = new AtomicBoolean();
        AtomicBoolean form = new AtomicBoolean();

        run(
                off,
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser(),
                ok);
        MockServerWebExchange refused = run(
                off,
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "text/plain"),
                cookieUser(),
                form);

        assertThat(ok).isTrue();
        assertThat(form).isFalse();
        assertThat(refused.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
}
