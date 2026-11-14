package dev.appiaworks.shiguchi.discovery;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Read-only actuator endpoint: every registry service the last sync saw and what became of it
 * ({@code REGISTERED}, {@code NOT_OPTED_IN}, {@code EXCLUDED_BY_PATTERN}, {@code NO_GRPC_PORT},
 * {@code EXPLICIT_CONFIG_WINS}, {@code PROBE_FAILED}). {@code GET /actuator/gateway-discovery}.
 * Present only with {@code shiguchi.discovery.auto-register.enabled=true}, and exposed only when
 * {@code management.endpoints.web.exposure.include} names {@code gateway-discovery}. It describes
 * internal topology: keep it off public exposure.
 */
@Component
@Endpoint(id = "gateway-discovery")
@ConditionalOnProperty(prefix = "shiguchi.discovery.auto-register", name = "enabled", havingValue = "true")
public class GatewayDiscoveryEndpoint {

    private final DiscoveredServiceSynchronizer synchronizer;

    public GatewayDiscoveryEndpoint(DiscoveredServiceSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    @ReadOperation
    public Map<String, Object> discovery() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("services", synchronizer.seen().values());
        return out;
    }
}
