package dev.appiaworks.shiguchi.cache;

import dev.appiaworks.shiguchi.gateway.ResponseCache;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

/**
 * Actuator endpoint for the REST response cache ({@code rest.response-cache}).
 *
 * <p>{@code GET /actuator/gateway-cache} — entries, size and hit/miss/stale/coalesced counts.
 * {@code DELETE /actuator/gateway-cache} empties it; {@code DELETE /actuator/gateway-cache/{service}}
 * drops one configured service's entries, for when a backend's data changed behind the gateway's
 * back. Exposed only when {@code management.endpoints.web.exposure.include} names
 * {@code gateway-cache}; the delete operations are writes, so keep it off public exposure.
 */
@Component
@Endpoint(id = "gateway-cache")
public class GatewayCacheEndpoint {

    private final ResponseCache cache;

    public GatewayCacheEndpoint(ResponseCache cache) {
        this.cache = cache;
    }

    @ReadOperation
    public Map<String, Object> stats() {
        return cache.stats();
    }

    @DeleteOperation
    public Map<String, Object> clear() {
        return Map.of("evicted", cache.clear());
    }

    @DeleteOperation
    public Map<String, Object> evict(@Selector String service) {
        return Map.of("service", service, "evicted", cache.evictService(service));
    }
}
