package dev.appiaworks.shiguchi.bff;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Turns {@link BffSessionUnavailableException} into {@code 503} with {@code Retry-After: 1}. First
 * in the security chain: the session is resolved lazily by whatever reads the security context
 * (the authorization rules, or the gateway's handler on an open route), so the error surfaces
 * anywhere downstream, and nothing downstream may turn it into an anonymous request.
 */
final class BffUnavailableFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).onErrorResume(BffUnavailableFilter::unavailable, e -> {
            if (exchange.getResponse().isCommitted()) return Mono.error(e);
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            exchange.getResponse().getHeaders().set("Retry-After", "1");
            return exchange.getResponse().setComplete();
        });
    }

    private static boolean unavailable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof BffSessionUnavailableException) return true;
            if (t.getCause() == t) return false;
        }
        return false;
    }
}
