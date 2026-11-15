package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import reactor.core.publisher.Mono;

/**
 * An extra check run after the {@link GatewayAuthorizer} has allowed a request.
 *
 * <p>Every {@code GatewayPolicy} bean runs, in {@code @Order} order, and all must pass; the
 * authorizer and its scope check are unchanged and always run first. A policy sees the same
 * {@link AuthorizationRequest} as the authorizer, including the route's tags, the (redacted)
 * request headers and the {@link AuthorizationRequest.Surface}.
 *
 * <ul>
 *   <li>Complete empty to allow.</li>
 *   <li>Signal {@link org.springframework.security.access.AccessDeniedException} to deny (HTTP
 *       403, {@code PERMISSION_DENIED}).</li>
 *   <li>Signal {@link ThrottledException} to throttle (HTTP 429 with {@code Retry-After},
 *       {@code RESOURCE_EXHAUSTED}).</li>
 *   <li>Any other error, or a {@code null} return, denies: the chain fails closed.</li>
 * </ul>
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.25.0")
@FunctionalInterface
public interface GatewayPolicy {

    /**
     * @param request the request being authorized
     * @return empty to allow; an error to deny or throttle
     */
    Mono<Void> check(AuthorizationRequest request);
}
