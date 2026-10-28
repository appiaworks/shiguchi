package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import dev.appiaworks.shiguchi.gateway.GatewayAccessLogFilter;
import dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Inbound rate limiting for the REST and GraphQL surfaces ({@code shiguchi.rate-limit.*}).
 * Runs after Spring Security (so the principal is known) and before any route handling: the
 * {@link RateLimitKeyResolver} names the caller, the {@link RateLimiter} takes the tokens, and a
 * refused request is answered 429 {@code RESOURCE_EXHAUSTED} through the error renderer with
 * {@code Retry-After} — the backend never sees it. Limited responses carry
 * {@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining}, {@code X-RateLimit-Replenish-Rate}
 * and {@code X-RateLimit-Burst-Capacity} unless {@code include-headers} is off.
 *
 * <p>Which paths are limited: {@code paths} when set (Ant patterns), else every transcoded REST
 * route, every {@code REST_DOWNSTREAM} route and the GraphQL endpoint; {@code exclude-paths}
 * and the actuator base path are never limited. Outcomes are counted in
 * {@code gateway.ratelimit.requests{outcome=allowed|rejected}}.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.rate-limit", name = "enabled", havingValue = "true")
@Order(GatewayRateLimitFilter.ORDER)
public class GatewayRateLimitFilter implements WebFilter {

    /** After Spring Security's {@code WebFilterChainProxy} (-100), before everything else. */
    public static final int ORDER = -90;

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String REPLENISH_HEADER = "X-RateLimit-Replenish-Rate";
    public static final String BURST_HEADER = "X-RateLimit-Burst-Capacity";
    public static final String METRIC = "gateway.ratelimit.requests";

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final InboundRateLimitConfig config;
    private final RateLimiter limiter;
    private final RateLimitKeyResolver keyResolver;
    private final GatewayErrorRenderer errorRenderer;
    private final GrpcRouteRegistry registry;
    private final String graphqlPath;
    private final String actuatorBasePath;
    private final Counter allowed;
    private final Counter rejected;

    public GatewayRateLimitFilter(
            GatewayProperties properties,
            RateLimiter limiter,
            RateLimitKeyResolver keyResolver,
            GatewayErrorRenderer errorRenderer,
            GrpcRouteRegistry registry,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${spring.graphql.http.path:${spring.graphql.path:/graphql}}") String graphqlPath,
            @Value("${management.endpoints.web.base-path:/actuator}") String actuatorBasePath) {
        this.config = properties.getRateLimit();
        this.limiter = limiter;
        this.keyResolver = keyResolver;
        this.errorRenderer = errorRenderer;
        this.registry = registry;
        this.graphqlPath = graphqlPath;
        this.actuatorBasePath = actuatorBasePath;
        MeterRegistry meters = meterRegistry.getIfAvailable(() -> Metrics.globalRegistry);
        this.allowed = Counter.builder(METRIC).tag("outcome", "allowed").register(meters);
        this.rejected = Counter.builder(METRIC).tag("outcome", "rejected").register(meters);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!applies(exchange)) return chain.filter(exchange);
        // chain.filter is a Mono<Void>, i.e. always empty: a switchIfEmpty after it would run the
        // chain a second time. Branch on the resolved key instead.
        return keyResolver
                .resolve(exchange)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(key -> key.isEmpty()
                        ? chain.filter(exchange)
                        : limiter.tryAcquire(key.get(), config.getRequestedTokens())
                                .flatMap(decision -> decision.allowed()
                                        ? allow(exchange, chain, decision)
                                        : refuse(exchange, key.get(), decision)));
    }

    /** Whether this request is subject to limiting (package-private for tests). */
    boolean applies(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (path.startsWith(actuatorBasePath + "/") || path.equals(actuatorBasePath)) return false;
        for (String pattern : config.getExcludePaths()) {
            if (MATCHER.match(pattern, path)) return false;
        }
        List<String> paths = config.getPaths();
        if (!paths.isEmpty()) {
            for (String pattern : paths) {
                if (MATCHER.match(pattern, path)) return true;
            }
            return false;
        }
        if (path.equals(graphqlPath)) return true;
        return registry.findByHttpKey(exchange.getRequest().getMethod().name(), path)
                .isPresent();
    }

    private Mono<Void> allow(ServerWebExchange exchange, WebFilterChain chain, RateLimiter.Decision decision) {
        allowed.increment();
        if (config.isIncludeHeaders()) {
            exchange.getResponse().beforeCommit(() -> {
                headers(exchange.getResponse().getHeaders(), decision.remaining());
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    private Mono<Void> refuse(ServerWebExchange exchange, String key, RateLimiter.Decision decision) {
        rejected.increment();
        log.debug(
                "Rate limit exceeded for {} on {} {}",
                key,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath());
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        HttpHeaders headers = response.getHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        if (config.isIncludeHeaders()) headers(headers, 0);
        headers.setContentType(errorRenderer.contentType());
        exchange.getAttributes().put(GatewayAccessLogFilter.GRPC_STATUS_ATTR, Status.Code.RESOURCE_EXHAUSTED.name());
        Status status = Status.RESOURCE_EXHAUSTED.withDescription("Rate limit exceeded: "
                + trim(config.getReplenishRate()) + " requests per second, bursts of " + config.getBurstCapacity()
                + "; retry in " + decision.retryAfterSeconds() + "s");
        byte[] body = errorRenderer.render(HttpStatus.TOO_MANY_REQUESTS, status, exchange);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private void headers(HttpHeaders headers, long remaining) {
        headers.set(LIMIT_HEADER, Integer.toString(config.getBurstCapacity()));
        headers.set(REMAINING_HEADER, Long.toString(remaining));
        headers.set(REPLENISH_HEADER, trim(config.getReplenishRate()));
        headers.set(BURST_HEADER, Integer.toString(config.getBurstCapacity()));
    }

    private static String trim(double rate) {
        return rate == Math.rint(rate) ? Long.toString((long) rate) : Double.toString(rate);
    }
}
