package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.web.server.ServerHttpSecurity;

@DisplayName("BffSecurityCustomizer: whether the module declares cookie authentication")
class BffSecurityCustomizerTest {

    private static final BffSecurityContextRepository REPOSITORY = new BffSecurityContextRepository(null);

    @Test
    @DisplayName("GIVEN a session authenticator is configured WHEN asked whether it authenticates SHOULD be true")
    void given_sessionAuthenticator_when_asked_should_authenticateTrue() {
        assertThat(new BffSecurityCustomizer(REPOSITORY, true, "/bff", null).authenticates())
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN no session authenticator is configured WHEN asked whether it authenticates SHOULD be false")
    void given_noSessionAuthenticator_when_asked_should_authenticateFalse() {
        assertThat(new BffSecurityCustomizer(REPOSITORY, false, "/bff", null).authenticates())
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a path WHEN asked for chain paths SHOULD return everything under it")
    void given_path_when_askedForChainPaths_should_returnEverythingUnderIt() {
        assertThat(new BffSecurityCustomizer(REPOSITORY, true, "/auth", null).chainPaths())
                .containsExactly("/auth/**");
    }

    @Test
    @DisplayName(
            "GIVEN a path WHEN authorizing SHOULD permit exactly the sign-in and back-channel logout endpoints under it")
    void given_path_when_authorizing_should_permitSignInAndBackchannelEndpoints() {
        ServerHttpSecurity.AuthorizeExchangeSpec spec = mock(ServerHttpSecurity.AuthorizeExchangeSpec.class);
        ServerHttpSecurity.AuthorizeExchangeSpec.Access access =
                mock(ServerHttpSecurity.AuthorizeExchangeSpec.Access.class);
        when(spec.pathMatchers(
                        "/auth/login/**",
                        "/auth/callback/**",
                        "/auth/providers",
                        "/auth/session",
                        "/auth/logout",
                        "/auth/backchannel-logout/**"))
                .thenReturn(access);

        new BffSecurityCustomizer(REPOSITORY, true, "/auth", null).authorize(spec);

        verify(access).permitAll();
    }
}
