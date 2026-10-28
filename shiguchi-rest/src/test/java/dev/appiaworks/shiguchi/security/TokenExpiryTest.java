package dev.appiaworks.shiguchi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TokenExpiryTest {

    private static final Instant EXP = Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);

    private static Jwt jwt(Instant exp) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("alice");
        if (exp != null) builder.issuedAt(exp.minusSeconds(60)).expiresAt(exp);
        else builder.claim("sub", "alice");
        return builder.build();
    }

    @Test
    @DisplayName("GIVEN a JWT authentication WHEN reading the expiry SHOULD return the exp claim")
    void given_jwt_authentication_when_reading_should_return_exp() {
        assertThat(TokenExpiry.expiry(new JwtAuthenticationToken(jwt(EXP)))).isEqualTo(EXP);
    }

    @Test
    @DisplayName("GIVEN a JWT only in the credentials WHEN reading the expiry SHOULD return the exp claim")
    void given_jwt_in_credentials_when_reading_should_return_exp() {
        assertThat(TokenExpiry.expiry(new TestingAuthenticationToken("alice", jwt(EXP))))
                .isEqualTo(EXP);
    }

    @Test
    @DisplayName("GIVEN a JWT without exp WHEN reading the expiry SHOULD return null")
    void given_jwt_without_exp_when_reading_should_return_null() {
        assertThat(TokenExpiry.expiry(new JwtAuthenticationToken(jwt(null)))).isNull();
    }

    @Test
    @DisplayName("GIVEN a non-JWT caller or null WHEN reading the expiry SHOULD return null")
    void given_non_jwt_or_null_when_reading_should_return_null() {
        assertThat(TokenExpiry.expiry(new TestingAuthenticationToken("u", "n"))).isNull();
        assertThat(TokenExpiry.expiry(null)).isNull();
    }
}
