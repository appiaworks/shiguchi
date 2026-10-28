package dev.appiaworks.shiguchi.sample.openfga;

/**
 * Binds one gateway route to the OpenFGA tuple that must hold for the call to be allowed.
 *
 * <p>This is the piece the gateway cannot infer on its own. A route knows it maps
 * {@code GET /v1/orders/{orderId}} onto {@code OrderService/GetOrder}, but nothing in the proto
 * says "{@code orderId} identifies an object of type {@code order}, and the caller needs the
 * {@code viewer} relation on it". That mapping is the policy, and it lives here.
 *
 * <p>{@code idArgument} names a key in {@code AuthorizationRequest.arguments()} — a REST path
 * variable or query parameter, or a GraphQL field argument. The value found there becomes the
 * object id, so the resulting FGA object is {@code objectType + ":" + value}.
 *
 * <p>A production deployment would more likely drive this from new fields on the
 * {@code (shiguchi.api.method)} proto option, so the policy travels with the service definition
 * rather than living in the gateway's config. The trade-off is the same either way: something
 * must state which request input carries the object id.
 *
 * @param objectType the FGA object type, e.g. {@code "order"}
 * @param relation the FGA relation the caller must hold, e.g. {@code "viewer"}
 * @param idArgument the argument key carrying the object id, e.g. {@code "orderId"}
 */
public record RouteObjectPermission(String objectType, String relation, String idArgument) {

    /** Renders the FGA object reference for a concrete id, e.g. {@code "order:42"}. */
    public String objectRef(String id) {
        return objectType + ":" + id;
    }
}
