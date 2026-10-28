package dev.appiaworks.shiguchi.bff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code shiguchi.bff}: browser sign-in with a session cookie instead of a
 * bearer token. Off unless {@code enabled: true}. A request carrying an {@code Authorization}
 * header is always handled as a bearer request; the session cookie is consulted only without one.
 *
 * <p>Experimental. The module first ships in 0.13.0, so properties, store formats and endpoint shapes
 * may still change without a migration. Sign-in and back-channel logout have been run by hand
 * against the production issuer, outside CI. The request hardening itself is built (see docs/design/bff.md).
 *
 * <pre>
 * shiguchi:
 *   bff:
 *     enabled: true
 *     providers:
 *       idp:
 *         issuer-uri: https://id.example.com
 *         client-id: gateway
 *         client-secret: ...
 *     applications:
 *       eve:
 *         origins: [https://eve.example.com]
 *         providers: [idp]
 *         return-to: [https://eve.example.com/]
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "shiguchi.bff")
public class BffProperties {

    /** Turn the module on. */
    private boolean enabled = false;

    /** Base path of the sign-in endpoints ({@code /login/{provider}}, {@code /callback/{provider}}, ...). */
    private String path = "/bff";

    /**
     * Public base URL of the gateway ({@code scheme://host[:port]}), used to build the
     * {@code redirect_uri} sent to the issuer. Unset: taken from the request, which already
     * honours the gateway's forwarded-header settings.
     */
    private String externalBaseUrl;

    /** How long a sign-in may take between the redirect to the issuer and the callback. */
    private Duration loginTimeout = Duration.ofMinutes(5);

    /**
     * Caps how fast one client address may start sign-ins ({@code GET <path>/login/{provider}}), per
     * instance. Off by default.
     */
    private LoginRateLimit loginRateLimit = new LoginRateLimit();

    /**
     * A session whose access token expires within this long is refreshed by the next request that
     * reaches the gateway (the refresh token is exchanged once, whichever instance serves it).
     * From 0 to less than 5 minutes.
     */
    private Duration refreshSkew = Duration.ofSeconds(60);

    /**
     * How long the instance refreshing a session holds the cross-instance lease before others may
     * take it over. At least 8 seconds: the token call (5 s timeout), the checks of its answer and
     * the store write must end before the lease does, and the instance gives up 2 s early. Instances
     * must be NTP-synchronised (the JDBC store compares application clocks): clock skew must stay
     * well below this value minus the token-call time.
     */
    private Duration refreshLease = Duration.ofSeconds(10);

    private Session session = new Session();

    /**
     * Optional second CSRF layer: when {@code name} is set, every cookie-authenticated request on an
     * unsafe method (WebSocket upgrades excepted) must carry that header with exactly {@code value}.
     * Off by default.
     */
    private CsrfHeader csrfHeader = new CsrfHeader();

    private Cookie cookie = new Cookie();

    private Admin admin = new Admin();

    /** Identity providers the gateway signs users in with, keyed by provider id ({@code [a-z0-9-]{1,32}}). */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** Browser applications served by this gateway, keyed by application id. */
    private Map<String, Application> applications = new LinkedHashMap<>();

    @Data
    public static class LoginRateLimit {

        /**
         * Sign-in starts per second and client address. 0 turns the limit off. Behind a load balancer
         * the address is the balancer's unless {@code server.forward-headers-strategy} is set.
         */
        private double requestsPerSecond = 0;

        /** Starts one address may make at once. 0: twice {@link #requestsPerSecond}, rounded up. */
        private int burst = 0;

        /** The burst in effect: {@link #burst}, or twice the rate when unset, at least 1. */
        int effectiveBurst() {
            return burst > 0 ? burst : (int) Math.max(1, Math.ceil(2 * requestsPerSecond));
        }
    }

    @Data
    public static class Session {

        /** Where sessions are kept. */
        private SessionStore store = SessionStore.MEMORY;

        /** A session unused for this long ends. */
        private Duration idleTimeout = Duration.ofMinutes(30);

        /** A session ends this long after sign-in, used or not. */
        private Duration absoluteTimeout = Duration.ofHours(12);

        /** Settings of the {@code REDIS} store. */
        private Redis redis = new Redis();

        /** Settings of the {@code JDBC} store. */
        private Jdbc jdbc = new Jdbc();

        /** Encryption of the tokens in the {@code REDIS} and {@code JDBC} stores. */
        private Encryption encryption = new Encryption();

        /**
         * Keep the browser's {@code User-Agent} (cut to 200 characters) with the session, so the "my sessions"
         * listing ({@code GET <path>/sessions}) can show which device each one is.
         */
        private boolean recordUserAgent = true;
    }

    /**
     * Encrypts the access, refresh and ID token of every stored session (AES-256-GCM) so that read
     * access to Redis or the database does not yield usable tokens. Required for {@code REDIS} and
     * {@code JDBC}, unless {@code disabled} is {@code true}; the {@code MEMORY} store is never
     * encrypted. Writes use the {@code active} key, reads accept any key listed in {@code keys}.
     */
    @Data
    public static class Encryption {

        /** Key id to Base64 (standard or URL-safe) of 32 random bytes. An id matches {@code [A-Za-z0-9_-]{1,32}}. */
        private Map<String, String> keys = new LinkedHashMap<>();

        /** Id of the key that encrypts new writes; must be one of {@code keys}. */
        private String active = "";

        /** Store tokens in plaintext in {@code REDIS} or {@code JDBC}: for development only. */
        private boolean disabled = false;

        /** Never prints key material, only the ids. */
        @Override
        public String toString() {
            return "Encryption(keys=" + keys.keySet() + ", active=" + active + ", disabled=" + disabled + ")";
        }
    }

    @Data
    public static class Redis {

        /** Prefix of every key the store writes: {@code <prefix>session:<key>} and {@code <prefix>login:<state>}. */
        private String keyPrefix = "gw:bff:";
    }

    @Data
    public static class Jdbc {

        /** Bean name of the {@code DataSource} to use. Empty: the application's single {@code DataSource}. */
        private String dataSource = "";

        /** Force a dialect instead of reading it from the database; empty: detected. */
        private dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSchema.Dialect dialect;

        /** Prefix of the two table names: {@code <prefix>session} and {@code <prefix>login}. */
        private String tablePrefix = "gw_bff_";

        /** Run {@code CREATE TABLE IF NOT EXISTS} (and the indexes) at startup, ignoring objects that exist. */
        private boolean createSchema = true;

        /** How often expired rows are deleted. Reads ignore expired rows whether or not the sweep ran. */
        private Duration sweepInterval = Duration.ofMinutes(1);
    }

    public enum SessionStore {
        MEMORY,
        REDIS,
        JDBC
    }

    @Data
    public static class CsrfHeader {

        /** Header name (case-insensitive). Empty: the check is off. */
        private String name = "";

        /** Exact value the header must have. */
        private String value = "1";
    }

    @Data
    public static class Cookie {

        /** Session cookie name. The {@code __Host-} prefix requires {@code secure: true}. */
        private String name = "__Host-gw-session";

        /** {@code SameSite} attribute of the session cookie. */
        private SameSite sameSite = SameSite.LAX;

        /** {@code Secure} attribute of the session cookie. */
        private boolean secure = true;

        /** {@code true}: the session cookie survives browser restarts ({@code Max-Age} = absolute timeout). */
        private boolean persistent = false;
    }

    @Data
    public static class Admin {

        /**
         * Role the {@code gateway-bff-sessions} actuator endpoint needs (read and revoke), with or
         * without the {@code ROLE_} or {@code SCOPE_} prefix. Required to use the endpoint: empty,
         * it refuses every caller.
         */
        private String role = "";
    }

    public enum SameSite {
        STRICT,
        LAX,
        NONE
    }

    @Data
    public static class Provider {

        /** OIDC issuer; its discovery document supplies the endpoints. */
        private String issuerUri;

        private String clientId;

        private String clientSecret;

        /** Name shown to users in the providers list. Unset: the provider id. */
        private String displayName;

        /** Authorize-request parameter that carries the {@code hint} of the login request. */
        private String hintParameter = "provider";

        /**
         * Extra parameters added to every authorize request, e.g. {@code provider: google} or {@code audience: api}.
         * They cannot replace a protocol parameter ({@code client_id}, {@code state}, {@code scope}...). A login
         * request's {@code hint} replaces the parameter named by {@link #hintParameter}.
         */
        private Map<String, String> authorizeParameters = new LinkedHashMap<>();

        /**
         * Query parameters of {@code GET <path>/login/{provider}} that are copied onto the authorize request
         * (enables silent re-auth with {@code prompt=none}, step-up with {@code acr_values} or {@code max_age},
         * account hints). Only these names are forwarded; the others are ignored. {@code prompt} and
         * {@code max_age} are checked for their syntax, every value for length (256) and control characters.
         * A passed value replaces a static {@link #authorizeParameters} entry of the same name. Empty: none.
         */
        private List<String> passThroughParameters =
                new ArrayList<>(List.of("prompt", "max_age", "acr_values", "login_hint", "ui_locales"));

        /** Authorization endpoint. Unset: from the issuer's discovery document. */
        private String authorizationUri;

        /** Token endpoint. Unset: from the issuer's discovery document. */
        private String tokenUri;

        /** JWK set URI used to verify tokens. Unset: from the issuer's discovery document. */
        private String jwkSetUri;

        /** End-session (RP-initiated logout) endpoint. Unset: from discovery; none known: logout returns no URL. */
        private String endSessionUri;

        /**
         * Token revocation endpoint (RFC 7009). Unset: from discovery; none known: the refresh token is
         * not revoked at the issuer on logout.
         */
        private String revocationUri;

        /** Accepted {@code aud} values of the access token. Empty: not checked. */
        private List<String> audiences = new ArrayList<>();

        /**
         * JWS algorithms accepted on the provider's access, ID and logout tokens, verified against its JWK set.
         * Exact names: RS256 (default), ES256, PS256, EdDSA... HMAC (HS256/384/512) and none are refused.
         */
        private List<String> jwsAlgorithms = new ArrayList<>(List.of("RS256"));

        /** How the gateway authenticates to the token endpoint. */
        private ClientAuthenticationMethod clientAuthenticationMethod = ClientAuthenticationMethod.BASIC;

        /** Scopes requested at sign-in. */
        private List<String> scopes = new ArrayList<>(List.of("openid", "profile", "email"));

        /** What the gateway sends upstream for a signed-in user. {@code RELAY}: the provider's access token. */
        private UpstreamTokenMode upstreamTokenMode = UpstreamTokenMode.RELAY;
    }

    public enum ClientAuthenticationMethod {
        /** {@code client_secret_basic}: HTTP Basic header. */
        BASIC,
        /** {@code client_secret_post}: credentials in the form body. */
        POST
    }

    public enum UpstreamTokenMode {
        RELAY
    }

    @Data
    public static class Application {

        /** Exact origins ({@code scheme://host[:port]}) the application is served from. */
        private List<String> origins = new ArrayList<>();

        /** Provider ids the application signs in with. */
        private List<String> providers = new ArrayList<>();

        /** Absolute URLs, under one of {@link #origins}, a sign-in may return to. */
        private List<String> returnTo = new ArrayList<>();

        /**
         * Where the issuer sends the browser after logout; absolute, under one of {@link #origins}.
         * Unset: the first {@link #returnTo}.
         */
        private String postLogoutRedirect;

        /**
         * ID-token claims {@code GET <path>/session} returns for this application. {@code name}, {@code email}
         * and {@code picture} come back as fields of their own; every other name comes back under {@code claims}
         * with its JSON value (string, number, boolean, list or flat object, at most two levels deep; other
         * shapes are dropped). The claims are read from the ID token at sign-in and from each verified refreshed
         * one; at most 4 KB per session is kept. At most 32 names of {@code [A-Za-z0-9_.:/-]{1,64}}.
         */
        private List<String> sessionClaims = new ArrayList<>(List.of("name", "email", "picture"));
    }
}
