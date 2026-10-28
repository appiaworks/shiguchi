package dev.appiaworks.shiguchi.graphql;

import graphql.GraphQLContext;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Carries the Reactor context of the HTTP request that started a GraphQL operation to the
 * publishers the gateway's data fetchers and batch loaders build.
 *
 * <p>graphql-java runs a fetcher outside the reactive chain that carries the request: a fetcher
 * that returns a {@code CompletableFuture} (via {@code Mono.toFuture()}) subscribes with an empty
 * Reactor context, so {@code ReactiveSecurityContextHolder} would be empty and every caller would
 * be {@code anonymous} to the scope check and to the backend. {@link GatewayRequestContextInterceptor}
 * saves the request's context in the {@link GraphQLContext}; {@link #within} writes it back onto
 * the publisher before it is subscribed.
 */
final class GraphQlRequestContext {

    private static final String KEY = GraphQlRequestContext.class.getName() + ".reactorContext";

    private GraphQlRequestContext() {}

    /** Saves the request's Reactor context, and its exchange when the GraphQL context holds none. */
    static void save(GraphQLContext graphQlContext, ContextView view) {
        graphQlContext.put(KEY, view);
        if (!graphQlContext.hasKey(ServerWebExchange.class)) {
            ServerWebExchange exchange = view.<ServerWebExchange>getOrEmpty(ServerWebExchange.class)
                    .or(() -> ServerWebExchangeContextFilter.getExchange(view))
                    .orElse(null);
            if (exchange != null) {
                graphQlContext.put(ServerWebExchange.class, exchange);
            }
        }
    }

    /** The publisher run inside the saved request context; unchanged when none was saved. */
    static <T> Mono<T> within(GraphQLContext graphQlContext, Mono<T> publisher) {
        ContextView saved = saved(graphQlContext);
        return saved == null ? publisher : publisher.contextWrite(c -> c.putAll(saved));
    }

    /** As {@link #within(GraphQLContext, Mono)}, for a stream. */
    static <T> Flux<T> within(GraphQLContext graphQlContext, Flux<T> publisher) {
        ContextView saved = saved(graphQlContext);
        return saved == null ? publisher : publisher.contextWrite(c -> c.putAll(saved));
    }

    private static ContextView saved(GraphQLContext graphQlContext) {
        if (graphQlContext == null) return null;
        Object saved = graphQlContext.get(KEY);
        return saved instanceof ContextView view && !view.isEmpty() ? view : null;
    }
}
