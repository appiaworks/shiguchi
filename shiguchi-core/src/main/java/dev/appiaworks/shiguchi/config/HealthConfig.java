package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How the gateway reports the state of its backends. The {@code shiguchi} actuator health
 * indicator lists every configured service (loaded or not, channel state, last error), and the
 * readiness policy decides whether Spring Boot's readiness state — what the Kubernetes
 * readiness probe and {@code /actuator/health/readiness} read — stays {@code ACCEPTING_TRAFFIC}
 * while backends are missing.
 */
@Data
@NoArgsConstructor
public class HealthConfig {
    /** Register the {@code shiguchi} health indicator (needs Actuator on the classpath). */
    private boolean enabled = true;
    /**
     * When the gateway is ready to take traffic. {@link ReadinessPolicy#ALL_LOADED} (default):
     * every configured service has loaded; {@link ReadinessPolicy#ANY_LOADED}: at least one
     * has; {@link ReadinessPolicy#IGNORE}: backends never affect readiness.
     */
    private ReadinessPolicy readiness = ReadinessPolicy.ALL_LOADED;
}
