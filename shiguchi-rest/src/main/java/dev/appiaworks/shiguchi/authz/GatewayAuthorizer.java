package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import reactor.core.publisher.Mono;

/**
 * Strategy for authorizing a request before the gateway dispatches it to a gRPC backend.
 *
 * <p>The gateway ships one implementation, {@link ScopeGatewayAuthorizer}, which enforces the
 * {@code auth_scopes} declared in the {@code (shiguchi.api.method)} option. It is registered as
 * the default and preserves the gateway's historical behaviour.
 *
 * <p>Supplying your own {@code GatewayAuthorizer} bean replaces it entirely â€” this is the
 * extension point for per-object / relationship-based authorization (OpenFGA, OPA, Cedar, ...),
 * which the coarse scope check cannot express. To keep the scope check <em>and</em> add another,
 * compose them: depend on {@link ScopeGatewayAuthorizer} from your implementation and chain the
 * two {@code Mono}s with {@code .then(...)}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Return an empty {@code Mono} to allow the call.</li>
 *   <li>Signal {@link org.springframework.security.access.AccessDeniedException} to deny it â€”
 *       both surfaces translate that to HTTP 403 / a GraphQL error.</li>
 *   <li>Never block. Implementations sit on the request hot path and run on Netty event-loop
 *       threads; bridge blocking clients with {@code Mono.fromFuture} or
 *       {@code subscribeOn(Schedulers.boundedElastic())}.</li>
 *   <li>Implementations that make a network call should apply their own timeout and decide
 *       explicitly whether a backend failure fails open or closed.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe; a single instance serves all concurrent requests.
 */
@FunctionalInterface
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.14.0")
public interface GatewayAuthorizer {

    /**
     * Authorize one request.
     *
     * @param request the route, caller and request inputs to decide on
     * @return an empty {@code Mono} when allowed, or one erroring with
     *     {@code AccessDeniedException} when denied
     */
    Mono<Void> authorize(AuthorizationRequest request);
}
