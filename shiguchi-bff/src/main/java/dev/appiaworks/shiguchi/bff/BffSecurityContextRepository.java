package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.GatewayRequestAttributes;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway's security context when the BFF module is on. A request with an
 * {@code Authorization} header, of any scheme and even malformed, gets no context from here and
 * is handled as a bearer request. Without one, the {@link BffSessionAuthenticator} is asked, and
 * a caller it returns is marked {@link GatewayRequestAttributes#AUTHENTICATED_BY} {@code cookie}.
 *
 * <p>Spring Security re-subscribes to {@link #load} for every reader of the context (the
 * authorization rules, the gateway's metadata, authorizers, the access log), so the answer is
 * computed once per request and kept on the exchange. {@link #save} does nothing: only sign-in
 * writes sessions.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class BffSecurityContextRepository implements ServerSecurityContextRepository {

    private static final String LOADED = BffSecurityContextRepository.class.getName() + ".loaded";

    private final BffSessionAuthenticator authenticator;

    /** @param authenticator the session lookup, or {@code null} to authenticate nobody by cookie */
    public BffSecurityContextRepository(BffSessionAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        return Mono.empty();
    }

    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        if (authenticator == null) return Mono.empty();
        if (exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) return Mono.empty();
        @SuppressWarnings("unchecked")
        Mono<SecurityContext> loaded =
                (Mono<SecurityContext>) exchange.getAttributes().computeIfAbsent(LOADED, k -> fromSession(exchange));
        return loaded;
    }

    private Mono<SecurityContext> fromSession(ServerWebExchange exchange) {
        return Mono.defer(() -> authenticator.authenticate(exchange))
                .filter(auth -> auth.isAuthenticated())
                .map(auth -> {
                    if (auth instanceof AbstractAuthenticationToken token
                            && !(token.getDetails() instanceof CookieAuthenticated)) {
                        token.setDetails(new CookieAuthenticated(token.getDetails(), null, null));
                    }
                    exchange.getAttributes()
                            .put(GatewayRequestAttributes.AUTHENTICATED_BY, GatewayRequestAttributes.COOKIE);
                    return (SecurityContext) new SecurityContextImpl(auth);
                })
                .cache();
    }

    /**
     * Whether this repository authenticated the caller by session cookie. For code that sees the
     * caller but not the exchange (the upstream credential source); with the exchange, read
     * {@link GatewayRequestAttributes#AUTHENTICATED_BY}.
     *
     * @param auth the caller, may be {@code null}
     * @return {@code true} for a cookie-authenticated caller
     */
    public static boolean isCookieAuthenticated(Authentication auth) {
        return CookieAuthenticated.isCookieAuthenticated(auth);
    }
}
