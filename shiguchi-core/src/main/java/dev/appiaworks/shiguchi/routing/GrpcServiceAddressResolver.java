package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.Optional;

/**
 * Resolves the network address for a service definition that uses logical addressing
 * (e.g. {@code discovery-name}) instead of a fixed {@code host}/{@code port}.
 *
 * <p>The gateway ships a Spring Cloud {@code DiscoveryClient}-backed implementation that
 * activates automatically when a {@code DiscoveryClient} bean is present. Register your own
 * bean of this type to resolve addresses from any other source (config service, DNS SRV,
 * custom registry); resolvers are consulted in {@code @Order} order and the first non-empty
 * result wins.
 */
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.2.0")
public interface GrpcServiceAddressResolver {

    /**
     * Resolve the address for one service, or {@link Optional#empty()} when this resolver
     * has no answer (the next resolver is consulted).
     */
    Optional<ResolvedAddress> resolve(ServiceDefinition service);

    record ResolvedAddress(String host, int port) {}
}
