package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

@DisplayName("GatewaySecurityHeadersFilter")
class GatewaySecurityHeadersFilterTest {

    private final GatewayProperties properties = new GatewayProperties();

    /** Commits the response, which is when beforeCommit hooks run. */
    private final WebFilterChain chain = exchange -> exchange.getResponse().setComplete();

    private HttpHeaders headersFor(ServerWebExchange exchange) {
        new GatewaySecurityHeadersFilter(properties).filter(exchange, chain).block();
        return exchange.getResponse().getHeaders();
    }

    private static ServerWebExchange get(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    @Test
    @DisplayName("GIVEN a browser surface WHEN it responds SHOULD carry the CSP, referrer and permissions headers")
    void given_browserSurface_when_responding_should_sendHeaders() {
        HttpHeaders headers = headersFor(get("/grpc-ui/index.html"));

        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.CSP))
                .contains("default-src 'self'")
                .contains("frame-ancestors 'none'");
        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.REFERRER_POLICY))
                .isEqualTo("no-referrer");
        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.PERMISSIONS_POLICY))
                .contains("camera=()");
    }

    @Test
    @DisplayName("GIVEN a transcoded REST route WHEN it responds SHOULD send none of them, since no browser renders it")
    void given_restRoute_when_responding_should_sendNothing() {
        HttpHeaders headers = headersFor(get("/v1/items/1"));

        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.CSP)).isNull();
        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.PERMISSIONS_POLICY))
                .isNull();
    }

    @Test
    @DisplayName("GIVEN every browser surface WHEN matched SHOULD cover the console, GraphiQL, GraphQL and Swagger")
    void given_browserSurfaces_when_matching_should_coverThemAll() {
        var filter = new GatewaySecurityHeadersFilter(properties);

        assertThat(filter.applies(get("/grpc-ui/"))).isTrue();
        assertThat(filter.applies(get("/graphiql"))).isTrue();
        assertThat(filter.applies(get("/graphql"))).isTrue();
        assertThat(filter.applies(get("/swagger-ui.html"))).isTrue();
        assertThat(filter.applies(get("/v3/api-docs/swagger-config"))).isTrue();
        assertThat(filter.applies(get("/v1/orders"))).isFalse();
        assertThat(filter.applies(get("/actuator/health"))).isFalse();
    }

    @Test
    @DisplayName("GIVEN a custom UI path WHEN the console responds SHOULD follow it, the way CORS does")
    void given_customUiPath_when_responding_should_followIt() {
        properties.getUi().setPath("/console");
        var filter = new GatewaySecurityHeadersFilter(properties);

        assertThat(filter.applies(get("/console/index.html"))).isTrue();
        assertThat(filter.applies(get("/grpc-ui/index.html"))).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a header already set upstream WHEN the filter runs SHOULD leave it alone rather than fight the proxy")
    void given_headerAlreadySet_when_filtering_should_notOverwrite() {
        ServerWebExchange exchange = get("/graphiql");
        exchange.getResponse().getHeaders().set(GatewaySecurityHeadersFilter.CSP, "default-src 'none'");

        HttpHeaders headers = headersFor(exchange);

        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.CSP)).isEqualTo("default-src 'none'");
    }

    @Test
    @DisplayName("GIVEN HSTS is unset WHEN responding SHOULD omit it, because a wrong max-age outlives the mistake")
    void given_hstsUnset_when_responding_should_omitIt() {
        assertThat(headersFor(get("/grpc-ui/")).getFirst(GatewaySecurityHeadersFilter.HSTS))
                .isNull();
    }

    @Test
    @DisplayName(
            "GIVEN HSTS is configured WHEN the request is plaintext SHOULD omit it, and send it once the request is HTTPS")
    void given_hstsConfigured_when_plaintext_should_omitUntilHttps() {
        properties.getHeaders().setStrictTransportSecurity("max-age=31536000");

        assertThat(headersFor(get("/grpc-ui/")).getFirst(GatewaySecurityHeadersFilter.HSTS))
                .isNull();

        ServerWebExchange https =
                MockServerWebExchange.from(MockServerHttpRequest.get("https://gw.example.com/grpc-ui/"));
        assertThat(headersFor(https).getFirst(GatewaySecurityHeadersFilter.HSTS))
                .isEqualTo("max-age=31536000");
    }

    @Test
    @DisplayName("GIVEN TLS terminated by a proxy WHEN it forwards the protocol SHOULD send HSTS on the plaintext hop")
    void given_forwardedProto_when_https_should_sendHsts() {
        properties.getHeaders().setStrictTransportSecurity("max-age=600");
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/grpc-ui/").header("X-Forwarded-Proto", "https"));

        assertThat(headersFor(exchange).getFirst(GatewaySecurityHeadersFilter.HSTS))
                .isEqualTo("max-age=600");
    }

    @Test
    @DisplayName("GIVEN a blank policy WHEN responding SHOULD omit that header instead of sending an empty one")
    void given_blankPolicy_when_responding_should_omitHeader() {
        properties.getHeaders().setContentSecurityPolicy("");
        properties.getHeaders().setPermissionsPolicy("  ");

        HttpHeaders headers = headersFor(get("/grpc-ui/"));

        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.CSP)).isNull();
        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.PERMISSIONS_POLICY))
                .isNull();
        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.REFERRER_POLICY))
                .isEqualTo("no-referrer");
    }

    @Test
    @DisplayName("GIVEN custom headers WHEN responding SHOULD send them verbatim alongside the defaults")
    void given_customHeaders_when_responding_should_sendThem() {
        properties.getHeaders().getCustom().put("Cross-Origin-Opener-Policy", "same-origin");

        HttpHeaders headers = headersFor(get("/graphql"));

        assertThat(headers.getFirst("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(headers.getFirst(GatewaySecurityHeadersFilter.CSP)).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN the chain answers 401 WHEN the response commits SHOULD still carry the headers, since a browser renders that too")
    void given_unauthorized_when_committing_should_stillSendHeaders() {
        ServerWebExchange exchange = get("/grpc-ui/");
        WebFilterChain rejecting = e -> {
            e.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return e.getResponse().setComplete();
        };

        new GatewaySecurityHeadersFilter(properties).filter(exchange, rejecting).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(GatewaySecurityHeadersFilter.CSP))
                .isNotNull();
    }
}
