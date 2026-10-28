package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The generated OpenAPI 3 document and the Swagger UI over it.
 *
 * <p>The document is built from the descriptors the gateway loaded, so it cannot describe routes
 * that are not being served. What is configured here is the metadata around it: the title and
 * version, the servers to advertise, and the security schemes callers should use.
 */
@Data
@NoArgsConstructor
public class OpenApiConfig {
    private boolean enabled = true;

    /**
     * Named security schemes to advertise in the OpenAPI spec.
     * Keys become the securityScheme IDs used in Swagger UI's Authorize dialog.
     *
     * Example (JWT Bearer):
     * <pre>
     *   security-schemes:
     *     bearerAuth:
     *       type: http
     *       scheme: bearer
     *       bearer-format: JWT
     * </pre>
     *
     * Example (OAuth2 authorization_code):
     * <pre>
     *   security-schemes:
     *     oauth2:
     *       type: oauth2
     *       authorization-code:
     *         authorization-url: https://auth.example.com/oauth/authorize
     *         token-url: https://auth.example.com/oauth/token
     *         scopes:
     *           read: Read access
     *           write: Write access
     * </pre>
     */
    private Map<String, SecuritySchemeConfig> securitySchemes = new java.util.LinkedHashMap<>();

    /**
     * Scheme IDs from securitySchemes to apply as global security requirements on all operations.
     * Leave empty to declare schemes without requiring them globally (useful when only some
     * operations need auth — annotate those protos individually instead).
     *
     * Example: [bearerAuth]
     */
    private List<String> globalSecurity = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class SecuritySchemeConfig {
        /**
         * OpenAPI security scheme type.
         * One of: http, apiKey, oauth2, openIdConnect
         */
        private String type;

        /** For type=http: auth scheme name — "bearer" (JWT) or "basic" */
        private String scheme;
        /** For type=http bearer: format hint shown in Swagger UI, e.g. "JWT" */
        private String bearerFormat;

        /** For type=apiKey: where the key is transmitted — header, query, or cookie */
        private String in;
        /** For type=apiKey: the header/query/cookie name, e.g. "X-API-Key" */
        private String name;

        /** For type=oauth2: authorization_code flow */
        private OAuthFlowConfig authorizationCode;
        /** For type=oauth2: client_credentials flow */
        private OAuthFlowConfig clientCredentials;
        /** For type=oauth2: implicit flow */
        private OAuthFlowConfig implicit;
        /** For type=oauth2: password flow */
        private OAuthFlowConfig password;

        /** For type=openIdConnect: OIDC discovery document URL */
        private String openIdConnectUrl;

        /** Human-readable description shown in Swagger UI's Authorize dialog */
        private String description;

        @Data
        @NoArgsConstructor
        public static class OAuthFlowConfig {
            private String authorizationUrl;
            private String tokenUrl;
            private String refreshUrl;
            /** Scope name → description */
            private Map<String, String> scopes = new java.util.LinkedHashMap<>();
        }
    }
}
