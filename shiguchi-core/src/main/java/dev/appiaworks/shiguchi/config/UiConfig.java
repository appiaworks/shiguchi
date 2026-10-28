package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The embedded gRPC web console.
 *
 * <p>Off by default, and deliberately so: it lists and invokes every method on every backend,
 * including those with no HTTP binding. Enabling it on an untrusted network without
 * authentication hands that ability to anyone who finds the path.
 */
@Data
@NoArgsConstructor
public class UiConfig {
    /** Set true to mount the embedded gRPC web UI at `path`. */
    private boolean enabled = false;
    /** Base path where the UI is served. */
    private String path = "/grpc-ui";
    /** When true, the UI path requires an authenticated Spring Security principal. */
    private boolean requireAuth = false;
}
