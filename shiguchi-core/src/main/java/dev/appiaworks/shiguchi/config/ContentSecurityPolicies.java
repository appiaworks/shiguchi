package dev.appiaworks.shiguchi.config;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the effective {@code Content-Security-Policy} for the browser surfaces from the
 * configuration, so nobody has to hand-write the whole policy just to let the console talk to
 * their identity provider.
 */
public final class ContentSecurityPolicies {

    private ContentSecurityPolicies() {}

    /**
     * The policy to send. A user-supplied value (anything but the shipped default) and a blank one
     * are returned untouched. The default gets the origins of the configured OAuth2 token endpoints
     * appended to {@code connect-src}: the authorization-code exchange and the refresh grant are
     * browser-direct. Client-credentials tokens are proxied by the gateway and need nothing, and the
     * authorization URL is only navigated to in a popup, which {@code connect-src} does not govern.
     */
    public static String effective(GatewayProperties properties) {
        String configured = properties.getHeaders().getContentSecurityPolicy();
        if (!HeadersConfig.DEFAULT_CONTENT_SECURITY_POLICY.equals(configured)) return configured;
        return withConnectOrigins(configured, tokenOrigins(properties.getOpenapi()));
    }

    /** Origins ({@code scheme://host[:port]}) of the token endpoints the browser calls directly. */
    static Set<String> tokenOrigins(OpenApiConfig openapi) {
        Set<String> origins = new LinkedHashSet<>();
        if (openapi == null || openapi.getSecuritySchemes() == null) return origins;
        for (OpenApiConfig.SecuritySchemeConfig scheme :
                openapi.getSecuritySchemes().values()) {
            if (scheme == null) continue;
            addOrigin(
                    origins,
                    scheme.getAuthorizationCode() == null
                            ? null
                            : scheme.getAuthorizationCode().getTokenUrl());
            // useTokenAutoRefresh falls back to this URL when no authorization-code flow is configured.
            addOrigin(
                    origins,
                    scheme.getClientCredentials() == null
                            ? null
                            : scheme.getClientCredentials().getTokenUrl());
        }
        return origins;
    }

    private static void addOrigin(Set<String> origins, String url) {
        if (url == null || url.isBlank()) return;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return;
            origins.add(scheme.toLowerCase() + "://" + uri.getHost().toLowerCase()
                    + (uri.getPort() >= 0 ? ":" + uri.getPort() : ""));
        } catch (IllegalArgumentException ignored) {
            // A malformed URL cannot be widened into the policy; the provider config is wrong anyway.
        }
    }

    /** Appends {@code origins} to the {@code connect-src} directive, deduplicated, keeping what is there. */
    static String withConnectOrigins(String policy, Set<String> origins) {
        if (origins.isEmpty()) return policy;
        StringBuilder out = new StringBuilder();
        for (String directive : policy.split(";")) {
            String d = directive.trim();
            if (d.isEmpty()) continue;
            if (out.length() > 0) out.append("; ");
            if (d.equals("connect-src") || d.startsWith("connect-src ")) {
                Set<String> sources = new LinkedHashSet<>(java.util.Arrays.asList(d.split("\s+")));
                sources.addAll(origins);
                d = String.join(" ", sources);
            }
            out.append(d);
        }
        return out.toString();
    }
}
