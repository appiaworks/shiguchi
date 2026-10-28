package dev.appiaworks.shiguchi.rest;

import dev.appiaworks.shiguchi.merge.MergeSpec;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.Optional;

/**
 * Supplies a {@link MergeSpec} for a route at registration time.
 *
 * <p>The highest-precedence way to declare a merge, ahead of YAML and the proto annotation, for
 * topologies that cannot be written down statically — sources that vary by tenant, by region, or
 * by what is actually deployed.
 *
 * <p>Lives in {@code -rest} rather than {@code -core} for the same reason {@code GatewayAuthorizer}
 * does: it is part of the dispatch surface an application extends, not of the routing model.
 *
 * <p>Called once per route while indexes are built, not per request — so an implementation may read
 * configuration or service discovery, but should not do per-call work.
 */
@FunctionalInterface
public interface MergeSourceProvider {

    /**
     * @param route the route being registered
     * @return the merge to serve it with, or empty to leave it an ordinary single-backend route
     */
    Optional<MergeSpec> mergeFor(RouteDefinition route);
}
