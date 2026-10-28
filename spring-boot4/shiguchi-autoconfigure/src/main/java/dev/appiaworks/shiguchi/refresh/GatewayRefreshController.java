package dev.appiaworks.shiguchi.refresh;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Actuator endpoint for manually triggering a gateway schema refresh.
 *
 * Exposed at POST /actuator/gateway-refresh when the refresh scheduler is active.
 * Requires management.endpoints.web.exposure.include to contain "gateway-refresh".
 *
 * POST /actuator/gateway-refresh             — refresh all DYNAMIC services
 * POST /actuator/gateway-refresh {"name":"x"} — refresh one named service
 */
@Component
@Endpoint(id = "gateway-refresh")
@ConditionalOnBean(GatewayRefreshScheduler.class)
public class GatewayRefreshController {

    private final GatewayRefreshScheduler scheduler;

    public GatewayRefreshController(GatewayRefreshScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @WriteOperation
    public Map<String, Object> refresh(@Nullable String name) {
        if (name != null && !name.isBlank()) {
            boolean refreshed = scheduler.refreshService(name);
            return Map.of("service", name, "refreshed", refreshed);
        }
        List<String> changed = scheduler.refreshAll();
        return Map.of("changed", changed, "count", changed.size());
    }
}
