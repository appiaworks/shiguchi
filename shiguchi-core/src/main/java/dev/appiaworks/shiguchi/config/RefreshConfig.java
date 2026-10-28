package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Periodic re-discovery of backend schemas.
 *
 * <p>Off by default; the {@code gateway-refresh} actuator endpoint covers the on-demand case. A
 * refresh that fails keeps the last good schema rather than dropping routes, so a backend
 * restarting mid-poll does not take its routes away.
 */
@Data
@NoArgsConstructor
public class RefreshConfig {
    /** Set true to enable periodic schema refresh polling. */
    private boolean enabled = false;
    /** Interval between polls in seconds (ignored when enabled = false). */
    private int intervalSeconds = 300;
}
