package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CORS for the browser-facing surfaces. Processed inside the security chain, so
 * preflight requests work even on authenticated surfaces. Disabled by default —
 * machine-to-machine REST routes never need CORS and should not get it by accident.
 */
@Data
@NoArgsConstructor
public class CorsConfig {
    private boolean enabled = false;
    /** Origins allowed to call the browser surfaces, e.g. {@code https://console.example.com}. */
    private List<String> allowedOrigins = new ArrayList<>();

    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "OPTIONS"));
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
    private boolean allowCredentials = false;
    /** How long browsers may cache the preflight response. */
    private java.time.Duration maxAge = java.time.Duration.ofHours(1);
    /**
     * Path patterns CORS applies to. Empty = the default browser surfaces:
     * the UI path, {@code /graphiql}, {@code /graphql}, Swagger UI, and
     * {@code /v3/api-docs/**}. REST API routes are deliberately not included —
     * add explicit patterns here if you really want CORS on them.
     */
    private List<String> paths = new ArrayList<>();
}
