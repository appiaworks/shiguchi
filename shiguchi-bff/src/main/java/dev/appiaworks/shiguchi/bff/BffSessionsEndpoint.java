package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Actuator endpoint for BFF sessions: {@code GET /actuator/gateway-bff-sessions/{provider}/{subject}}
 * answers how many live sessions a user has at a provider and when each was created and last used
 * (no tokens, no handles); {@code DELETE} on the same path ends them all. Exposed only when
 * {@code management.endpoints.web.exposure.include} names {@code gateway-bff-sessions}. Both
 * methods need an authenticated caller (the gateway's security chain gates them, the way it gates
 * the {@code gateway-push} writes) and the role {@code shiguchi.bff.admin.role} names. Without a
 * role the endpoint refuses every caller (403): there is no "any authenticated caller" default.
 */
@Endpoint(id = "gateway-bff-sessions")
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public class BffSessionsEndpoint {

    private static final Duration BLOCK = Duration.ofSeconds(30);

    private final BffSessions sessions;
    private final BffProperties properties;

    public BffSessionsEndpoint(BffSessions sessions, BffProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @ReadOperation
    public WebEndpointResponse<Map<String, Object>> read(
            @Selector String provider, @Selector String subject, SecurityContext ctx) {
        WebEndpointResponse<Map<String, Object>> refused = gate(ctx);
        if (refused != null) return refused;
        if (!sessions.known(provider)) return unknownProvider(provider);
        List<BffSession> found =
                sessions.sessions(provider, subject).collectList().block(BLOCK);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (found != null) {
            found.stream().sorted(Comparator.comparing(BffSession::created)).forEach(s -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("application", s.application());
                row.put("created", s.created().toString());
                row.put("lastSeen", s.lastSeen().toString());
                rows.add(row);
            });
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider);
        body.put("subject", subject);
        body.put("count", rows.size());
        body.put("sessions", rows);
        return new WebEndpointResponse<>(body, 200);
    }

    @DeleteOperation
    public WebEndpointResponse<Map<String, Object>> revoke(
            @Selector String provider, @Selector String subject, SecurityContext ctx) {
        WebEndpointResponse<Map<String, Object>> refused = gate(ctx);
        if (refused != null) return refused;
        if (!sessions.known(provider)) return unknownProvider(provider);
        Integer ended = sessions.revokeSubject(provider, subject).block(BLOCK);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider);
        body.put("subject", subject);
        body.put("revoked", ended == null ? 0 : ended);
        return new WebEndpointResponse<>(body, 200);
    }

    private WebEndpointResponse<Map<String, Object>> unknownProvider(String provider) {
        return new WebEndpointResponse<>(Map.of("error", "unknown_provider", "provider", provider), 404);
    }

    /** The role gate; null when the caller may proceed. */
    private WebEndpointResponse<Map<String, Object>> gate(SecurityContext ctx) {
        String role = properties.getAdmin().getRole();
        if (role == null || role.isBlank()) {
            return new WebEndpointResponse<>(
                    Map.of(
                            "error",
                            "admin role not configured",
                            "message",
                            "set shiguchi.bff.admin.role to use this endpoint"),
                    403);
        }
        if (ctx != null && ctx.getPrincipal() instanceof Authentication auth && holdsRole(auth, role)) {
            return null;
        }
        return new WebEndpointResponse<>(
                Map.of("error", "forbidden", "message", "role " + role + " required (shiguchi.bff.admin.role)"), 403);
    }

    /** True when {@code auth} holds the role as {@code X}, {@code ROLE_X} or {@code SCOPE_X}. */
    static boolean holdsRole(Authentication auth, String role) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            String name = a.getAuthority();
            if (name.equals(role) || name.equals("ROLE_" + role) || name.equals("SCOPE_" + role)) return true;
        }
        return false;
    }
}
