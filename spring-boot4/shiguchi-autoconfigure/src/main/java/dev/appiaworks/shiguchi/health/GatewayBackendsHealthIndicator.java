package dev.appiaworks.shiguchi.health;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The {@code shiguchi} health indicator (Boot 4: {@code spring-boot-health} packages): one
 * entry per configured service with whether its schema loaded, when, how many routes it
 * contributes, the state of its channel and, when it is not loaded, the last failure and attempt
 * count. {@code UP} when every configured service is loaded, {@code DOWN} otherwise — independent
 * of the readiness policy, which is about taking traffic; this is about telling an operator what
 * is wrong.
 *
 * <p>Shows in {@code /actuator/health} (details per {@code management.endpoint.health.show-details})
 * and can join the readiness group with
 * {@code management.endpoint.health.group.readiness.include=readinessState,shiguchi}.
 * Disable with {@code management.health.shiguchi.enabled=false} or
 * {@code shiguchi.health.enabled=false}.
 */
@Component("shiguchiHealthIndicator")
@ConditionalOnClass(ReactiveHealthIndicator.class)
@ConditionalOnEnabledHealthIndicator("shiguchi")
@ConditionalOnProperty(prefix = "shiguchi.health", name = "enabled", matchIfMissing = true)
public class GatewayBackendsHealthIndicator implements ReactiveHealthIndicator {

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final ServiceLoadStatus status;

    public GatewayBackendsHealthIndicator(
            GatewayProperties properties, GrpcRouteRegistry registry, ServiceLoadStatus status) {
        this.properties = properties;
        this.registry = registry;
        this.status = status;
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(this::check);
    }

    Health check() {
        List<ServiceLoadStatus.ServiceState> states = status.snapshot(properties, registry);
        Map<String, Object> services = new LinkedHashMap<>();
        int loaded = 0;
        for (ServiceLoadStatus.ServiceState s : states) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", s.loaded() ? "UP" : "DOWN");
            detail.put("mode", s.mode());
            detail.put("target", s.target());
            if (s.loaded()) {
                loaded++;
                detail.put("loadedAt", s.loadedAt());
                detail.put("routes", s.routes());
                detail.put("channel", s.channelState());
            }
            if (s.lastFailure() != null) {
                detail.put("lastError", s.lastFailure().error());
                detail.put("lastFailureAt", s.lastFailure().at());
                detail.put("attempts", s.lastFailure().attempts());
            }
            services.put(s.name(), detail);
        }
        Health.Builder health = loaded == states.size() ? Health.up() : Health.down();
        return health.withDetail("configured", states.size())
                .withDetail("loaded", loaded)
                .withDetail("services", services)
                .build();
    }
}
