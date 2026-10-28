package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("WebSocket token authenticator")
class PushTokenAuthenticatorTest {

    // ---- available() tests ----

    @Test
    @DisplayName("available returns false when decoder is null")
    void given_null_decoder_when_available_checked_should_return_false() {
        PushTokenAuthenticator auth = new PushTokenAuthenticator(null, null);

        assertThat(auth.available()).isFalse();
    }

    @Test
    @DisplayName("available returns true when decoder is present")
    void given_decoder_when_available_checked_should_return_true() {
        ReactiveJwtDecoder decoder = token -> Mono.error(new JwtException("not implemented"));
        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, null);

        assertThat(auth.available()).isTrue();
    }

    // ---- authenticate() tests ----

    @Test
    @DisplayName("authenticate with null decoder returns UNAUTHORIZED error")
    void given_null_decoder_when_authenticating_should_error_unauthorized() {
        PushTokenAuthenticator auth = new PushTokenAuthenticator(null, null);

        StepVerifier.create(auth.authenticate("token"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    @DisplayName("authenticate with null token returns UNAUTHORIZED error")
    void given_null_token_when_authenticating_should_error_unauthorized() {
        ReactiveJwtDecoder decoder = token -> Mono.error(new JwtException("should not be called"));
        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, null);

        StepVerifier.create(auth.authenticate(null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    @DisplayName("authenticate with blank token returns UNAUTHORIZED error")
    void given_blank_token_when_authenticating_should_error_unauthorized() {
        ReactiveJwtDecoder decoder = token -> Mono.error(new JwtException("should not be called"));
        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, null);

        StepVerifier.create(auth.authenticate("   "))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    @DisplayName("authenticate with valid JWT returns Authentication with subject as name")
    void given_valid_token_when_authenticating_should_return_authentication() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .claim("scope", "read")
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();

        ReactiveJwtDecoder decoder = token -> Mono.just(jwt);
        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, null);

        StepVerifier.create(auth.authenticate("t"))
                .assertNext(authentication -> {
                    assertThat(authentication.getName()).isEqualTo("alice");
                    assertThat(authentication.isAuthenticated()).isTrue();
                    assertThat(authentication.getAuthorities())
                            .extracting("authority")
                            .contains("SCOPE_read");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("authenticate with decoder that throws JwtException returns UNAUTHORIZED")
    void given_decoder_that_rejects_when_authenticating_should_error_unauthorized() {
        ReactiveJwtDecoder decoder = token -> Mono.error(new JwtException("invalid signature"));
        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, null);

        StepVerifier.create(auth.authenticate("bad-token"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(401);
                    assertThat(ex.getMessage()).contains("token rejected");
                })
                .verify();
    }

    @Test
    @DisplayName("authenticate with custom converter uses the converter")
    void given_custom_converter_when_authenticating_should_use_converter() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("bob")
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();

        ReactiveJwtDecoder decoder = token -> Mono.just(jwt);
        Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> converter =
                j -> Mono.just(new JwtAuthenticationToken(j, List.of()));

        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, converter);

        StepVerifier.create(auth.authenticate("t"))
                .assertNext(authentication -> {
                    assertThat(authentication.getName()).isEqualTo("bob");
                    assertThat(authentication.isAuthenticated()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a converter that rejects the token WHEN authenticating SHOULD fail with 401")
    void given_converterRejectsToken_when_authenticating_should_failUnauthenticated() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("bob").build();
        ReactiveJwtDecoder decoder = token -> Mono.just(jwt);
        var converter = new dev.appiaworks.shiguchi.security.JwtPrincipalConverter(
                j -> Mono.just(new JwtAuthenticationToken(j, List.of(), null)));

        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, converter);

        StepVerifier.create(auth.authenticate("t"))
                .expectErrorSatisfies(
                        e -> assertThat(((PushException) e).status().value()).isEqualTo(401))
                .verify();
    }

    // ---- expiry() tests ----

    @Test
    @DisplayName("expiry returns jwt expiresAt for JwtAuthenticationToken")
    void given_jwt_authentication_token_when_expiry_called_should_return_expires_at() {
        Instant expiresAt = Instant.parse("2026-12-31T23:59:59Z");
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .expiresAt(expiresAt)
                .build();

        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of());

        assertThat(PushTokenAuthenticator.expiry(auth)).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("expiry returns null for TestingAuthenticationToken")
    void given_testing_authentication_token_when_expiry_called_should_return_null() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("user", "pwd");
        auth.setAuthenticated(true);

        assertThat(PushTokenAuthenticator.expiry(auth)).isNull();
    }

    @Test
    @DisplayName("expiry returns null for null authentication")
    void given_null_authentication_when_expiry_called_should_return_null() {
        assertThat(PushTokenAuthenticator.expiry(null)).isNull();
    }

    @Test
    @DisplayName("expiry returns null for authentication without JWT")
    void given_authentication_without_jwt_when_expiry_called_should_return_null() {
        Authentication auth = new TestingAuthenticationToken("user", "pwd", "ROLE_USER");
        auth.setAuthenticated(true);

        assertThat(PushTokenAuthenticator.expiry(auth)).isNull();
    }

    @Test
    @DisplayName("authenticate with trimmed token calls decoder with trimmed value")
    void given_token_with_whitespace_when_authenticating_should_trim_before_decode() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("charlie")
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();

        // Decoder receives the trimmed token
        ReactiveJwtDecoder decoder = token -> {
            // Verify the token has been trimmed
            assertThat(token).isEqualTo("t");
            return Mono.just(jwt);
        };

        PushTokenAuthenticator auth = new PushTokenAuthenticator(decoder, null);

        StepVerifier.create(auth.authenticate("  t  "))
                .assertNext(authentication -> {
                    assertThat(authentication.getName()).isEqualTo("charlie");
                })
                .verifyComplete();
    }
}
