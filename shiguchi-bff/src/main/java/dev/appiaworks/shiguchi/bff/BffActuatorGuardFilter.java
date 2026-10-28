package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.GatewayRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Refuses every request authenticated by a BFF session cookie that targets an actuator endpoint, all
 * methods, reads included, with {@code 403 {"error":"cookie sessions cannot call actuator endpoints"}}.
 * The actuator writes the gateway gates (traffic, push, refresh, BFF sessions) are guarded by "any
 * authenticated caller", which a signed-in browser user would satisfy, and the {@code Origin} check
 * is no defence because a user can send their own cookie and a matching {@code Origin} from a
 * script. Bearer and anonymous requests are untouched. Runs right after authentication.
 */
final class BffActuatorGuardFilter implements WebFilter {

    private final String basePath;

    BffActuatorGuardFilter(String actuatorBasePath) {
        String base = actuatorBasePath == null || actuatorBasePath.isBlank() ? "/actuator" : actuatorBasePath.trim();
        while (base.length() > 1 && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.basePath = base;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!(path.equals(basePath) || path.startsWith(basePath + "/"))) return chain.filter(exchange);
        // The context is read here, before anything looks at the exchange attribute: loading sets it.
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(auth -> CookieAuthenticated.isCookieAuthenticated(auth)
                        || GatewayRequestAttributes.COOKIE.equals(
                                exchange.getAttributes().get(GatewayRequestAttributes.AUTHENTICATED_BY)))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> found.isEmpty() ? chain.filter(exchange) : refuse(exchange));
    }

    private static Mono<Void> refuse(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer body = response.bufferFactory()
                .wrap("{\"error\":\"cookie sessions cannot call actuator endpoints\"}"
                        .getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }
}
