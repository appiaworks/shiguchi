package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Supplies the upstream {@code authorization} metadata for a caller who was authenticated without
 * an {@code Authorization} header, by a session cookie for example. {@link GrpcMetadataPopulator}
 * asks it only when the inbound {@code Authorization} header is blank and the security context
 * holds an authenticated, non-anonymous caller; a request carrying its own header always forwards
 * that header, and the source is never called for it.
 *
 * <p>At most one bean of this type may exist; two fail startup.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Return the full header value, scheme included ({@code "Bearer …"}), or an empty
 *       {@code Mono} to send no {@code authorization} key.</li>
 *   <li>An error fails the call: the gateway does not fall back to sending it without a
 *       credential.</li>
 *   <li>Never block. Implementations run on Netty event-loop threads, on every call from such a
 *       caller.</li>
 * </ul>
 */
@FunctionalInterface
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public interface UpstreamCredentialSource {

    /**
     * The {@code authorization} value to send upstream for this caller.
     *
     * @param request the inbound request, which carries no {@code Authorization} header
     * @param authentication the caller, authenticated and not anonymous
     * @return the header value, or empty for none
     */
    Mono<String> authorization(ServerHttpRequest request, Authentication authentication);
}
