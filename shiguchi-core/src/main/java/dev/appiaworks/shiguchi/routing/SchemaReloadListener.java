package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * Notified after the set of registered {@link ServiceEndpointContext}s changed at runtime, so
 * every derived artefact (GraphQL SDL, OpenAPI document, UI schema) can rebuild itself.
 *
 * <p>Two things trigger it: the refresh scheduler swapping a context whose schema changed, and a
 * route loader registering a service whose load was deferred past startup because the backend was
 * not reachable yet. Before this hook the second case left the service routable but absent from
 * every generated document until the next refresh.
 *
 * <p>Lives in core so the scheduler and the loaders depend on nothing but this interface: the
 * surfaces that implement it (graphql, openapi, ui) are optional modules, and referencing their
 * classes directly made the scheduler impossible to instantiate whenever one was absent.
 */
@FunctionalInterface
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.4.0")
public interface SchemaReloadListener {

    /** Rebuild whatever this listener derives from the registered contexts. Must not throw. */
    void onSchemaReloaded();
}
