package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A backend that cannot be reached when the gateway starts is retried once when the
 * application is ready and then, by default, every {@link #retryInterval} until it loads —
 * the gateway starts without its routes meanwhile (and reports not ready under the default
 * {@link HealthConfig#readiness} policy). {@link #failFast} aborts startup instead.
 */
@Data
@NoArgsConstructor
public class StartupConfig {
    /**
     * Abort startup when any configured service is still not loaded after the retry that runs
     * once the application is ready. Off by default: the gateway starts degraded and keeps
     * retrying.
     */
    private boolean failFast = false;
    /**
     * How often services that failed to load are retried after startup. {@code 0} disables the
     * background retry (the one retry when the application is ready still happens).
     */
    private java.time.Duration retryInterval = java.time.Duration.ofSeconds(30);
}
