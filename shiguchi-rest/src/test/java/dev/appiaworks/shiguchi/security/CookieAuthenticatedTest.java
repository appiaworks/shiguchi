package dev.appiaworks.shiguchi.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class CookieAuthenticatedTest {

    @Test
    @DisplayName("GIVEN a cookie caller WHEN reading handle and application SHOULD return them")
    void given_cookie_caller_when_reading_should_return_values() {
        var auth = new TestingAuthenticationToken("u", "n");
        auth.setDetails(new CookieAuthenticated(null, "key-1", "web"));

        assertThat(CookieAuthenticated.isCookieAuthenticated(auth)).isTrue();
        assertThat(CookieAuthenticated.handle(auth)).isEqualTo("key-1");
        assertThat(CookieAuthenticated.application(auth)).isEqualTo("web");
    }

    @Test
    @DisplayName("GIVEN a wrapped third-party caller WHEN reading SHOULD be cookie-authenticated with null handle")
    void given_wrapped_caller_when_reading_should_have_null_handle() {
        var auth = new TestingAuthenticationToken("u", "n");
        auth.setDetails(new CookieAuthenticated("x", null, null));

        assertThat(CookieAuthenticated.isCookieAuthenticated(auth)).isTrue();
        assertThat(CookieAuthenticated.handle(auth)).isNull();
        assertThat(CookieAuthenticated.application(auth)).isNull();
    }

    @Test
    @DisplayName("GIVEN a bearer caller or null WHEN reading SHOULD report not cookie-authenticated")
    void given_bearer_or_null_when_reading_should_be_false() {
        var auth = new TestingAuthenticationToken("u", "n");

        assertThat(CookieAuthenticated.isCookieAuthenticated(auth)).isFalse();
        assertThat(CookieAuthenticated.handle(auth)).isNull();
        assertThat(CookieAuthenticated.application(null)).isNull();
        assertThat(CookieAuthenticated.isCookieAuthenticated(null)).isFalse();
    }
}
