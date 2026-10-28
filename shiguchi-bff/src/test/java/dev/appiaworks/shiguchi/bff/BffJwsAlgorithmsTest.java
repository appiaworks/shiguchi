package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName("BffProviders: the per-provider JWS algorithm allow-list on access, ID and logout tokens")
class BffJwsAlgorithmsTest {

    private static StubIssuer issuer;

    private BffProperties properties;

    @BeforeAll
    static void startIssuer() {
        issuer = StubIssuer.start();
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void setUp() {
        issuer.reset();
        properties = BffTestFixtures.properties();
        BffProperties.Provider idp = properties.getProviders().get("idp");
        idp.setIssuerUri(issuer.issuer());
        idp.setAuthorizationUri(null);
        idp.setTokenUri(null);
        idp.setJwkSetUri(null);
        idp.setEndSessionUri(null);
        idp.setClientId(issuer.clientId());
        idp.setClientSecret("secret");
    }

    private BffProviders providers(String... algorithms) {
        if (algorithms.length > 0) {
            properties.getProviders().get("idp").setJwsAlgorithms(List.of(algorithms));
        }
        return new BffProviders(properties, WebClient.builder());
    }

    private String idToken() {
        Instant now = Instant.now();
        return issuer.sign(Map.of(
                "iss", issuer.issuer(),
                "sub", "alice",
                "aud", issuer.clientId(),
                "azp", issuer.clientId(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(300).getEpochSecond()));
    }

    private String logoutToken() {
        Instant now = Instant.now();
        return issuer.signAs(
                Map.of(
                        "iss", issuer.issuer(),
                        "aud", issuer.clientId(),
                        "iat", now.getEpochSecond(),
                        "exp", now.plusSeconds(120).getEpochSecond(),
                        "jti", "jti-1",
                        "sid", "sid-a",
                        "events", Map.of(BffProviders.BACKCHANNEL_LOGOUT_EVENT, Map.of())),
                "logout+jwt");
    }

    private static Jwt decode(org.springframework.security.oauth2.jwt.ReactiveJwtDecoder decoder, String token) {
        return decoder.decode(token).block();
    }

    @Test
    @DisplayName("GIVEN the default algorithms WHEN an RS256 access and ID token arrive SHOULD accept both")
    void given_defaultAlgorithms_when_rs256Tokens_should_accept() {
        BffProviders providers = providers();

        assertThat(decode(providers.accessDecoder("idp").block(), issuer.accessToken("alice"))
                        .getSubject())
                .isEqualTo("alice");
        assertThat(decode(providers.idTokenDecoder("idp").block(), idToken()).getSubject())
                .isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN the default algorithms WHEN an ES256 access token arrives SHOULD refuse it")
    void given_defaultAlgorithms_when_es256AccessToken_should_refuse() {
        issuer.signingAlgorithm("ES256");
        BffProviders providers = providers();

        assertThatThrownBy(() -> decode(providers.accessDecoder("idp").block(), issuer.accessToken("alice")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("GIVEN the default algorithms WHEN an ES256 ID token arrives SHOULD refuse it")
    void given_defaultAlgorithms_when_es256IdToken_should_refuse() {
        issuer.signingAlgorithm("ES256");
        BffProviders providers = providers();

        assertThatThrownBy(() -> decode(providers.idTokenDecoder("idp").block(), idToken()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("GIVEN jws-algorithms ES256 WHEN ES256 access and ID tokens arrive SHOULD accept both")
    void given_es256Configured_when_es256Tokens_should_accept() {
        issuer.signingAlgorithm("ES256");
        BffProviders providers = providers("ES256");

        assertThat(decode(providers.accessDecoder("idp").block(), issuer.accessToken("alice"))
                        .getSubject())
                .isEqualTo("alice");
        assertThat(decode(providers.idTokenDecoder("idp").block(), idToken()).getSubject())
                .isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN jws-algorithms ES256 only WHEN an RS256 access and ID token arrive SHOULD refuse both")
    void given_es256Only_when_rs256Tokens_should_refuse() {
        BffProviders providers = providers("ES256");

        assertThatThrownBy(() -> decode(providers.accessDecoder("idp").block(), issuer.accessToken("alice")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decode(providers.idTokenDecoder("idp").block(), idToken()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("GIVEN jws-algorithms ES256 WHEN an ES256 logout token arrives SHOULD accept it")
    void given_es256Configured_when_es256LogoutToken_should_accept() {
        issuer.signingAlgorithm("ES256");
        BffProviders providers = providers("ES256");

        assertThat(decode(providers.logoutTokenDecoder("idp").block(), logoutToken())
                        .getClaimAsString("sid"))
                .isEqualTo("sid-a");
    }

    @Test
    @DisplayName("GIVEN the default algorithms WHEN an ES256 logout token arrives SHOULD refuse it")
    void given_defaultAlgorithms_when_es256LogoutToken_should_refuse() {
        issuer.signingAlgorithm("ES256");
        BffProviders providers = providers();

        assertThatThrownBy(() -> decode(providers.logoutTokenDecoder("idp").block(), logoutToken()))
                .isInstanceOf(JwtException.class);
    }
}
