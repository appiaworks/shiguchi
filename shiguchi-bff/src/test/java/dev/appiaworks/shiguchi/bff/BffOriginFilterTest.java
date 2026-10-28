package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

@DisplayName("BffOriginFilter: CSRF and content-type rules for cookie-authenticated requests")
class BffOriginFilterTest {

    private static final String EVE = "https://eve.example.com";
    private static final String FIN = "https://fin.example.com";

    private final BffOriginFilter filter =
            new BffOriginFilter(new BffApplications(BffTestFixtures.properties()), "/bff", "/graphql");

    private static Authentication cookieUser(String application) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "n/a");
        auth.setDetails(new CookieAuthenticated(null, "handle", application));
        return auth;
    }

    private static Authentication bearerUser() {
        return new TestingAuthenticationToken("bob", "n/a");
    }

    /** Runs the filter for {@code auth} and returns the exchange; {@code chained} tells whether the request went on. */
    private MockServerWebExchange run(
            MockServerHttpRequest.BaseBuilder<?> request, Authentication auth, AtomicBoolean chained) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        filter.filter(exchange, e -> {
                    chained.set(true);
                    return Mono.empty();
                })
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();
        return exchange;
    }

    private static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }

    @Test
    @DisplayName("GIVEN a bearer caller WHEN posting with no Origin SHOULD pass untouched")
    void given_bearer_when_postWithoutOrigin_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(MockServerHttpRequest.post("/v1/x").header(HttpHeaders.CONTENT_TYPE, "text/plain"), bearerUser(), chained);

        assertThat(chained).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "HEAD"})
    @DisplayName("GIVEN a cookie caller of eve WHEN a read carries eve's own Origin SHOULD pass")
    void given_cookieOfEve_when_readWithOwnOrigin_should_pass(String method) {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), "/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE),
                cookieUser("eve"),
                chained);

        assertThat(chained).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "HEAD"})
    @DisplayName("GIVEN a cookie caller of eve WHEN a read carries application fin's Origin SHOULD answer 403")
    void given_cookieOfEve_when_readWithOtherApplicationsOrigin_should_forbid(String method) {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), "/v1/x")
                        .header(HttpHeaders.ORIGIN, FIN),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        if (method.equals("GET")) assertThat(body(exchange)).isEqualTo("{\"error\":\"origin not allowed\"}");
    }

    @Test
    @DisplayName("GIVEN a cookie caller of eve WHEN a read carries Origin null SHOULD answer 403")
    void given_cookieOfEve_when_readWithNullOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                MockServerHttpRequest.get("/bff/session").header(HttpHeaders.ORIGIN, "null"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GIVEN a bearer caller WHEN reading with any Origin SHOULD pass untouched")
    void given_bearer_when_readWithAnyOrigin_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.get("/v1/x").header(HttpHeaders.ORIGIN, "https://evil.example.com"),
                bearerUser(),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN a CORS preflight without a cookie WHEN it names another application's Origin SHOULD pass")
    void given_preflightWithoutCookie_when_foreignOrigin_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.options("/v1/x")
                        .header(HttpHeaders.ORIGIN, FIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"),
                bearerUser(),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN a cookie caller WHEN reading with GET and no Origin SHOULD pass")
    void given_cookie_when_get_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(MockServerHttpRequest.get("/v1/x"), cookieUser("eve"), chained);

        assertThat(chained).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("GIVEN a cookie caller WHEN a state-changing method has no Origin SHOULD answer 403")
    void given_cookie_when_noOrigin_should_forbid(String method) {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), "/v1/x"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(exchange)).isEqualTo("{\"error\":\"origin not allowed\"}");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "null",
                "https://fin.example.com",
                "https://eve.example.com.evil.net",
                "https://eve.example.com:8443",
                "http://eve.example.com",
                "https://evil.example.com",
                "https://eve.example.com/path"
            })
    @DisplayName(
            "GIVEN a cookie caller of eve WHEN Origin is null, another application's or a look-alike SHOULD answer 403")
    void given_cookieOfEve_when_foreignOrigin_should_forbid(String origin) {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GIVEN a cookie caller of eve WHEN Origin is eve's own, in any case SHOULD pass")
    void given_cookieOfEve_when_ownOrigin_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, "HTTPS://Eve.Example.com:443")
                        .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN a cookie caller of eve WHEN Origin is application fin's SHOULD answer 403")
    void given_cookieOfEve_when_otherApplicationsOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, FIN)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller whose application is unknown WHEN Origin is any configured application's SHOULD pass")
    void given_unknownApplication_when_configuredOrigin_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, FIN)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser(null),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN a cookie caller whose application is unknown WHEN Origin is foreign SHOULD answer 403")
    void given_unknownApplication_when_foreignOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser(null),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("GIVEN a cookie caller of an application no longer configured WHEN posting SHOULD answer 403")
    void given_removedApplication_when_posting_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser("gone"),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("GIVEN a cookie WebSocket handshake WHEN Origin is bad SHOULD answer 403")
    void given_webSocketUpgrade_when_badOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                MockServerHttpRequest.get("/push/ws")
                        .header(HttpHeaders.UPGRADE, "websocket")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GIVEN a cookie WebSocket handshake WHEN Origin is missing SHOULD answer 403")
    void given_webSocketUpgrade_when_noOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(MockServerHttpRequest.get("/push/ws").header(HttpHeaders.UPGRADE, "WebSocket"), cookieUser("eve"), chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("GIVEN a cookie WebSocket handshake WHEN Origin is the application's SHOULD pass")
    void given_webSocketUpgrade_when_goodOrigin_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.get("/push/ws")
                        .header(HttpHeaders.UPGRADE, "websocket")
                        .header(HttpHeaders.ORIGIN, EVE),
                cookieUser("eve"),
                chained);

        assertThat(chained).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "text/plain",
                "text/plain;charset=UTF-8",
                "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x"
            })
    @DisplayName(
            "GIVEN a cookie caller with the right Origin WHEN the REST body has a no-preflight content type SHOULD answer 415")
    void given_cookie_when_simpleContentType_should_answer415(String contentType) {
        AtomicBoolean chained = new AtomicBoolean();

        MockServerWebExchange exchange = run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, contentType),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("GIVEN a cookie caller with the right Origin WHEN the REST body has no content type SHOULD answer 415")
    void given_cookie_when_bodyWithoutContentType_should_answer415() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/v1/x")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_LENGTH, "5"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller with the right Origin WHEN posting with no body and no content type SHOULD pass")
    void given_cookie_when_emptyPost_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(MockServerHttpRequest.post("/v1/x").header(HttpHeaders.ORIGIN, EVE), cookieUser("eve"), chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN a cookie caller with the right Origin WHEN posting gRPC-Web SHOULD pass")
    void given_cookie_when_grpcWeb_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/lib.Echo/Get")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller with the right Origin WHEN posting to GraphQL with a plain content type SHOULD pass the filter")
    void given_cookie_when_graphqlPath_should_skipContentTypeRule() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/graphql")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "text/plain"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN a cookie caller WHEN posting to GraphQL with a foreign Origin SHOULD answer 403")
    void given_cookie_when_graphqlForeignOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/graphql")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.CONTENT_TYPE, "application/json"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
    }

    @Test
    @DisplayName("GIVEN a cookie caller WHEN posting a form to logout with the right Origin SHOULD pass")
    void given_cookie_when_logoutForm_should_pass() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/bff/logout")
                        .header(HttpHeaders.ORIGIN, EVE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isTrue();
    }

    @Test
    @DisplayName("GIVEN a cookie caller WHEN posting to logout with a foreign Origin SHOULD answer 403")
    void given_cookie_when_logoutForeignOrigin_should_forbid() {
        AtomicBoolean chained = new AtomicBoolean();

        run(
                MockServerHttpRequest.post("/bff/logout")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded"),
                cookieUser("eve"),
                chained);

        assertThat(chained).isFalse();
    }
}
