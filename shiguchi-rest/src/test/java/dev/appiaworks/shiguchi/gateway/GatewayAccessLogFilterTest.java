package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.appiaworks.shiguchi.config.AccessLogConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.net.InetSocketAddress;
import java.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The access log as a WebFlux filter wrapping the whole pipeline: which requests it logs, the
 * fields of a line in both formats, and the principal recorded after authentication.
 */
@DisplayName("GatewayAccessLogFilter")
class GatewayAccessLogFilterTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger accessLog;

    @BeforeEach
    void captureLog() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        accessLog = context.getLogger("dev.appiaworks.shiguchi.access");
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        accessLog.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        accessLog.detachAppender(appender);
    }

    private static GatewayAccessLogFilter filter(AccessLogConfig.Format format) {
        GatewayProperties properties = new GatewayProperties();
        properties.getAccessLog().setEnabled(true);
        properties.getAccessLog().setFormat(format);
        return new GatewayAccessLogFilter(properties);
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));
    }

    /** A chain that does what the pipeline would: set attributes and a status. */
    private static WebFilterChain chain(HttpStatus status, String... attributes) {
        return exchange -> {
            for (int i = 0; i < attributes.length; i += 2) {
                exchange.getAttributes().put(attributes[i], attributes[i + 1]);
            }
            exchange.getResponse().setStatusCode(status);
            return Mono.empty();
        };
    }

    private String onlyLine() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("GIVEN the filter WHEN ordered SHOULD run before Spring Security, rate limiting and gRPC-Web")
    void given_filter_when_ordered_should_wrapSecurityRateLimitAndGrpcWeb() {
        int order = filter(AccessLogConfig.Format.COMMON).getOrder();

        assertThat(order)
                .isGreaterThan(RequestIdFilter.ORDER)
                .isLessThan(-100)
                .isLessThan(dev.appiaworks.shiguchi.ratelimit.GatewayRateLimitFilter.ORDER)
                .isLessThan(GrpcWebFilter.ORDER);
        assertThat(GatewayAccessLogFilter.PrincipalCapture.ORDER).isBetween(-100, -91);
    }

    @Test
    @DisplayName("GIVEN a transcoded REST call WHEN it completes SHOULD log route, statuses and protocol rest")
    void given_restRoute_when_completes_should_logRouteAndProtocolRest() {
        var exchange =
                exchange(MockServerHttpRequest.get("/v1/books/1?view=full").header("User-Agent", "curl/8"));

        filter(AccessLogConfig.Format.COMMON)
                .filter(
                        exchange,
                        chain(
                                HttpStatus.OK,
                                GatewayAccessLogFilter.ROUTE_ATTR,
                                "lib.Library/GetBook",
                                GatewayAccessLogFilter.GRPC_STATUS_ATTR,
                                "OK"))
                .block();

        assertThat(onlyLine())
                .startsWith("127.0.0.1 GET /v1/books/1?view=full route=lib.Library/GetBook status=200 grpc=OK ")
                .contains("ua=\"curl/8\"")
                .endsWith("proto=rest");
    }

    @Test
    @DisplayName("GIVEN a gRPC-Web call WHEN it completes SHOULD log protocol grpc-web in JSON")
    void given_grpcWebCall_when_completes_should_logProtocolGrpcWeb() {
        var exchange = exchange(MockServerHttpRequest.post("/lib.Library/GetBook"));

        filter(AccessLogConfig.Format.JSON)
                .filter(
                        exchange,
                        chain(
                                HttpStatus.OK,
                                GatewayAccessLogFilter.ROUTE_ATTR,
                                "lib.Library/GetBook",
                                GatewayAccessLogFilter.PROTOCOL_ATTR,
                                "grpc-web",
                                GatewayAccessLogFilter.GRPC_STATUS_ATTR,
                                "NOT_FOUND"))
                .block();

        assertThat(onlyLine())
                .contains("\"route\":\"lib.Library/GetBook\"")
                .contains("\"grpcStatus\":\"NOT_FOUND\"")
                .endsWith("\"protocol\":\"grpc-web\"}");
    }

    @Test
    @DisplayName("GIVEN a request rejected before routing with 401 or 429 WHEN it completes SHOULD log it")
    void given_rejectionBeforeRouting_when_completes_should_logIt() {
        var filter = filter(AccessLogConfig.Format.COMMON);

        filter.filter(exchange(MockServerHttpRequest.get("/v1/books/1")), chain(HttpStatus.UNAUTHORIZED))
                .block();
        filter.filter(exchange(MockServerHttpRequest.get("/v1/books/1")), chain(HttpStatus.TOO_MANY_REQUESTS))
                .block();

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("route=- status=401")
                .endsWith("proto=http");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("status=429");
    }

    @Test
    @DisplayName("GIVEN a plain Spring Cloud Gateway route WHEN it completes SHOULD log it")
    void given_scgRoute_when_completes_should_logIt() {
        filter(AccessLogConfig.Format.COMMON)
                .filter(
                        exchange(MockServerHttpRequest.get("/legacy/x")),
                        chain(HttpStatus.OK, ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, "legacy"))
                .block();

        assertThat(onlyLine()).contains("/legacy/x route=- status=200");
    }

    @Test
    @DisplayName("GIVEN an unrouted request such as a static asset or probe WHEN it completes SHOULD not log it")
    void given_unroutedRequest_when_completes_should_notLog() {
        filter(AccessLogConfig.Format.COMMON)
                .filter(exchange(MockServerHttpRequest.get("/actuator/health")), chain(HttpStatus.OK))
                .block();

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("GIVEN X-Forwarded-For WHEN logged SHOULD use its first hop as the remote IP")
    void given_forwardedFor_when_logged_should_useFirstHop() {
        var exchange = exchange(MockServerHttpRequest.get("/v1/x").header("X-Forwarded-For", "203.0.113.7, 10.0.0.1"));

        filter(AccessLogConfig.Format.COMMON)
                .filter(exchange, chain(HttpStatus.OK, GatewayAccessLogFilter.ROUTE_ATTR, "a.B/C"))
                .block();

        assertThat(onlyLine()).startsWith("203.0.113.7 GET /v1/x");
    }

    @Test
    @DisplayName("GIVEN values with quotes and control characters WHEN logged as JSON SHOULD escape them")
    void given_specialCharacters_when_loggedAsJson_should_escape() {
        var exchange = exchange(MockServerHttpRequest.get("/v1/x").header("User-Agent", "a\"b\\c"));

        filter(AccessLogConfig.Format.JSON)
                .filter(exchange, chain(HttpStatus.OK, GatewayAccessLogFilter.ROUTE_ATTR, "a.B/C"))
                .block();

        assertThat(onlyLine()).contains("\"userAgent\":\"a\\\"b\\\\c\"").contains("\"query\":\"\"");
    }

    @Test
    @DisplayName(
            "GIVEN an authenticated request WHEN the principal capture ran after security SHOULD log the principal's name")
    void given_authenticatedRequest_when_captured_should_logPrincipal() {
        Principal alice = () -> "alice";
        ServerWebExchange exchange = exchange(MockServerHttpRequest.get("/v1/x"))
                .mutate()
                .principal(Mono.just(alice))
                .build();
        var capture = new GatewayAccessLogFilter.PrincipalCapture();

        filter(AccessLogConfig.Format.COMMON)
                .filter(
                        exchange,
                        e -> capture.filter(e, chain(HttpStatus.OK, GatewayAccessLogFilter.ROUTE_ATTR, "a.B/C")))
                .block();

        assertThat(onlyLine()).contains("principal=alice");
    }

    @Test
    @DisplayName("GIVEN no principal WHEN logged SHOULD print a dash")
    void given_anonymous_when_logged_should_printDash() {
        filter(AccessLogConfig.Format.COMMON)
                .filter(
                        exchange(MockServerHttpRequest.get("/v1/x")),
                        e -> new GatewayAccessLogFilter.PrincipalCapture()
                                .filter(e, chain(HttpStatus.OK, GatewayAccessLogFilter.ROUTE_ATTR, "a.B/C")))
                .block();

        assertThat(onlyLine()).contains("principal=-").matches(".* \\d+ms .*");
    }
}
