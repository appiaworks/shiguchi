package dev.appiaworks.shiguchi.sample.downstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demonstrates {@code REST_DOWNSTREAM}: an existing, non-gRPC REST service aggregated into the
 * gateway alongside everything else it fronts.
 *
 * <p>The gateway fetches the backend's OpenAPI document, registers one proxy route per operation
 * under {@code path-prefix}, enforces per-operation scopes before proxying, and merges the
 * document into the consolidated {@code /v3/api-docs} with the backend's component names
 * namespaced so they cannot collide with another service's.
 *
 * <p>The backend itself ({@link InventoryBackend}) is an in-process reactor-netty server on
 * {@code :8081}, started before Spring so its document is there when the gateway's route loader
 * runs, the way a real backend would already be up. A real deployment would point
 * {@code openapi-url} at the service's own springdoc, FastAPI, or hand-written document; the
 * gateway does not care which.
 *
 * <pre>
 * curl http://localhost:8080/inv/items/42        # proxied to :8081/items/42, prefix stripped
 * curl http://localhost:8080/inv/items           # hidden by exclude-methods → 404
 * curl -X POST http://localhost:8080/inv/items   # needs scope inventory.write → 403 anonymously
 * curl http://localhost:8080/v3/api-docs         # merged spec: /inv/... paths, inventory.* components
 * open http://localhost:8080/swagger-ui.html
 * </pre>
 */
@SpringBootApplication
public class SampleRestDownstreamApplication {

    public static void main(String[] args) {
        InventoryBackend backend = new InventoryBackend(8081);
        backend.start();
        Runtime.getRuntime().addShutdownHook(new Thread(backend::stop, "inventory-backend-shutdown"));
        SpringApplication.run(SampleRestDownstreamApplication.class, args);
    }
}
