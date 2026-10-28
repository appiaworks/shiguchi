package dev.appiaworks.shiguchi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.DefaultGatewayErrorRenderer;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@DisplayName("GatewayRateLimitFilter")
class GatewayRateLimitFilterTest {

    private final GrpcRouteRegistry registry = new GrpcRouteRegistry();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger passed = new AtomicInteger();
    private final WebFilterChain chain = exchange -> Mono.fromRunnable(passed::incrementAndGet);
    private GatewayProperties props;

    @BeforeEach
    void routes() {
        var svc = new ServiceDefinition();
        svc.setName("inventory");
        var ctx = new ServiceEndpointContext(svc, null);
        ctx.addRoute(RouteDefinition.builder("inventory", "getItem", MethodDescriptor.MethodType.UNARY, "")
                .httpBinding("GET", "/v1/items/{sku}")
                .build());
        registry.registerContext(ctx);
        props = new GatewayProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setReplenishRate(1.0);
        props.getRateLimit().setBurstCapacity(2);
    }

    @SuppressWarnings("unchecked")
    private GatewayRateLimitFilter filter(RateLimiter limiter) {
        ObjectProvider<MeterRegistry> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any()))
                .thenReturn(meters);
        return new GatewayRateLimitFilter(
                props,
                limiter,
                new DefaultRateLimitKeyResolver(props.getRateLimit()),
                new DefaultGatewayErrorRenderer(),
                registry,
                provider,
                "/graphql",
                "/actuator");
    }

    private static ServerWebExchange get(String path, String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(path).remoteAddress(new InetSocketAddress(ip, 1234)));
    }

    private static String body(ServerWebExchange exchange) {
        return ((MockServerWebExchange) exchange)
                .getResponse()
                .getBodyAsString()
                .block();
    }

    @Test
    @DisplayName(
            "GIVEN a burst of 2 WHEN one IP sends three requests to a REST route SHOULD pass two with remaining headers and refuse the third with 429, Retry-After and the error body")
    void given_burst_when_exceeded_should_answer429WithHeaders() {
        GatewayRateLimitFilter filter = filter(new InMemoryRateLimiter(1.0, 2));

        ServerWebExchange first = get("/v1/items/1", "10.0.0.1");
        filter.filter(first, chain).block();
        ServerWebExchange second = get("/v1/items/2", "10.0.0.1");
        filter.filter(second, chain).block();
        ServerWebExchange third = get("/v1/items/3", "10.0.0.1");
        filter.filter(third, chain).block();

        assertThat(passed.get()).isEqualTo(2);
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(third.getResponse().getHeaders().getFirst(GatewayRateLimitFilter.REMAINING_HEADER))
                .isEqualTo("0");
        assertThat(third.getResponse().getHeaders().getFirst(GatewayRateLimitFilter.LIMIT_HEADER))
                .isEqualTo("2");
        assertThat(third.getResponse().getHeaders().getFirst(GatewayRateLimitFilter.REPLENISH_HEADER))
                .isEqualTo("1");
        assertThat(body(third)).contains("\"code\":\"RESOURCE_EXHAUSTED\"").contains("1 requests per second");
        assertThat(meters.get(GatewayRateLimitFilter.METRIC)
                        .tag("outcome", "rejected")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get(GatewayRateLimitFilter.METRIC)
                        .tag("outcome", "allowed")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("GIVEN two IPs WHEN one is exhausted SHOULD still let the other through")
    void given_twoCallers_when_oneExhausted_should_isolate() {
        GatewayRateLimitFilter filter = filter(new InMemoryRateLimiter(1.0, 1));
        filter.filter(get("/v1/items/1", "10.0.0.1"), chain).block();
        ServerWebExchange refused = get("/v1/items/1", "10.0.0.1");
        filter.filter(refused, chain).block();
        ServerWebExchange other = get("/v1/items/1", "10.0.0.2");
        filter.filter(other, chain).block();

        assertThat(refused.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(other.getResponse().getStatusCode()).isNull();
        assertThat(passed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN no explicit paths WHEN the request is not a gateway route, the GraphQL endpoint or is under actuator SHOULD not be limited")
    void given_defaultPaths_when_notARoute_should_skip() {
        GatewayRateLimitFilter filter = filter(new InMemoryRateLimiter(1.0, 1));

        assertThat(filter.applies(get("/v1/items/1", "x"))).isTrue();
        assertThat(filter.applies(get("/graphql", "x"))).isTrue();
        assertThat(filter.applies(get("/swagger-ui.html", "x"))).isFalse();
        assertThat(filter.applies(get("/actuator/health", "x"))).isFalse();
        assertThat(filter.applies(get("/v1/unknown", "x"))).isFalse();
    }

    @Test
    @DisplayName("GIVEN explicit paths and exclusions WHEN matched SHOULD follow them, actuator still exempt")
    void given_explicitPaths_when_matched_should_followPatterns() {
        props.getRateLimit().setPaths(List.of("/api/**", "/actuator/**"));
        props.getRateLimit().setExcludePaths(List.of("/api/public/**"));
        GatewayRateLimitFilter filter = filter(new InMemoryRateLimiter(1.0, 1));

        assertThat(filter.applies(get("/api/orders", "x"))).isTrue();
        assertThat(filter.applies(get("/api/public/ping", "x"))).isFalse();
        assertThat(filter.applies(get("/v1/items/1", "x"))).isFalse();
        assertThat(filter.applies(get("/actuator/health", "x"))).isFalse();
    }

    @Test
    @DisplayName("GIVEN the key resolver yields nothing WHEN filtering SHOULD pass the request through unlimited")
    void given_noKey_when_filtering_should_passThrough() {
        props.getRateLimit().setKey(InboundRateLimitConfig.Key.PRINCIPAL);
        GatewayRateLimitFilter filter = filter(new InMemoryRateLimiter(1.0, 1));

        for (int i = 0; i < 3; i++)
            filter.filter(get("/v1/items/1", "10.0.0.1"), chain).block();

        assertThat(passed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN include-headers off WHEN refused SHOULD still send Retry-After but no X-RateLimit headers")
    void given_headersOff_when_refused_should_omitRateLimitHeaders() {
        props.getRateLimit().setIncludeHeaders(false);
        GatewayRateLimitFilter filter = filter(new InMemoryRateLimiter(1.0, 1));
        filter.filter(get("/v1/items/1", "10.0.0.1"), chain).block();
        ServerWebExchange refused = get("/v1/items/1", "10.0.0.1");
        filter.filter(refused, chain).block();

        assertThat(refused.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(refused.getResponse().getHeaders().getFirst(GatewayRateLimitFilter.REMAINING_HEADER))
                .isNull();
        assertThat(new String(body(refused).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .contains("RESOURCE_EXHAUSTED");
    }
}
