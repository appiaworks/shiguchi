package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import java.net.InetSocketAddress;
import java.security.Principal;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Keys per {@code shiguchi.rate-limit.key}: the authenticated principal's name ({@code
 * user:<name>}), the client IP ({@code ip:<address>}: first value of {@code client-ip-header}
 * when set and present, else the socket peer), or the principal with the IP as fallback. Spring
 * Security's anonymous principal does not count as authenticated.
 */
public class DefaultRateLimitKeyResolver implements RateLimitKeyResolver {

    private final InboundRateLimitConfig.Key key;
    private final String clientIpHeader;

    public DefaultRateLimitKeyResolver(InboundRateLimitConfig config) {
        this.key = config.getKey() == null ? InboundRateLimitConfig.Key.PRINCIPAL_OR_IP : config.getKey();
        this.clientIpHeader =
                config.getClientIpHeader() == null || config.getClientIpHeader().isBlank()
                        ? null
                        : config.getClientIpHeader();
    }

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return switch (key) {
            case IP -> Mono.justOrEmpty(ip(exchange.getRequest()));
            case PRINCIPAL -> principal(exchange);
            case PRINCIPAL_OR_IP ->
                principal(exchange).switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(ip(exchange.getRequest()))));
        };
    }

    private static Mono<String> principal(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> name != null && !name.isBlank() && !"anonymousUser".equals(name))
                .map(name -> "user:" + name);
    }

    /** {@code ip:<address>}, or null when neither the header nor the socket says. */
    String ip(ServerHttpRequest request) {
        if (clientIpHeader != null) {
            String value = request.getHeaders().getFirst(clientIpHeader);
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
