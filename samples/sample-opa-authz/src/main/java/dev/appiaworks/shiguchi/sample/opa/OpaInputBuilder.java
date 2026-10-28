package dev.appiaworks.shiguchi.sample.opa;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.routing.AuthzRuleDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Builds the JSON document OPA evaluates its policy against.
 *
 * <h2>Why this differs from the OpenFGA sample</h2>
 *
 * OpenFGA asks a narrow question — {@code check(user, relation, object)} — so that sample resolves
 * each {@code authz} rule into a concrete {@code (relation, object)} pair and issues one call per
 * pair. OPA is the opposite shape: it takes an arbitrary {@code input} document and returns
 * whatever the policy computes from it. Nothing is resolved on the gateway side; the policy decides
 * what matters.
 *
 * <p>That is why this sample reads {@link dev.appiaworks.shiguchi.routing.RouteDefinition#getAuthzRules()}
 * <em>raw</em> rather than going through {@code AuthzRuleResolver}. The resolver's output —
 * {@code (relation, object)} pairs — is Zanzibar-shaped, and flattening rules into it here would
 * throw away information a Rego policy may legitimately want (the object type separately from the
 * id, the field the id came from, the full method name). Passing the rules through structurally
 * keeps the policy free to use as much or as little as it likes.
 *
 * <h2>Document shape</h2>
 *
 * <pre>
 * {
 *   "subject":  { "id": "alice", "authenticated": true,
 *                 "authorities": ["SCOPE_orders:read"],
 *                 "claims": { "groups": ["auditors"], ... } },
 *   "action":   { "service": "orders.OrderService", "method": "GetOrder",
 *                 "http_method": "GET", "http_path": "/v1/orders/{orderId}",
 *                 "graphql_field": null },
 *   "resource": { "arguments": { "orderId": "42" },
 *                 "authz": [ { "object_type": "order", "relation": "viewer",
 *                              "id_field": "order_id", "id": "42" } ] },
 *   "required_scopes": ["orders:read"]
 * }
 * </pre>
 *
 * Each entry in {@code resource.authz} carries the declared rule <em>and</em> the resolved
 * {@code id} when the request supplied one, so a policy can either trust the gateway's lookup or
 * do its own against {@code resource.arguments}. A rule whose id was absent has {@code id: null} —
 * the policy must treat that as a failure to authorize, never as an absent constraint.
 */
final class OpaInputBuilder {

    private OpaInputBuilder() {}

    /** JWT claims copied into the document. Kept to an allow-list so tokens do not leak wholesale. */
    private static final List<String> FORWARDED_CLAIMS = List.of("groups", "roles", "tenant", "org");

    static Map<String, Object> build(AuthorizationRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("subject", subject(request.authentication()));
        input.put("action", action(request));
        input.put("resource", resource(request));
        input.put("required_scopes", request.route().getAuthScopes());
        return input;
    }

    private static Map<String, Object> subject(Authentication auth) {
        Map<String, Object> subject = new LinkedHashMap<>();
        boolean authenticated = auth != null && auth.isAuthenticated();
        subject.put("id", authenticated ? auth.getName() : null);
        subject.put("authenticated", authenticated);
        subject.put(
                "authorities",
                authenticated
                        ? auth.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .toList()
                        : List.of());
        subject.put("claims", claims(auth));
        return subject;
    }

    private static Map<String, Object> claims(Authentication auth) {
        Map<String, Object> claims = new LinkedHashMap<>();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            for (String name : FORWARDED_CLAIMS) {
                Object value = jwt.getClaim(name);
                if (value != null) claims.put(name, value);
            }
        }
        return claims;
    }

    private static Map<String, Object> action(AuthorizationRequest request) {
        var route = request.route();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("service", route.getFullServiceName());
        action.put("method", route.getMethodName());
        action.put("http_method", route.getHttpMethod());
        action.put("http_path", route.getHttpPath());
        action.put("graphql_field", route.getGraphQlName());
        return action;
    }

    private static Map<String, Object> resource(AuthorizationRequest request) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("arguments", request.arguments());

        List<Map<String, Object>> rules = new ArrayList<>();
        for (AuthzRuleDefinition rule : request.route().getAuthzRules()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("object_type", rule.objectType());
            entry.put("relation", rule.relation());
            entry.put("id_field", rule.idField());
            // Resolved id, or null when the request did not supply that field. Null means the rule
            // could not be evaluated — a policy must fail on it rather than ignore it.
            entry.put("id", lookup(request.arguments(), rule.idField()));
            rules.add(entry);
        }
        resource.put("authz", rules);
        return resource;
    }

    /**
     * Best-effort lookup of a rule's id field in the request arguments, matching the annotation's
     * snake_case against the camelCase a surface may have produced, and walking dotted paths.
     * Returns null when absent — deliberately not an exception, so the policy sees the whole
     * picture and decides.
     */
    private static Object lookup(Map<String, Object> arguments, String field) {
        Object current = arguments;
        for (String segment : field.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            Object next = map.get(segment);
            if (next == null) next = map.get(camelCase(segment));
            if (next == null) return null;
            current = next;
        }
        if (current instanceof String s && s.isBlank()) return null;
        return current;
    }

    private static String camelCase(String snake) {
        if (snake.indexOf('_') < 0) return snake;
        StringBuilder sb = new StringBuilder(snake.length());
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
