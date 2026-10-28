package dev.appiaworks.shiguchi.sample.discovery;

import java.util.List;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

/**
 * A registry over the {@link CatalogFleet} running in this JVM, so the sample needs no Eureka,
 * Consul or Kubernetes to run.
 *
 * <p>The gateway asks for a {@link DiscoveryClient} and nothing more. Swapping this class for
 * {@code spring-cloud-starter-netflix-eureka-client} (or Consul, or Kubernetes) is a dependency
 * and a few properties — the gateway configuration does not change, because
 * {@code discovery-name: catalog-service} means "ask the registry", whichever registry that is.
 * See the README.
 *
 * <p>Instance ports are read from the fleet on every call rather than captured once, which is
 * what makes {@link CatalogFleet#stopOne()} visible: the next refresh (every
 * {@code shiguchi.client.discovery-refresh}) sees a shorter list and the channel drops that
 * address, exactly as it would when a pod is evicted.
 */
@Component
public class InProcessDiscoveryClient implements DiscoveryClient {

    /** The id the gateway looks up — {@code shiguchi.services[0].discovery-name}. */
    static final String SERVICE_ID = "catalog-service";

    private final CatalogFleet fleet;

    public InProcessDiscoveryClient(CatalogFleet fleet) {
        this.fleet = fleet;
    }

    @Override
    public String description() {
        return "In-process catalog fleet (sample stand-in for Eureka/Consul/Kubernetes)";
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceId) {
        if (!SERVICE_ID.equals(serviceId)) return List.of();
        return fleet.instances().stream()
                .map(instance -> (ServiceInstance) new DefaultServiceInstance(
                        instance.instanceId(), SERVICE_ID, "localhost", instance.port(), false))
                .toList();
    }

    @Override
    public List<String> getServices() {
        return List.of(SERVICE_ID);
    }
}
