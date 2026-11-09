package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import dev.appiaworks.shiguchi.ratelimit.RateLimiter;
import dev.appiaworks.shiguchi.ratelimit.RateLimiter.Decision;
import java.net.InetSocketAddress;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

/**
 * Charges the cost of an operation to its caller's bucket. The caller is the authenticated
 * principal, else the client IP, per {@code shiguchi.rate-limit.key}; a request with neither is
 * not charged. The limiter is private to this class, never a bean: the HTTP rate-limit filter
 * injects a {@link RateLimiter} bean by type.
 */
final class GraphQlCostBudget {

    private final RateLimiter limiter;
    private final InboundRateLimitConfig keys;
    private final int perMinute;

    GraphQlCostBudget(RateLimiter limiter, InboundRateLimitConfig keys, int perMinute) {
        this.limiter = limiter;
        this.keys = keys;
        this.perMinute = perMinute;
    }

    /** Spends {@code cost} points; an operation dearer than the whole budget spends all of it. */
    Mono<Decision> charge(WebGraphQlRequest request, int cost) {
        int tokens = Math.max(1, Math.min(cost, perMinute));
        return key(request).flatMap(key -> limiter.tryAcquire(key, tokens)).defaultIfEmpty(Decision.allow(perMinute));
    }

    private Mono<String> key(WebGraphQlRequest request) {
        InboundRateLimitConfig.Key mode =
                keys.getKey() == null ? InboundRateLimitConfig.Key.PRINCIPAL_OR_IP : keys.getKey();
        return switch (mode) {
            case IP -> Mono.justOrEmpty(ip(request));
            case PRINCIPAL -> principal();
            case PRINCIPAL_OR_IP -> principal().switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(ip(request))));
        };
    }

    private static Mono<String> principal() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .filter(name -> name != null && !name.isBlank() && !"anonymousUser".equals(name))
                .map(name -> "user:" + name);
    }

    private String ip(WebGraphQlRequest request) {
        String header = keys.getClientIpHeader();
        if (header != null && !header.isBlank()) {
            String value = request.getHeaders().getFirst(header);
            if (value != null && !value.isBlank()) {
                int comma = value.indexOf(',');
                return "ip:" + (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? "ip:" + remote.getAddress().getHostAddress()
                : null;
    }
}
