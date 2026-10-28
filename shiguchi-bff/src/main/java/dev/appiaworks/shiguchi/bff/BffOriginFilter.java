package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.GatewayRequestAttributes;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The cross-site request forgery check for cookie-authenticated callers, which the gateway's chain
 * leaves off (its CSRF support is disabled because bearer callers send no ambient credential).
 * Runs right after authentication. A request that is not cookie-authenticated (a bearer caller, an
 * anonymous one) passes untouched; so does a safe method ({@code GET}, {@code HEAD}, {@code OPTIONS},
 * which a CORS preflight is and which carries no cookie) that has no {@code Origin} header.
 *
 * <p>Every other cookie request, and every cookie WebSocket handshake whatever its method, needs an
 * {@code Origin} header that is exactly one of the origins of <em>the session's own</em>
 * application: else {@code 403 {"error":"origin not allowed"}}. A cookie request on a safe method
 * that carries an {@code Origin} is held to the same set (so application B's page cannot read
 * application A's session with {@code fetch(..., {credentials: 'include'})}), {@code Origin: null}
 * included; without an {@code Origin} it passes, since the browser then shows the response only to
 * the page's own origin. A session whose application is unknown (a third-party
 * authenticator that does not record one) is held to the origins of any configured application.
 *
 * <p>On the REST/JSON surface a body without a JSON-like content type ({@code text/plain}, form
 * encodings, or no content type at all) is refused with 415, since a browser sends those without a
 * CORS preflight. gRPC-Web (its content type forces a preflight), GraphQL (its own path) and
 * {@code POST <bff path>/logout} (a plain form post is a valid logout; the {@code Origin} rule still
 * holds for it) are exempt from that second rule only.
 *
 * <p>With {@code csrf-header.name} set, every cookie request on an unsafe method (a WebSocket upgrade
 * excepted: browsers cannot set headers on one) must also carry that header with exactly the
 * configured value, else {@code 403 {"error":"csrf header required"}}. The header forces a CORS
 * preflight, so the content-type rule is skipped while it is required (a logout form post then needs
 * the header too).
 */
final class BffOriginFilter implements WebFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> FORBIDDEN_TYPES =
            Set.of("text/plain", "application/x-www-form-urlencoded", "multipart/form-data");

    private final Map<String, Set<String>> originsByApplication = new LinkedHashMap<>();
    private final Set<String> anyOrigin = new HashSet<>();
    private final String logoutPath;
    private final String graphqlPath;
    private final String csrfHeaderName;
    private final String csrfHeaderValue;

    BffOriginFilter(BffApplications applications, String bffPath, String graphqlPath) {
        this(applications, bffPath, graphqlPath, null, null);
    }

    /**
     * @param csrfHeaderName header every cookie-authenticated unsafe request must carry, or blank for none
     * @param csrfHeaderValue the exact value it must have
     */
    BffOriginFilter(
            BffApplications applications,
            String bffPath,
            String graphqlPath,
            String csrfHeaderName,
            String csrfHeaderValue) {
        this.csrfHeaderName = csrfHeaderName == null || csrfHeaderName.isBlank() ? null : csrfHeaderName.trim();
        this.csrfHeaderValue = csrfHeaderValue == null ? "1" : csrfHeaderValue;
        for (BffApplications.Resolved application : applications.all()) {
            Set<String> origins = new HashSet<>();
            for (String origin : application.settings().getOrigins()) {
                String normalized = BffApplications.normalizeOrigin(origin);
                if (normalized != null) origins.add(normalized);
            }
            originsByApplication.put(application.id(), origins);
            anyOrigin.addAll(origins);
        }
        this.logoutPath = bffPath + "/logout";
        this.graphqlPath = graphqlPath == null || graphqlPath.isBlank() ? "/graphql" : graphqlPath;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean upgrade = isWebSocketUpgrade(request);
        boolean safe = !upgrade && SAFE_METHODS.contains(request.getMethod().name());
        // A safe request without Origin (navigation, <img>, same-origin fetch) cannot be read cross-origin: pass it.
        if (safe && request.getHeaders().getFirst(HttpHeaders.ORIGIN) == null) return chain.filter(exchange);
        // The context is read here, before anything looks at the exchange attribute: loading sets it.
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(auth -> isCookieAuthenticated(exchange, auth))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found ->
                        found.isEmpty() ? chain.filter(exchange) : check(exchange, chain, found.get(), upgrade, safe));
    }

    private Mono<Void> check(
            ServerWebExchange exchange, WebFilterChain chain, Authentication auth, boolean upgrade, boolean safe) {
        ServerHttpRequest request = exchange.getRequest();
        String origin = BffApplications.normalizeOrigin(request.getHeaders().getFirst(HttpHeaders.ORIGIN));
        if (origin == null || !allowedOrigins(auth).contains(origin)) {
            return refuse(exchange, HttpStatus.FORBIDDEN, "origin not allowed");
        }
        if (!upgrade && !safe && csrfHeaderName != null) {
            // A custom header forces a CORS preflight, which makes the content-type rule below redundant.
            if (!csrfHeaderValue.equals(request.getHeaders().getFirst(csrfHeaderName))) {
                return refuse(exchange, HttpStatus.FORBIDDEN, "csrf header required");
            }
            return chain.filter(exchange);
        }
        if (!upgrade && !safe && !exemptFromContentType(request) && refusedContentType(request.getHeaders())) {
            return refuse(exchange, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported content type");
        }
        return chain.filter(exchange);
    }

    private static boolean isCookieAuthenticated(ServerWebExchange exchange, Authentication auth) {
        return CookieAuthenticated.isCookieAuthenticated(auth)
                || GatewayRequestAttributes.COOKIE.equals(
                        exchange.getAttributes().get(GatewayRequestAttributes.AUTHENTICATED_BY));
    }

    /** The session's application's origins; every application's when the session names none; none when it names a gone one. */
    private Set<String> allowedOrigins(Authentication auth) {
        String application = CookieAuthenticated.application(auth);
        if (application == null) return anyOrigin;
        return originsByApplication.getOrDefault(application, Set.of());
    }

    private static boolean isWebSocketUpgrade(ServerHttpRequest request) {
        String upgrade = request.getHeaders().getFirst(HttpHeaders.UPGRADE);
        return upgrade != null && upgrade.trim().equalsIgnoreCase("websocket");
    }

    private boolean exemptFromContentType(ServerHttpRequest request) {
        String path = request.getPath().value();
        if (path.equals(logoutPath) && request.getMethod().name().equals("POST")) return true;
        if (path.equals(graphqlPath) || path.startsWith(graphqlPath + "/")) return true;
        String type = mediaType(request.getHeaders());
        return type != null && type.startsWith("application/grpc");
    }

    private static boolean refusedContentType(HttpHeaders headers) {
        String type = mediaType(headers);
        if (type == null) {
            return headers.getContentLength() > 0 || headers.getFirst(HttpHeaders.TRANSFER_ENCODING) != null;
        }
        return FORBIDDEN_TYPES.contains(type);
    }

    /** The content type without parameters in lower case, or {@code null} when absent or blank. */
    private static String mediaType(HttpHeaders headers) {
        String raw = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        if (raw == null || raw.isBlank()) return null;
        int semicolon = raw.indexOf(';');
        return (semicolon < 0 ? raw : raw.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static Mono<Void> refuse(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        DataBuffer body =
                response.bufferFactory().wrap(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(body));
    }
}
