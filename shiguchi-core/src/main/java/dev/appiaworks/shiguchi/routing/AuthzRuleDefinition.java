package dev.appiaworks.shiguchi.routing;

import java.util.Objects;

/**
 * One object-level authorization requirement declared by {@code (shiguchi.api.method).authz}: the
 * caller must hold {@link #relation()} on the object {@code objectType:<value of idField>}.
 *
 * <p>This is the resolved, transport-neutral form of the proto {@code AuthzRule} message, carried
 * on a {@link RouteDefinition} so both the REST and GraphQL surfaces expose it identically.
 *
 * <p>The gateway does not evaluate these rules. They exist for a
 * {@code GatewayAuthorizer} implementation (OpenFGA, OPA, Cedar, ...) to consume — the scope check
 * the gateway ships answers "may this caller call this method?", which cannot express
 * "may this caller act on <em>this object</em>?". When no authorizer consumes them the rules are
 * inert, so a service can annotate for object-level authorization and still run behind a gateway
 * with no policy engine wired.
 *
 * @param objectType object type in the policy engine's model, e.g. {@code "order"}
 * @param relation relation the caller must hold on that object, e.g. {@code "viewer"}
 * @param idField request field whose value is the object id — a REST path variable or query
 *     parameter, or a GraphQL field argument. Dots address nested fields ({@code "order.id"}).
 */
public record AuthzRuleDefinition(String objectType, String relation, String idField) {

    public AuthzRuleDefinition {
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(relation, "relation");
        Objects.requireNonNull(idField, "idField");
    }

    /**
     * Renders the object reference for a concrete id, e.g. {@code "order:42"} — the form Zanzibar
     * style engines (OpenFGA and its relatives) expect.
     */
    public String objectRef(String id) {
        return objectType + ":" + id;
    }
}
