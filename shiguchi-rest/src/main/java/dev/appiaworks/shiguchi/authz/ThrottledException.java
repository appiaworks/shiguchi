package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;

/**
 * Signalled by a {@link GatewayPolicy} to throttle a request. The gateway answers HTTP 429 with a
 * {@code Retry-After} header on REST, {@code RESOURCE_EXHAUSTED} on gRPC-Web, and a
 * {@code RESOURCE_EXHAUSTED} error on GraphQL.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.25.0")
public class ThrottledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public ThrottledException(Duration retryAfter) {
        super("Throttled, retry after " + retryAfter);
        this.retryAfter = retryAfter == null ? Duration.ofSeconds(1) : retryAfter;
    }

    /** How long the caller should wait before retrying. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** {@link #retryAfter()} as whole seconds, rounded up, at least 1. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
