package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.shiguchi.routing.AuthzRuleDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@code (shiguchi.api.method).authz} rules on a route against the arguments a caller
 * actually sent, producing the concrete {@code (relation, object)} pairs a policy engine is asked
 * about.
 *
 * <p>This exists so every {@link GatewayAuthorizer} implementation does not have to re-derive the
 * same lookup — locating a rule's {@code idField} in {@link AuthorizationRequest#arguments()},
 * walking dotted paths for nested fields, and deciding what an absent id means.
 *
 * <h2>Missing ids are a denial, not a skip</h2>
 *
 * When a rule names a field the request did not supply, {@link #resolve} reports it as
 * {@linkplain Resolved#unresolved() unresolved} rather than silently dropping it. A rule that
 * cannot be evaluated must not degrade into "allow": that would turn a typo in an annotation, or a
 * client omitting a parameter, into an authorization bypass. Callers should deny when
 * {@code unresolved()} is non-empty.
 */
public final class AuthzRuleResolver {

    private AuthzRuleResolver() {}

    /**
     * The outcome of resolving a route's rules against one request.
     *
     * @param checks rules whose object id was found, paired with the resolved object reference
     * @param unresolved rules whose {@code idField} was absent or blank in the request
     */
    public record Resolved(List<ResolvedCheck> checks, List<AuthzRuleDefinition> unresolved) {

        public Resolved {
            checks = List.copyOf(checks);
            unresolved = List.copyOf(unresolved);
        }

        /** True when the route declared no rules at all — nothing to enforce. */
        public boolean isEmpty() {
            return checks.isEmpty() && unresolved.isEmpty();
        }
    }

    /**
     * One rule bound to a concrete object.
     *
     * @param rule the declaring rule
     * @param objectRef the object reference, e.g. {@code "order:42"}
     */
    public record ResolvedCheck(AuthzRuleDefinition rule, String objectRef) {

        public String relation() {
            return rule.relation();
        }
    }

    /** Resolves every rule declared on the request's route against that request's arguments. */
    public static Resolved resolve(AuthorizationRequest request) {
        List<AuthzRuleDefinition> rules = request.route().getAuthzRules();
        if (rules.isEmpty()) return new Resolved(List.of(), List.of());

        List<ResolvedCheck> checks = new ArrayList<>(rules.size());
        List<AuthzRuleDefinition> unresolved = new ArrayList<>();

        for (AuthzRuleDefinition rule : rules) {
            Optional<String> id = lookup(request.arguments(), rule.idField());
            if (id.isPresent()) {
                checks.add(new ResolvedCheck(rule, rule.objectRef(id.get())));
            } else {
                unresolved.add(rule);
            }
        }
        return new Resolved(checks, unresolved);
    }

    /**
     * Finds a value in the request arguments.
     *
     * <p>Tries the field name as given, then its camelCase form, since the annotation is written in
     * proto snake_case while GraphQL arguments and some REST bindings surface camelCase. Dotted
     * paths ({@code "order.id"}) walk nested maps.
     */
    private static Optional<String> lookup(Map<String, Object> arguments, String field) {
        if (field.indexOf('.') < 0) {
            return direct(arguments, field);
        }
        Object current = arguments;
        for (String segment : field.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return Optional.empty();
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            Object next = typed.containsKey(segment) ? typed.get(segment) : typed.get(camelCase(segment));
            if (next == null) return Optional.empty();
            current = next;
        }
        return stringify(current);
    }

    private static Optional<String> direct(Map<String, Object> arguments, String field) {
        Object value = arguments.get(field);
        if (value == null) value = arguments.get(camelCase(field));
        return stringify(value);
    }

    private static Optional<String> stringify(Object value) {
        if (value == null) return Optional.empty();
        String s = String.valueOf(value);
        return s.isBlank() ? Optional.empty() : Optional.of(s);
    }

    /** {@code order_id} → {@code orderId}. Returns the input unchanged when it has no underscore. */
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
