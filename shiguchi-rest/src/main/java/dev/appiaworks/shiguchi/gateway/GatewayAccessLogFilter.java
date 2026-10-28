package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.config.AccessLogConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.net.InetSocketAddress;
import java.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Enterprise access log: one line per request on the dedicated logger
 * {@code dev.appiaworks.shiguchi.access} — remote IP ({@code X-Forwarded-For}-aware), HTTP method,
 * path, matched gRPC route, HTTP status, gRPC status, duration, principal, user agent, request
 * id and protocol.
 *
 * <p>Enable with {@code shiguchi.access-log.enabled=true}; choose {@code COMMON} (text)
 * or {@code JSON} (one object per line, ELK/Splunk-ready) via
 * {@code shiguchi.access-log.format}. Ship the logger to a file or collector through
 * your logging backend's appender configuration.
 *
 * <p>A WebFlux {@link WebFilter} ordered ahead of security, rate limiting and gRPC-Web, so it
 * wraps the whole pipeline: the duration covers everything, gRPC-Web calls (which never reach a
 * gateway route) are logged, and so are requests turned away before routing (401, 403, 429).
 * What it logs: requests that matched a transcoded or gRPC-Web method, requests that matched any
 * Spring Cloud Gateway route, and those rejections. Everything else — the console's static
 * assets, actuator probes — is left out, as it was when this was a gateway route filter.
 *
 * <p>The principal is recorded by {@link PrincipalCapture}, which runs after authentication:
 * this filter runs before it, where the exchange has no principal yet.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.access-log", name = "enabled", havingValue = "true")
public class GatewayAccessLogFilter implements WebFilter, Ordered {

    /** After {@link RequestIdFilter} and the security headers, before Spring Security (-100). */
    public static final int ORDER = GatewaySecurityHeadersFilter.ORDER + 10;

    /** Exchange attribute set by {@link ShiguchiFilter}: matched route as "service/method". */
    public static final String ROUTE_ATTR = "gsg.route";
    /** Exchange attribute set by {@link ShiguchiFilter}: final gRPC status code name. */
    public static final String GRPC_STATUS_ATTR = "gsg.grpcStatus";
    /** Exchange attribute: the protocol a routed request spoke, {@code rest} or {@code grpc-web}. */
    public static final String PROTOCOL_ATTR = "gsg.protocol";

    static final String PRINCIPAL_ATTR = "gsg.principal";

    /** Set by Spring Cloud Gateway when a request matched one of its routes. */
    private static final String SCG_ROUTE_ATTR =
            org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

    private static final Logger accessLog = LoggerFactory.getLogger("dev.appiaworks.shiguchi.access");

    private final AccessLogConfig config;

    public GatewayAccessLogFilter(GatewayProperties properties) {
        this.config = properties.getAccessLog();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            if (logged(exchange)) log(exchange, startNanos);
        });
    }

    /** Routed traffic and the rejections that stopped a request before it could be routed. */
    static boolean logged(ServerWebExchange exchange) {
        var attributes = exchange.getAttributes();
        if (attributes.containsKey(ROUTE_ATTR) || attributes.containsKey(SCG_ROUTE_ATTR)) return true;
        var status = exchange.getResponse().getStatusCode();
        if (status == null) return false;
        int code = status.value();
        return code == 401 || code == 403 || code == 429;
    }

    private void log(ServerWebExchange exchange, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        ServerHttpRequest request = exchange.getRequest();

        String remoteIp = remoteIp(request);
        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();
        String query = request.getURI().getRawQuery();
        String route = (String) exchange.getAttributes().getOrDefault(ROUTE_ATTR, "-");
        String grpcStatus = (String) exchange.getAttributes().getOrDefault(GRPC_STATUS_ATTR, "-");
        String principal = (String) exchange.getAttributes().getOrDefault(PRINCIPAL_ATTR, "-");
        String protocol = (String) exchange.getAttributes()
                .getOrDefault(PROTOCOL_ATTR, exchange.getAttributes().containsKey(ROUTE_ATTR) ? "rest" : "http");
        int status = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value()
                : 0;
        String userAgent = header(request, "User-Agent");
        // The id RequestIdFilter settled on, which is the one in every other log line for this
        // request and the one echoed to the caller. Falling back to the raw header keeps the
        // access log working when that filter is switched off.
        Object assignedId = exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR);
        String requestId = assignedId instanceof String id ? id : header(request, "X-Request-Id");

        if (config.getFormat() == AccessLogConfig.Format.JSON) {
            accessLog.info("{\"remoteIp\":\"" + json(remoteIp)
                    + "\",\"method\":\"" + json(method)
                    + "\",\"path\":\"" + json(path)
                    + "\",\"query\":\"" + json(query == null ? "" : query)
                    + "\",\"route\":\"" + json(route)
                    + "\",\"status\":" + status
                    + ",\"grpcStatus\":\"" + json(grpcStatus)
                    + "\",\"durationMs\":" + durationMs
                    + ",\"principal\":\"" + json(principal)
                    + "\",\"userAgent\":\"" + json(userAgent)
                    + "\",\"requestId\":\"" + json(requestId)
                    + "\",\"protocol\":\"" + json(protocol) + "\"}");
        } else {
            accessLog.info(
                    "{} {} {}{} route={} status={} grpc={} {}ms principal={} ua=\"{}\" reqId={} proto={}",
                    remoteIp,
                    method,
                    path,
                    query != null ? "?" + query : "",
                    route,
                    status,
                    grpcStatus,
                    durationMs,
                    principal,
                    userAgent,
                    requestId,
                    protocol);
        }
    }

    /** First X-Forwarded-For hop when present (trusted-proxy deployments), else the socket peer. */
    private static String remoteIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "-";
    }

    private static String header(ServerHttpRequest request, String name) {
        String value = request.getHeaders().getFirst(name);
        return value != null ? value : "-";
    }

    /** Minimal JSON string escaping — quotes, backslashes, and control characters. */
    private static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Records the authenticated principal's name for the access log. Runs right after Spring
     * Security (-100), where the exchange carries the principal, and stores it in the exchange
     * attributes, which every mutated copy of the exchange shares — so the log filter outside
     * security can read it when the request finishes.
     */
    @Component
    @ConditionalOnProperty(prefix = "shiguchi.access-log", name = "enabled", havingValue = "true")
    public static class PrincipalCapture implements WebFilter, Ordered {

        /** After Spring Security's filter chain (-100), before rate limiting (-90). */
        public static final int ORDER = -95;

        @Override
        public int getOrder() {
            return ORDER;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            return exchange.getPrincipal()
                    .map(Principal::getName)
                    .doOnNext(name -> exchange.getAttributes().put(PRINCIPAL_ATTR, name))
                    .then(chain.filter(exchange));
        }
    }
}
