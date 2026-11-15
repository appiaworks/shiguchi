package dev.appiaworks.shiguchi.grpc;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import io.grpc.Metadata;
import io.grpc.Status;
import java.time.Instant;

/**
 * Decides whether a call on the native gRPC proxy port may proceed, when
 * {@code shiguchi.grpc.security.mode} is {@code ENFORCE}.
 *
 * <p>The proxy module does not depend on the REST module, where the authorizer lives, so the
 * decision is behind this interface; the Spring Boot auto-configuration supplies the implementation
 * (bearer JWT, {@code GatewayAuthorizer}, {@code GatewayPolicy} chain). {@link #admit} may block: it
 * runs on the gRPC call executor, never on a Netty event loop.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.25.0")
public interface ProxyCallGuard {

    /**
     * @param fullMethodName {@code package.Service/Method}
     * @param headers the call's inbound metadata
     * @return the decision; never null. An implementation that cannot decide returns a denial.
     */
    Decision admit(String fullMethodName, Metadata headers);

    /**
     * The outcome. A denial carries the status to close the call with and optional trailers
     * (for instance {@code retry-after}); an admission carries the caller's name, sent to the
     * backend as {@code x-principal}, and when the token expires ({@code null} for no expiry).
     */
    record Decision(Status denial, Metadata trailers, String principal, Instant expiresAt) {

        public static Decision allow(String principal, Instant expiresAt) {
            return new Decision(null, null, principal, expiresAt);
        }

        public static Decision deny(Status status) {
            return new Decision(status, new Metadata(), null, null);
        }

        public static Decision deny(Status status, Metadata trailers) {
            return new Decision(status, trailers != null ? trailers : new Metadata(), null, null);
        }

        public boolean allowed() {
            return denial == null;
        }
    }
}
