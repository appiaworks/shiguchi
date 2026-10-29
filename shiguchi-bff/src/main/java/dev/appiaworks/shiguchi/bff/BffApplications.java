package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

/**
 * The configured applications, and which one a request belongs to. One application: always that
 * one. Several: the one whose {@code origins} contain the request's {@code Origin} header, or,
 * without that header, the request's own scheme, host and port. No match: empty, and the caller
 * refuses the request.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class BffApplications {

    /** An application id and its settings. */
    public record Resolved(String id, BffProperties.Application settings) {}

    private final List<Resolved> applications = new ArrayList<>();
    private final Map<String, Resolved> byOrigin = new LinkedHashMap<>();

    public BffApplications(BffProperties properties) {
        properties.getApplications().forEach((id, app) -> {
            Resolved resolved = new Resolved(id, app);
            applications.add(resolved);
            for (String origin : app.getOrigins()) {
                String normalized = normalizeOrigin(origin);
                if (normalized != null) byOrigin.putIfAbsent(normalized, resolved);
            }
        });
    }

    /**
     * The application this request belongs to.
     *
     * @param exchange the request
     * @return the application, or empty when several are configured and none matches
     */
    public Optional<Resolved> resolve(ServerWebExchange exchange) {
        if (applications.size() == 1) return Optional.of(applications.get(0));
        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        String key = origin != null
                ? normalizeOrigin(origin)
                : normalizeOrigin(exchange.getRequest().getURI());
        return key == null ? Optional.empty() : Optional.ofNullable(byOrigin.get(key));
    }

    /** Every configured application, in configuration order. */
    public List<Resolved> all() {
        return List.copyOf(applications);
    }

    /**
     * {@code scheme://host[:port]} in lower case with the scheme's default port dropped, or
     * {@code null} when the value is not exactly an http(s) origin (a path, query, fragment, user
     * info or {@code *} makes it one that is not).
     */
    static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.contains("*")) return null;
        URI uri;
        try {
            uri = new URI(origin.trim());
        } catch (Exception e) {
            return null;
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) return null;
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) return null;
        return normalizeOrigin(uri);
    }

    /** The origin part of an absolute http(s) URI, normalized as {@link #normalizeOrigin(String)}. */
    static String normalizeOrigin(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        int port = uri.getPort();
        boolean defaultPort =
                port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
