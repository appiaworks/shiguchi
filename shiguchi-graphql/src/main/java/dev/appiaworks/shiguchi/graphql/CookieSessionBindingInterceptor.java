package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.CookieSessionWatch;
import dev.appiaworks.shiguchi.security.TokenExpiry;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Binds the GraphQL operations of a caller to the credential that authenticated them. A caller
 * authenticated with the BFF session cookie is bound to the session: a subscription ends with an
 * {@code UNAUTHORIZED} "session ended" error when the session does (at once when this instance ends
 * it, within {@code shiguchi.security.session-check-interval} when another does), and every
 * operation sent on an open WebSocket first asks whether the session is still active and is refused
 * with the same error when it is not (a socket opened with the cookie outlives the access token, and
 * the logout). A bearer (JWT) caller is bound to the token, with or without the BFF: a subscription
 * ends with a "token expired" error at the token's {@code exp}, and an operation sent on an open
 * WebSocket after it is refused (the socket's auth is the handshake's); {@code
 * shiguchi.security.end-streams-at-token-expiry=false} turns that off. A cookie caller is never
 * bound to the access token it carries. An HTTP request has just been authenticated, so it is not
 * asked again.
 *
 * <p>Spring GraphQL's interceptor chain runs once per operation, so this one hook covers the
 * gateway's own fields and every other subscription of the application.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class CookieSessionBindingInterceptor implements WebGraphQlInterceptor {

    private final ObjectProvider<CookieSessionWatch> watches;
    private final boolean endAtTokenExpiry;

    public CookieSessionBindingInterceptor(ObjectProvider<CookieSessionWatch> watches, GatewayProperties properties) {
        this.watches = watches;
        this.endAtTokenExpiry = properties.getSecurity().isEndStreamsAtTokenExpiry();
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .map(auth -> Optional.ofNullable(bound(request, chain, auth)))
                .defaultIfEmpty(Optional.empty())
                .flatMap(run -> run.orElseGet(() -> chain.next(request)));
    }

    /** The bound run of the operation, or {@code null} when the caller is not bound to anything. */
    private Mono<WebGraphQlResponse> bound(WebGraphQlRequest request, Chain chain, Authentication auth) {
        boolean socket = request instanceof WebSocketGraphQlRequest;
        if (CookieAuthenticated.isCookieAuthenticated(auth)) {
            CookieSessionWatch watch = watches.getIfAvailable();
            if (watch == null || !watch.bound(auth)) return null;
            Mono<WebGraphQlResponse> response = Mono.defer(() -> chain.next(request))
                    .map(r -> bind(r, watch.ended(auth), CookieSessionWatch.REASON));
            if (!socket) return response;
            return watch.active(auth)
                    .flatMap(active -> active ? response : Mono.just(refused(request, CookieSessionWatch.REASON)));
        }
        Instant exp = endAtTokenExpiry ? TokenExpiry.expiry(auth) : null;
        if (exp == null) return null;
        // A token accepted within the clock skew is already past exp: a socket refuses, a stream ends at once.
        Duration wait = Duration.between(Instant.now(), exp);
        if (wait.isNegative() && socket) return Mono.just(refused(request, TokenExpiry.REASON));
        Mono<Void> end = Mono.delay(wait.isNegative() ? Duration.ZERO : wait, Schedulers.parallel())
                .then();
        return Mono.defer(() -> chain.next(request)).map(r -> bind(r, end, TokenExpiry.REASON));
    }

    /** A subscription's results end with the {@code reason} error when {@code end} completes. */
    @SuppressWarnings("unchecked")
    private static WebGraphQlResponse bind(WebGraphQlResponse response, Mono<Void> end, String reason) {
        Object data = response.getData();
        if (!(data instanceof Publisher<?> results)) return response;
        var ended = new AtomicBoolean();
        Flux<ExecutionResult> bound = Flux.from((Publisher<ExecutionResult>) results)
                .takeUntilOther(end.thenReturn(Boolean.TRUE).doOnNext(e -> ended.set(true)))
                .concatWith(Mono.defer(
                        () -> ended.get() ? Mono.error(GraphQlSessionEnded.exception(reason)) : Mono.empty()));
        return response.transform(builder -> builder.data(bound));
    }

    private static WebGraphQlResponse refused(WebGraphQlRequest request, String reason) {
        ExecutionResult result = ExecutionResultImpl.newExecutionResult()
                .errors(List.of(GraphQlSessionEnded.error(reason)))
                .build();
        return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(request.toExecutionInput(), result));
    }
}
