package dev.appiaworks.shiguchi.sample.discovery;

import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A REST gateway in front of a gRPC service that runs as several instances, found through Spring
 * Cloud service discovery and balanced across with {@code round_robin}.
 *
 * <p>The gateway side is all configuration: {@code discovery-name} instead of {@code host}/
 * {@code port} in {@code application.yml}. The Java here is the part a real deployment already
 * has — the backend instances, and a registry that knows about them.
 *
 * <p>Run it and repeat {@code GET /v1/products}: {@code servedBy} rotates through
 * {@code catalog-1}, {@code catalog-2}, {@code catalog-3}.
 */
@SpringBootApplication
public class SampleDiscoveryGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleDiscoveryGatewayApplication.class, args);
    }

    /**
     * Control over the fleet, so the sample can be poked at without a second terminal. Not part of
     * the gateway: a real deployment scales with its orchestrator.
     */
    @RestController
    @RequestMapping("/fleet")
    public static class FleetController {

        private final CatalogFleet fleet;

        public FleetController(CatalogFleet fleet) {
            this.fleet = fleet;
        }

        /** The instances discovery currently reports, with the ports they actually bound. */
        @GetMapping
        public Map<String, Object> instances() {
            List<Map<String, Object>> running = fleet.instances().stream()
                    .map(i -> Map.<String, Object>of("instanceId", i.instanceId(), "port", i.port()))
                    .toList();
            return Map.of("serviceId", InProcessDiscoveryClient.SERVICE_ID, "instances", running);
        }

        /**
         * Kills one instance. The next {@code /v1/products} calls keep working on the remaining
         * ones — the channel drops the dead address instead of failing the call.
         */
        @DeleteMapping("/one")
        public Map<String, Object> stopOne() {
            String stopped = fleet.stopOne();
            return Map.of("stopped", stopped, "remaining", fleet.instances().size());
        }
    }
}
