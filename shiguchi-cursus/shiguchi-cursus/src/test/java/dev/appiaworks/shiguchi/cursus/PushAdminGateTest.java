package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("Push admin role gate")
class PushAdminGateTest {

    private static final String ROLE = "PUSH_ADMIN";

    @Test
    @DisplayName("GIVEN no admin role configured WHEN checked without any authentication SHOULD allow")
    void given_null_role_when_checked_without_authentication_should_allow() {
        assertThat(PushAdminGate.holdsRole(null, null)).isTrue();
    }

    @Test
    @DisplayName("GIVEN a blank admin role WHEN checked SHOULD allow")
    void given_blank_role_when_checked_should_allow() {
        assertThat(PushAdminGate.holdsRole(null, "   ")).isTrue();
    }

    @Test
    @DisplayName("GIVEN an admin role configured WHEN checked without authentication SHOULD deny")
    void given_role_configured_when_checked_without_authentication_should_deny() {
        assertThat(PushAdminGate.holdsRole(null, ROLE)).isFalse();
    }

    @Test
    @DisplayName("GIVEN an authority equal to the admin role WHEN checked SHOULD allow")
    void given_authority_equal_to_role_when_checked_should_allow() {
        assertThat(PushAdminGate.holdsRole(auth(ROLE), ROLE)).isTrue();
    }

    @Test
    @DisplayName("GIVEN a ROLE_ prefixed authority WHEN checked SHOULD allow")
    void given_role_prefixed_authority_when_checked_should_allow() {
        assertThat(PushAdminGate.holdsRole(auth("ROLE_" + ROLE), ROLE)).isTrue();
    }

    @Test
    @DisplayName("GIVEN a SCOPE_ prefixed authority WHEN checked SHOULD allow")
    void given_scope_prefixed_authority_when_checked_should_allow() {
        assertThat(PushAdminGate.holdsRole(auth("SCOPE_" + ROLE), ROLE)).isTrue();
    }

    @Test
    @DisplayName("GIVEN only an unrelated authority WHEN checked SHOULD deny")
    void given_other_authority_when_checked_should_deny() {
        assertThat(PushAdminGate.holdsRole(auth("ROLE_VIEWER"), ROLE)).isFalse();
    }

    private static Authentication auth(String authority) {
        return new UsernamePasswordAuthenticationToken("u", "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }
}
