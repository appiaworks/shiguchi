package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.HeadersConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Security response headers for the pages a browser loads: the console, GraphiQL and Swagger UI
 * ({@code shiguchi.headers.*}).
 *
 * <p>Spring Security already sets the per-response basics on every exchange. The gap this fills is
 * {@code Content-Security-Policy}: Spring cannot pick one, because a useful policy depends on what
 * the page loads and a wrong one breaks it silently — but the gateway knows exactly what its own
 * bundled pages load, so it can ship a policy that fits them. {@code Permissions-Policy} and an
 * optional {@code Strict-Transport-Security} come along for the same reason.
 *
 * <p>Scoped to {@link GatewayCorsConfiguration#defaultBrowserPaths} — the gateway's one definition
 * of "browser surface", shared with CORS so the two cannot drift. Transcoded REST routes get
 * nothing: they are an API for programs, where a framing header protects nobody.
 *
 * <p>Headers a caller (or a proxy in front) already set are left alone, so this never fights an
 * ingress that has its own policy.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.headers", name = "enabled", havingValue = "true", matchIfMissing = true)
@Order(GatewaySecurityHeadersFilter.ORDER)
public class GatewaySecurityHeadersFilter implements WebFilter {

    /**
     * Before the security chain (-100) so the headers are on the response whatever it turns out to
     * be — a 401 from the security chain is still a page a browser renders.
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

    public static final String CSP = "Content-Security-Policy";
    public static final String REFERRER_POLICY = "Referrer-Policy";
    public static final String PERMISSIONS_POLICY = "Permissions-Policy";
    public static final String HSTS = "Strict-Transport-Security";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<String> paths;
    private final Map<String, String> headers;
    private final String hsts;

    public GatewaySecurityHeadersFilter(GatewayProperties properties) {
        HeadersConfig config = properties.getHeaders();
        this.paths = GatewayCorsConfiguration.defaultBrowserPaths(properties);

        Map<String, String> resolved = new LinkedHashMap<>();
        putIfSet(resolved, CSP, config.getContentSecurityPolicy());
        putIfSet(resolved, REFERRER_POLICY, config.getReferrerPolicy());
        putIfSet(resolved, PERMISSIONS_POLICY, config.getPermissionsPolicy());
        config.getCustom().forEach((name, value) -> putIfSet(resolved, name, value));
        this.headers = Map.copyOf(resolved);

        // HSTS is separate: it is only meaningful over HTTPS, and sending it on a plaintext
        // request is at best ignored and at worst misleading.
        this.hsts = config.getStrictTransportSecurity() == null
                ? ""
                : config.getStrictTransportSecurity().trim();
    }

    private static void putIfSet(Map<String, String> target, String name, String value) {
        if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
            target.put(name.trim(), value.trim());
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!applies(exchange)) return chain.filter(exchange);

        // beforeCommit, because a handler further down may set its own headers, and because the
        // response is not writable once it is committed.
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
            headers.forEach((name, value) -> {
                if (responseHeaders.getFirst(name) == null) responseHeaders.set(name, value);
            });
            if (!hsts.isEmpty() && isSecure(exchange) && responseHeaders.getFirst(HSTS) == null) {
                responseHeaders.set(HSTS, hsts);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /** Whether this request is for one of the browser surfaces (package-private for tests). */
    boolean applies(ServerWebExchange exchange) {
        if (headers.isEmpty() && hsts.isEmpty()) return false;
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        for (String pattern : paths) {
            if (MATCHER.match(pattern, path)) return true;
        }
        return false;
    }

    /**
     * HTTPS as the caller saw it: the scheme of the request, or {@code X-Forwarded-Proto} when a
     * TLS-terminating proxy sits in front and the gateway itself speaks plaintext.
     */
    private static boolean isSecure(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        if ("https".equalsIgnoreCase(request.getURI().getScheme())) return true;
        String forwarded = request.getHeaders().getFirst("X-Forwarded-Proto");
        return forwarded != null && "https".equalsIgnoreCase(forwarded.trim());
    }
}
