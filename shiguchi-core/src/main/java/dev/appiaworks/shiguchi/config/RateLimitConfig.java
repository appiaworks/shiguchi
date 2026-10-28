package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rate limiting on the gateway's gRPC proxy port, applied per method across all callers.
 *
 * <p>Not to be confused with {@link InboundRateLimitConfig} under {@code shiguchi.rate-limit},
 * which limits the REST and GraphQL surfaces per caller.
 */
@Data
@NoArgsConstructor
public class RateLimitConfig {
    /** Enable per-method token-bucket rate limiting. */
    private boolean enabled = false;
    /** Max requests per second allowed per method (across all callers). */
    private double requestsPerSecond = 100.0;
}
