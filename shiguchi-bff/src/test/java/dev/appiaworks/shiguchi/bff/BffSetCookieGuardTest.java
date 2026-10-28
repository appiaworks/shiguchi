package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@DisplayName("BffSetCookieGuard: only the sign-in endpoints may set the session or a login cookie")
class BffSetCookieGuardTest {

    private static BffSetCookieGuard guard() {
        BffProperties properties = BffTestFixtures.properties();
        properties.getCookie().setSecure(false);
        properties.getCookie().setName("gw-session");
        return new BffSetCookieGuard(new BffCookies(properties), "/bff");
    }

    private static MockServerWebExchange run(String path, Consumer<ServerWebExchange> backend) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        guard().filter(exchange, e -> {
                    backend.accept(e);
                    return e.getResponse().setComplete();
                })
                .block();
        return exchange;
    }

    @Test
    @DisplayName(
            "GIVEN a proxied response WHEN a layer adds the session cookie, a login cookie and another cookie SHOULD keep only the other")
    void given_proxiedResponse_when_ownCookiesAdded_should_dropThem() {
        MockServerWebExchange exchange = run("/v1/x", e -> {
            e.getResponse().addCookie(ResponseCookie.from("gw-session", "evil").build());
            e.getResponse().addCookie(ResponseCookie.from("gw-login", "evil").build());
            e.getResponse().addCookie(ResponseCookie.from("theme", "dark").build());
        });

        assertThat(exchange.getResponse().getCookies()).containsOnlyKeys("theme");
    }

    @Test
    @DisplayName("GIVEN secure cookies WHEN a proxied response sets __Host-gw-login SHOULD drop it")
    void given_secureCookies_when_hostLoginCookieAdded_should_dropIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/x"));
        new BffSetCookieGuard(new BffCookies(BffTestFixtures.properties()), "/bff")
                .filter(exchange, e -> {
                    e.getResponse()
                            .addCookie(ResponseCookie.from("__Host-gw-login", "evil")
                                    .build());
                    e.getResponse()
                            .addCookie(ResponseCookie.from("theme", "dark").build());
                    return e.getResponse().setComplete();
                })
                .block();

        assertThat(exchange.getResponse().getCookies()).containsOnlyKeys("theme");
    }

    @Test
    @DisplayName(
            "GIVEN a proxied response WHEN a raw Set-Cookie header names the session cookie SHOULD drop only that header")
    void given_proxiedResponse_when_rawHeaderNamesSessionCookie_should_dropIt() {
        MockServerWebExchange exchange = run("/v1/x", e -> {
            e.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, "gw-session=evil; Path=/");
            e.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, "theme=dark; Path=/");
        });

        assertThat(exchange.getResponse().getHeaders().get(HttpHeaders.SET_COOKIE))
                .containsExactly("theme=dark; Path=/");
    }

    @Test
    @DisplayName("GIVEN a late commit action WHEN it adds the session cookie SHOULD still be dropped")
    void given_lateCommitAction_when_addsSessionCookie_should_dropIt() {
        MockServerWebExchange exchange = run(
                "/v1/x",
                e -> e.getResponse()
                        .beforeCommit(() -> Mono.fromRunnable(() -> e.getResponse()
                                .addCookie(ResponseCookie.from("gw-session", "evil")
                                        .build()))));

        assertThat(exchange.getResponse().getCookies()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a response under the BFF path WHEN it sets the session cookie SHOULD be left as written")
    void given_bffPath_when_sessionCookieSet_should_keepIt() {
        MockServerWebExchange exchange = run(
                "/bff/callback/idp",
                e -> e.getResponse()
                        .addCookie(ResponseCookie.from("gw-session", "real").build()));

        assertThat(exchange.getResponse().getCookies()).containsOnlyKeys("gw-session");
    }
}
