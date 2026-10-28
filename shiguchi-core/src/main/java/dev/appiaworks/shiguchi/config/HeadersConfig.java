package dev.appiaworks.shiguchi.config;

import java.util.HashMap;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Security response headers on the browser surfaces ({@code shiguchi.headers.*}).
 *
 * <p>Spring Security already sets the per-response basics on every exchange
 * ({@code X-Content-Type-Options}, {@code X-Frame-Options}, {@code Cache-Control},
 * {@code Referrer-Policy}). What it cannot choose for you is a Content-Security-Policy: a
 * useful one depends on what the page loads, and a wrong one breaks it silently. The gateway
 * knows what its own console, GraphiQL and Swagger load, so it ships a policy for those.
 *
 * <p>Applied only to the browser surfaces — the same set CORS uses. A transcoded REST route
 * is consumed by programs, where these headers protect nobody.
 */
@Data
@NoArgsConstructor
public class HeadersConfig {
    /** Send the headers below on the browser surfaces. */
    private boolean enabled = true;

    /**
     * {@code Content-Security-Policy} for the browser surfaces. The default is what the
     * bundled console, GraphiQL and Swagger UI actually need: their own scripts and styles,
     * inline styles, data/blob images, and same-origin API calls. {@code 'unsafe-inline'} is
     * there for styles only, because those three bundles inject them; scripts are not granted
     * it. Empty disables the header.
     *
     * <p>Serving your own assets from a CDN, or embedding the console in another page, means
     * replacing this — it is a starting point, not a guarantee.
     */
    private String contentSecurityPolicy = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    /** {@code Referrer-Policy}. Empty keeps Spring Security's default. */
    private String referrerPolicy = "no-referrer";

    /**
     * {@code Permissions-Policy}. The default turns off the device APIs none of these pages
     * use, so a compromised script cannot reach them. Empty disables the header.
     */
    private String permissionsPolicy = "geolocation=(), microphone=(), camera=(), payment=(), usb=()";

    /**
     * {@code Strict-Transport-Security}, sent only on HTTPS requests. Off by default: the
     * gateway usually sits behind a TLS-terminating ingress that owns this header, and a
     * wrong {@code max-age} is remembered by browsers long after it is fixed. Empty disables
     * it; a typical value is {@code max-age=31536000 ; includeSubDomains}.
     */
    private String strictTransportSecurity = "";

    /** Extra headers to send verbatim on the browser surfaces. */
    private java.util.Map<String, String> custom = new HashMap<>();
}
