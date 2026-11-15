package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Security of the gateway's native gRPC proxy port ({@code shiguchi.grpc.security}).
 *
 * <p>{@link Mode#PASSTHROUGH} (the default) authenticates nobody: every call is forwarded with its
 * metadata and the backend decides. {@link Mode#ENFORCE} makes the proxy authenticate a bearer
 * token with the gateway's JWT decoder and run the route's scope check, the
 * {@code GatewayAuthorizer} and the {@code GatewayPolicy} chain before it forwards.
 */
@Data
@NoArgsConstructor
public class GrpcSecurityConfig {

    /** How the proxy treats callers. */
    public enum Mode {
        /** Forward every call untouched; {@code x-principal} is always {@code anonymous}. */
        PASSTHROUGH,
        /** Require a valid bearer token and authorize each call before forwarding it. */
        ENFORCE
    }

    /** {@code PASSTHROUGH} (default) or {@code ENFORCE}. */
    private Mode mode = Mode.PASSTHROUGH;

    /** Longest the proxy waits for the authorization decision of one call. */
    private java.time.Duration decisionTimeout = java.time.Duration.ofSeconds(5);
}
