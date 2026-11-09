package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GraphQlConfig;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Serves {@code query ... @live}: the operation runs, and runs again whenever a service behind
 * its root fields is invalidated (a mutation through this gateway, see
 * {@link GraphQlLiveInvalidationInterceptor}) or {@code refresh-interval} passes without news. The
 * answer is a stream of full results, so the transport treats it like a subscription: over
 * {@code graphql-transport-ws} one {@code next} per result, over SSE one event.
 *
 * <p>Each run goes through the interceptors after this one, the limits and the cost budget
 * included, with the credentials of the caller who started the stream. Two runs are at least
 * {@code max(min-interval, throttleMs)} apart, signals that arrive in between collapse into one
 * run, and a result equal to the one before is not sent. A result without data (the operation
 * itself was refused) ends the stream, except for a refused budget, which the next signal retries.
 * On any other transport {@code @live} is ignored and the query runs once.
 */
class GraphQlLiveInterceptor implements WebGraphQlInterceptor, Ordered {

    static final String LIMIT_CLASSIFICATION = "LIVE_LIMIT";

    private final GraphQlLiveDependencies dependencies;
    private final GraphQlLiveInvalidations invalidations;
    private final GraphQlLiveStreams streams;
    private final GraphQlConfig.LiveConfig config;

    GraphQlLiveInterceptor(
            GraphQlLiveDependencies dependencies,
            GraphQlLiveInvalidations invalidations,
            GraphQlLiveStreams streams,
            GraphQlConfig.LiveConfig config) {
        this.dependencies = dependencies;
        this.invalidations = invalidations;
        this.streams = streams;
        this.config = config;
    }

    @Override
    public int getOrder() {
        // outside the limits interceptor (HIGHEST_PRECEDENCE + 100), so every run is priced again
        return Ordered.HIGHEST_PRECEDENCE + 90;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (!StringUtils.hasText(request.getDocument()) || !streamable(request)) {
            return chain.next(request);
        }
        GraphQlLiveDependencies.Shape shape = dependencies.shape(request.getDocument(), request.getOperationName());
        if (shape == null || !shape.live() || shape.type() != graphql.language.OperationDefinition.Operation.QUERY) {
            return chain.next(request);
        }
        return principal().map(principal -> {
            GraphQlLiveStreams.Lease lease = streams.acquire(principal);
            if (lease == null) return refused(request);
            Duration throttle = throttle(shape.throttleMs());
            Flux<ExecutionResult> results = triggers(shape.services(), throttle)
                    .concatMap(
                            trigger -> run(request, chain)
                                    .concatWith(Mono.delay(throttle).then(Mono.empty())),
                            1)
                    .distinctUntilChanged(GraphQlLiveInterceptor::content)
                    .index((i, result) -> numbered(result, i + 1))
                    .takeUntil(GraphQlLiveInterceptor::endsStream)
                    .doFinally(signal -> lease.release());
            return response(
                    request,
                    ExecutionResultImpl.newExecutionResult().data(results).build());
        });
    }

    private static boolean streamable(WebGraphQlRequest request) {
        if (request instanceof WebSocketGraphQlRequest) return true;
        for (MediaType accepted : request.getHeaders().getAccept()) {
            if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(accepted)
                    && accepted.getType().equalsIgnoreCase("text")) {
                return true;
            }
        }
        return false;
    }

    private Duration throttle(long hintMs) {
        Duration hint = hintMs > 0 ? Duration.ofMillis(hintMs) : Duration.ZERO;
        return hint.compareTo(config.getMinInterval()) > 0 ? hint : config.getMinInterval();
    }

    /** One value to run now, then one per relevant signal and per refresh, the pending ones merged into one. */
    private Flux<Object> triggers(Set<String> dependsOn, Duration throttle) {
        Flux<Object> signals = invalidations
                .signals()
                .filter(services -> GraphQlLiveDependencies.intersects(dependsOn, services))
                .map(services -> (Object) services);
        Flux<Object> refresh = config.getRefreshInterval().isZero()
                        || config.getRefreshInterval().isNegative()
                ? Flux.never()
                : Flux.interval(config.getRefreshInterval()).map(i -> (Object) i);
        return Flux.merge(Mono.just((Object) "start"), signals, refresh).onBackpressureLatest();
    }

    private static Mono<ExecutionResult> run(WebGraphQlRequest request, Chain chain) {
        return Mono.defer(() -> chain.next(request))
                .map(response -> response.getExecutionResult())
                .filter(result -> !(result.getData() instanceof Publisher));
    }

    private Mono<String> principal() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .defaultIfEmpty("anonymous");
    }

    /** What makes two results the same: data and errors, not the extensions (cost, sequence). */
    private static Map<String, Object> content(ExecutionResult result) {
        Map<String, Object> spec = new HashMap<>(result.toSpecification());
        spec.remove("extensions");
        return spec;
    }

    private static ExecutionResult numbered(ExecutionResult result, long seq) {
        Map<Object, Object> extensions = new HashMap<>();
        if (result.getExtensions() != null) extensions.putAll(result.getExtensions());
        extensions.put("live", Map.of("seq", seq));
        return ExecutionResultImpl.newExecutionResult()
                .from(result)
                .extensions(extensions)
                .build();
    }

    /** A result that ends the stream: the operation was refused outright, or the credential ended. */
    private static boolean endsStream(ExecutionResult result) {
        List<GraphQLError> errors = result.getErrors();
        if (errors == null || errors.isEmpty()) return false;
        for (GraphQLError error : errors) {
            Map<String, Object> extensions = error.getExtensions();
            if (extensions != null && "unauthenticated".equals(extensions.get("code"))) return true;
        }
        if (result.getData() != null) return false;
        for (GraphQLError error : errors) {
            Map<String, Object> extensions = error.getExtensions();
            Object classification = extensions == null ? null : extensions.get("classification");
            if ("RATE_LIMITED".equals(String.valueOf(classification))) return false;
        }
        return true;
    }

    private static WebGraphQlResponse refused(WebGraphQlRequest request) {
        GraphQLError error = GraphqlErrorBuilder.newError()
                .message("too many live queries")
                .extensions(Map.of("classification", LIMIT_CLASSIFICATION))
                .build();
        return response(
                request,
                ExecutionResultImpl.newExecutionResult().addError(error).build());
    }

    private static WebGraphQlResponse response(WebGraphQlRequest request, ExecutionResult result) {
        return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(request.toExecutionInput(), result));
    }
}
