package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;

/**
 * One of the route loaders, seen as "the thing that can build a {@link ServiceEndpointContext}
 * for a service definition": {@link DynamicRouteLoader} for {@code DYNAMIC} and {@code PB_FILE},
 * {@link StaticRouteLoader} for {@code STATIC}, {@link RestDownstreamRouteLoader} for
 * {@code REST_DOWNSTREAM}. {@link PendingLoadRetrier} uses it to retry services that failed to
 * load, whichever loader owns them.
 */
public interface ServiceLoader {

    /** Whether this loader handles the service's mode. */
    boolean supports(ServiceDefinition service);

    /** Builds a fully populated context; throws when the backend or its schema cannot be reached. */
    ServiceEndpointContext buildContext(ServiceDefinition service) throws Exception;
}
