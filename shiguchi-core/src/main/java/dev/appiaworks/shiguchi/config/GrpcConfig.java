package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The gateway's own gRPC port — a pass-through proxy in front of the configured backends, with
 * merged reflection across them, for callers that speak gRPC and want one address.
 *
 * <p>Separate from the REST and GraphQL surfaces and from their rate limiting: the limits here
 * apply to that proxy port.
 */
@Data
@NoArgsConstructor
public class GrpcConfig {
    /** Expose a native gRPC port that proxies to downstream services. */
    private boolean enabled = false;
    /** Port the embedded gRPC server listens on. */
    private int port = 9090;

    private RateLimitConfig rateLimit = new RateLimitConfig();
}
