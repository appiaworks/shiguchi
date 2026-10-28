package dev.appiaworks.shiguchi.bff.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BffSessionKeysTest {

    @Test
    @DisplayName("GIVEN a cookie value WHEN deriving the key twice SHOULD be deterministic and differ from the value")
    void given_cookie_value_when_deriving_key_should_be_deterministic_and_differ() {
        String value = BffSessionKeys.newCookieValue();

        assertThat(BffSessionKeys.key(value))
                .isEqualTo(BffSessionKeys.key(value))
                .isNotEqualTo(value);
    }

    @Test
    @DisplayName("GIVEN fresh values WHEN generated SHOULD be 43 unpadded Base64url characters and unique")
    void given_fresh_values_when_generated_should_be_url_safe_and_unique() {
        String a = BffSessionKeys.newCookieValue();

        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(BffSessionKeys.newCookieValue()).isNotEqualTo(a);
        assertThat(BffSessionKeys.newCodeVerifier()).matches("[A-Za-z0-9_-]{43}");
        assertThat(BffSessionKeys.newState()).matches("[A-Za-z0-9_-]{43}");
        assertThat(BffSessionKeys.newNonce()).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("GIVEN the RFC 7636 appendix B verifier WHEN computing the S256 challenge SHOULD match the RFC")
    void given_rfc7636_verifier_when_challenging_should_match_rfc() {
        assertThat(BffSessionKeys.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
