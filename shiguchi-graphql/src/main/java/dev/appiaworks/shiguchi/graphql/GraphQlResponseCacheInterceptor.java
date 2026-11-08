package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.graphql.GraphQlResponseCache.Entry;
import dev.appiaworks.shiguchi.graphql.GraphQlResponseCache.Outcome;
import dev.appiaworks.shiguchi.graphql.GraphQlResponseCache.Plan;
import graphql.language.OperationDefinition;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Serves query responses from {@link GraphQlResponseCache} and keeps it current. A query whose
 * root fields all map to methods with {@code cache_ttl_seconds} is looked up by document,
 * operation, variables and (when the key is per caller) principal: a fresh entry is returned
 * without touching the engine, an expired one within {@code stale-while-revalidate} is returned
 * while one refresh runs in the background, anything else runs and is stored when it has data
 * and no errors. A mutation always runs, then evicts every entry built from the services behind
 * its root fields. Subscriptions and requests over WebSocket pass through untouched.
 *
 * <p>Every answered request carries {@code Cache-Status} (RFC 9211) and, on a hit, {@code Age}.
 */
class GraphQlResponseCacheInterceptor implements WebGraphQlInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GraphQlResponseCacheInterceptor.class);
    static final String CACHE_STATUS = "Cache-Status";
    static final String AGE = "Age";
    static final String ANONYMOUS = "anonymous";

    private final GraphQlResponseCache cache;

    GraphQlResponseCacheInterceptor(GraphQlResponseCache cache) {
        this.cache = cache;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (request instanceof WebSocketGraphQlRequest || !StringUtils.hasText(request.getDocument())) {
            return chain.next(request);
        }
        Plan plan = cache.plan(request.getDocument(), request.getOperationName());
        if (plan == null) {
            return chain.next(request);
        }
        if (plan.type() == OperationDefinition.Operation.MUTATION) {
            return chain.next(request).doOnNext(response -> evictAfter(plan));
        }
        if (plan.type() != OperationDefinition.Operation.QUERY || plan.ttl() == null) {
            return chain.next(request);
        }
        return Mono.deferContextual(view -> principal(plan).flatMap(principal -> {
            String key = GraphQlResponseCache.key(
                    request.getDocument(), request.getOperationName(), request.getVariables(), principal);
            Instant now = cache.clock().instant();
            Entry entry = cache.get(key);
            if (entry != null && entry.fresh(now)) {
                return Mono.just(serve(request, entry, now, Outcome.HIT));
            }
            if (entry != null && entry.servableStale(now)) {
                refresh(request, chain, plan, key, entry, view);
                return Mono.just(serve(request, entry, now, Outcome.STALE));
            }
            return chain.next(request).map(response -> {
                cache.record(Outcome.MISS);
                boolean stored = store(key, plan, response);
                response.getResponseHeaders()
                        .set(CACHE_STATUS, stored ? "shiguchi; fwd=miss; stored" : "shiguchi; fwd=miss");
                return response;
            });
        }));
    }

    private void evictAfter(Plan plan) {
        if (plan.evictAll()) {
            cache.invalidateAll();
        } else {
            cache.evictServices(plan.services());
        }
    }

    /** The key's caller part: empty when the entry is shared, else the authenticated name. */
    private Mono<String> principal(Plan plan) {
        if (!plan.perCaller()) {
            return Mono.just("");
        }
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .defaultIfEmpty(ANONYMOUS);
    }

    private WebGraphQlResponse serve(WebGraphQlRequest request, Entry entry, Instant now, Outcome outcome) {
        cache.record(outcome);
        WebGraphQlResponse response =
                new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(request.toExecutionInput(), entry.result));
        response.getResponseHeaders()
                .set(CACHE_STATUS, outcome == Outcome.STALE ? "shiguchi; hit; detail=stale" : "shiguchi; hit");
        response.getResponseHeaders().set(AGE, Long.toString(entry.ageSeconds(now)));
        return response;
    }

    /** Stores a response with data and no errors; anything else is never cached. */
    private boolean store(String key, Plan plan, WebGraphQlResponse response) {
        if (!response.isValid()) {
            return false;
        }
        cache.store(key, response.getExecutionResult(), plan.services(), plan.ttl());
        return true;
    }

    /**
     * One background execution per stale entry: the first caller to find it expired runs the
     * request again under its own Reactor context (security included) and replaces the entry;
     * concurrent callers keep being served the stale copy until it lands.
     */
    private void refresh(WebGraphQlRequest request, Chain chain, Plan plan, String key, Entry entry, ContextView view) {
        if (!entry.refreshing.compareAndSet(false, true)) {
            return;
        }
        chain.next(request)
                .contextWrite(c -> c.putAll(view))
                .subscribe(
                        response -> {
                            if (!store(key, plan, response)) {
                                entry.refreshing.set(false);
                            }
                        },
                        error -> {
                            entry.refreshing.set(false);
                            log.debug("GraphQL response cache refresh failed: {}", error.toString());
                        });
    }
}
