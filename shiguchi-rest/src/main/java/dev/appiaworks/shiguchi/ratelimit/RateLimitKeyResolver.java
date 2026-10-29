package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * What identifies a caller for rate limiting. The default ({@link DefaultRateLimitKeyResolver})
 * follows {@code shiguchi.rate-limit.key}; define your own bean of this type to key on an
 * API-key header, a tenant claim, a route, or a combination, and the default backs off.
 */
@FunctionalInterface
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface RateLimitKeyResolver {

    /** The caller's key, or an empty {@code Mono} to let this request through unlimited. */
    Mono<String> resolve(ServerWebExchange exchange);
}
