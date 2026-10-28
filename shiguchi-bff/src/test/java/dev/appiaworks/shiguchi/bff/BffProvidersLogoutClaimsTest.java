package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("BffProviders: logout-token claim checks read the injected clock")
class BffProvidersLogoutClaimsTest {

    /** Far from the real time, so a check that read the system clock instead would answer differently. */
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Jwt logoutToken(Instant iat) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .audience(List.of("gateway"))
                .issuedAt(iat)
                .expiresAt(iat.plusSeconds(120))
                .claim("events", Map.of(BffProviders.BACKCHANNEL_LOGOUT_EVENT, Map.of()))
                .claim("sid", "sid-a")
                .jti("jti-1")
                .build();
    }

    @ParameterizedTest(name = "iat now+{0}s accepted={1}")
    @CsvSource({"0, true", "59, true", "61, false"})
    @DisplayName(
            "GIVEN a fixed clock WHEN the logout token iat is ahead of it SHOULD accept up to the 60 s skew and refuse past it")
    void given_fixedClock_when_iatAhead_should_honourSkew(long aheadSeconds, boolean accepted) {
        boolean ok = !BffProviders.logoutClaims(logoutToken(NOW.plusSeconds(aheadSeconds)), "gateway", CLOCK)
                .hasErrors();

        assertThat(ok).isEqualTo(accepted);
    }
}
