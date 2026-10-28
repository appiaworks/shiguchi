package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * The per-namespace subscribe and publish checks of {@link PushProperties.Namespace}. The caller
 * is whatever the security chain put on the exchange: a JWT, or nothing. Failures are
 * {@link PushException}s with 401 (no principal where one is needed) or 403.
 *
 * <p>{@code AUTHORIZER} hands the decision to the application's {@link GatewayAuthorizer} with
 * {@code route == null} and the topic in {@code arguments} ({@code push.operation},
 * {@code push.topic}, {@code push.namespace}); an authorizer that only knows routes must handle
 * that.
 */
public final class TopicAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(TopicAuthorizer.class);

    private final GatewayAuthorizer authorizer;

    public TopicAuthorizer(GatewayAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    public Mono<Void> authorizeSubscribe(Authentication auth, Topic topic, PushProperties.Namespace ns) {
        return switch (ns.getAuth()) {
            case NONE -> Mono.empty();
            case AUTHENTICATED -> requireAuthenticated(auth, topic);
            case ROLE -> requireRole(auth, topic, ns.getRoles());
            case PRINCIPAL_MATCH -> requirePrincipalMatch(auth, topic, ns.getPrincipalClaim());
            case CLAIM -> requireClaim(auth, topic, ns.getClaim());
            case AUTHORIZER -> delegate(auth, topic, "SUBSCRIBE");
        };
    }

    public Mono<Void> authorizePublish(Authentication auth, Topic topic, PushProperties.Namespace ns) {
        return switch (ns.getPublishAuth()) {
            case NONE -> Mono.empty();
            case AUTHENTICATED -> requireAuthenticated(auth, topic);
            case ROLE -> requireRole(auth, topic, ns.getPublishRoles());
            case CLAIM -> requireClaim(auth, topic, ns.getPublishClaim());
            case AUTHORIZER -> delegate(auth, topic, "PUBLISH");
        };
    }

    static boolean isAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    private static Mono<Void> requireAuthenticated(Authentication auth, Topic topic) {
        return isAuthenticated(auth)
                ? Mono.empty()
                : Mono.error(PushException.unauthenticated("authentication required for " + topic));
    }

    private static Mono<Void> requireRole(Authentication auth, Topic topic, List<String> roles) {
        if (!isAuthenticated(auth))
            return Mono.error(PushException.unauthenticated("authentication required for " + topic));
        Collection<? extends GrantedAuthority> held = auth.getAuthorities();
        for (String role : roles) {
            for (GrantedAuthority a : held) {
                String name = a.getAuthority();
                if (name.equals(role) || name.equals("ROLE_" + role) || name.equals("SCOPE_" + role)) {
                    return Mono.empty();
                }
            }
        }
        return Mono.error(PushException.forbidden("none of " + roles + " held, for " + topic));
    }

    /**
     * The topic name is the caller's name, or starts with it and {@code /}. The name is the
     * principal's, or the string value of {@code principalClaim} when the namespace sets one. A
     * name a topic cannot carry ({@code auth0|abc}) never matches.
     */
    private static Mono<Void> requirePrincipalMatch(Authentication auth, Topic topic, String principalClaim) {
        if (!isAuthenticated(auth))
            return Mono.error(PushException.unauthenticated("authentication required for " + topic));
        String me;
        if (principalClaim == null || principalClaim.isBlank()) {
            me = auth.getName();
        } else {
            Object value = claimValue(auth, principalClaim.trim());
            if (!(value instanceof String s) || s.isEmpty()) {
                return Mono.error(PushException.forbidden(
                        "claim " + principalClaim.trim() + " is missing or not a string, for " + topic));
            }
            me = s;
        }
        if (me != null
                && !me.isEmpty()
                && (topic.name().equals(me) || topic.name().startsWith(me + "/"))) {
            return Mono.empty();
        }
        if (me != null && !Topic.isValidName(me)) {
            log.debug("Principal '{}' cannot be a topic name, so no topic matches it", me);
        }
        return Mono.error(PushException.forbidden(topic + " is not " + me + "'s topic"));
    }

    private static Mono<Void> requireClaim(Authentication auth, Topic topic, String claimPath) {
        if (!isAuthenticated(auth))
            return Mono.error(PushException.unauthenticated("authentication required for " + topic));
        Object claim = claimValue(auth, claimPath);
        if (claim instanceof Collection<?> patterns) {
            for (Object p : patterns) {
                if (p instanceof String s && TopicPattern.parse(s).matches(topic)) return Mono.empty();
            }
        } else if (claim instanceof String s && TopicPattern.parse(s).matches(topic)) {
            return Mono.empty();
        }
        return Mono.error(PushException.forbidden("claim " + claimPath + " grants nothing for " + topic));
    }

    /** The claim at a dotted path in the caller's JWT, or null when there is no JWT or no such claim. */
    static Object claimValue(Authentication auth, String claimPath) {
        Object principal = auth.getPrincipal();
        Map<String, Object> claims;
        if (principal instanceof Jwt jwt) {
            claims = jwt.getClaims();
        } else if (auth.getCredentials() instanceof Jwt jwt) {
            claims = jwt.getClaims();
        } else {
            return null;
        }
        Object current = claims;
        for (String part : claimPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
            if (current == null) return null;
        }
        return current;
    }

    private Mono<Void> delegate(Authentication auth, Topic topic, String operation) {
        if (authorizer == null) {
            return Mono.error(new IllegalStateException(
                    "namespace '" + topic.namespace() + "' uses AUTHORIZER but there is no GatewayAuthorizer bean"));
        }
        Map<String, Object> args = Map.of(
                "push.operation", operation,
                "push.topic", topic.toString(),
                "push.namespace", topic.namespace());
        return authorizer
                .authorize(new AuthorizationRequest(null, auth, args, null))
                .onErrorMap(
                        AccessDeniedException.class,
                        e -> isAuthenticated(auth)
                                ? PushException.forbidden(e.getMessage())
                                : PushException.unauthenticated(e.getMessage()));
    }
}
