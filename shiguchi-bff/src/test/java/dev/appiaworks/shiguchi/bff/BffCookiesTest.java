package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

@DisplayName("BffCookies: attributes of the session cookie and the per-login cookie")
class BffCookiesTest {

    private static BffCookies cookies(boolean secure, boolean persistent) {
        BffProperties properties = BffTestFixtures.properties();
        properties.getCookie().setSecure(secure);
        properties.getCookie().setPersistent(persistent);
        if (!secure) properties.getCookie().setName("gw-session");
        return new BffCookies(properties);
    }

    @Test
    @DisplayName(
            "GIVEN default settings WHEN the session cookie is built SHOULD be HttpOnly, Secure, Lax, Path=/ and a browser-session cookie")
    void given_defaults_when_sessionCookieBuilt_should_beHardenedSessionCookie() {
        ResponseCookie cookie = cookies(true, false).session("value");

        assertThat(cookie.getName()).isEqualTo("__Host-gw-session");
        assertThat(cookie.getValue()).isEqualTo("value");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getMaxAge().isNegative()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN cookie.persistent WHEN the session cookie is built SHOULD carry Max-Age equal to the absolute timeout")
    void given_persistent_when_sessionCookieBuilt_should_carryMaxAge() {
        assertThat(cookies(true, true).session("v").getMaxAge()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    @DisplayName("GIVEN a session cookie WHEN cleared SHOULD have the same name and path and Max-Age 0")
    void given_sessionCookie_when_cleared_should_expireIt() {
        ResponseCookie cleared = cookies(true, false).clearSession();

        assertThat(cleared.getName()).isEqualTo("__Host-gw-session");
        assertThat(cleared.getPath()).isEqualTo("/");
        assertThat(cleared.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cleared.isSecure()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a state WHEN the login cookie is built SHOULD be named __Host-gw-login whatever the state, Lax and login-timeout long")
    void given_state_when_loginCookieBuilt_should_useOneFixedName() {
        ResponseCookie cookie = cookies(true, false).login("abcdefghijkl");

        assertThat(cookie.getName()).isEqualTo("__Host-gw-login");
        assertThat(cookie.getValue()).isEqualTo("abcdefghijkl");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName(
            "GIVEN cookie.secure false WHEN the login cookie is built SHOULD drop the __Host- prefix and the Secure flag")
    void given_insecure_when_loginCookieBuilt_should_dropPrefixAndSecure() {
        ResponseCookie cookie = cookies(false, false).login("abcdefghijkl");

        assertThat(cookie.getName()).isEqualTo("gw-login");
        assertThat(cookie.isSecure()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a login cookie WHEN cleared SHOULD keep name and path and use Max-Age 0")
    void given_loginCookie_when_cleared_should_expireIt() {
        ResponseCookie cleared = cookies(true, false).clearLogin();

        assertThat(cleared.getName()).isEqualTo("__Host-gw-login");
        assertThat(cleared.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cleared.getPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("GIVEN a request with both cookies WHEN read SHOULD return their values, and null for a missing one")
    void given_requestWithCookies_when_read_should_returnValues() {
        BffCookies cookies = cookies(true, false);
        MockServerHttpRequest request = MockServerHttpRequest.get("/bff/session")
                .cookie(new HttpCookie("__Host-gw-session", "sess"))
                .cookie(new HttpCookie("__Host-gw-login", "abcdefghijkl"))
                .build();

        assertThat(cookies.sessionValue(request)).isEqualTo("sess");
        assertThat(cookies.loginValue(request)).isEqualTo("abcdefghijkl");
        assertThat(cookies.sessionValue(MockServerHttpRequest.get("/").build())).isNull();
    }
}
