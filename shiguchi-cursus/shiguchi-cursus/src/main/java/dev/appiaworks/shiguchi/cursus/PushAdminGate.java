package dev.appiaworks.shiguchi.cursus;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** The role check behind the admin writes and the export: {@code admin.role}, with or without a prefix. */
final class PushAdminGate {

    private PushAdminGate() {}

    /** True when no role is configured, or {@code auth} holds it as {@code X}, {@code ROLE_X} or {@code SCOPE_X}. */
    static boolean holdsRole(Authentication auth, String role) {
        if (role == null || role.isBlank()) return true;
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String name = a.getAuthority();
            if (name.equals(role) || name.equals("ROLE_" + role) || name.equals("SCOPE_" + role)) return true;
        }
        return false;
    }
}
