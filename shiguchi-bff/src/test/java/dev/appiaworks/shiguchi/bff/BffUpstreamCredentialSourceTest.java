package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.gateway.UpstreamCredentialSource;
import dev.appiaworks.shiguchi.gateway.UpstreamCredentialSourceContract;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@DisplayName("BffUpstreamCredentialSource: the session token sent upstream for a cookie caller")
class BffUpstreamCredentialSourceTest extends UpstreamCredentialSourceContract {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static JwtAuthenticationToken jwtCaller() {
        Jwt jwt = Jwt.withTokenValue("alice-token")
                .header("alg", "none")
                .subject("alice")
                .build();
        return new JwtAuthenticationToken(jwt, List.of(), "alice");
    }

    @Override
    protected UpstreamCredentialSource source() {
        return new BffUpstreamCredentialSource();
    }

    @Override
    protected Authentication caller() {
        JwtAuthenticationToken caller = jwtCaller();
        caller.setDetails(new CookieAuthenticated(null, null, null));
        return caller;
    }

    @Test
    @DisplayName("GIVEN a cookie-authenticated JWT caller WHEN asked SHOULD return its token as a Bearer value")
    void given_cookieAuthenticatedJwt_when_asked_should_returnBearerToken() {
        String value = new BffUpstreamCredentialSource()
                .authorization(request(), caller())
                .block(TIMEOUT);

        assertThat(value).isEqualTo("Bearer alice-token");
    }

    @Test
    @DisplayName("GIVEN a JWT caller without the cookie marker WHEN asked SHOULD return nothing")
    void given_jwtCallerWithoutCookieMarker_when_asked_should_returnNothing() {
        String value = new BffUpstreamCredentialSource()
                .authorization(request(), jwtCaller())
                .block(TIMEOUT);

        assertThat(value).isNull();
    }

    @Test
    @DisplayName("GIVEN a non-JWT caller carrying the cookie marker WHEN asked SHOULD return nothing")
    void given_nonJwtCallerWithCookieMarker_when_asked_should_returnNothing() {
        TestingAuthenticationToken caller = new TestingAuthenticationToken("alice", null, List.of());
        caller.setAuthenticated(true);
        caller.setDetails(new CookieAuthenticated(null, null, null));

        String value = new BffUpstreamCredentialSource()
                .authorization(request(), caller)
                .block(TIMEOUT);

        assertThat(value).isNull();
    }
}
