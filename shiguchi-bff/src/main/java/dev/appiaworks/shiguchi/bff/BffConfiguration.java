package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.config.CorsConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.SecurityConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.UpstreamCredentialSource;
import dev.appiaworks.shiguchi.security.CookieSessions;
import dev.appiaworks.shiguchi.security.GatewaySecurityCustomizer;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Wires browser sign-in when {@code shiguchi.bff.enabled=true}: the sign-in endpoints, the
 * session store and the default {@link BffSessionAuthenticator}, the security-context
 * repository that reads the session cookie of a request without an {@code Authorization} header,
 * the security customizer that installs it, and the credential source that sends the session's
 * token upstream. Lives under {@code dev.appiaworks.shiguchi}, which every gateway auto-configuration
 * scans, so the starters need no registration of their own. Nothing in this package is a
 * {@code @Component}: with the module off, none of it exists.
 *
 * <p>Startup checks: see {@link #validate}. {@code security.mode: PERMIT_ALL} is allowed and
 * logged as a warning.
 *
 * <p>This module never defines a {@code ReactiveJwtDecoder} bean: Boot's issuer decoder backs off
 * when one exists, and every bearer caller would then be validated by it.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.bff", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BffProperties.class)
@Import(BffStoreConfiguration.class)
public class BffConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BffConfiguration.class);

    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern CLAIM_NAME = Pattern.compile("[A-Za-z0-9_.:/-]{1,64}");
    private static final int MAX_SESSION_CLAIMS = 32;
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** Authorize-request parameters the gateway sets itself; {@code authorize-parameters} may not name them. */
    private static final Set<String> PROTOCOL_PARAMETERS = Set.of(
            "response_type",
            "client_id",
            "redirect_uri",
            "scope",
            "state",
            "nonce",
            "code_challenge",
            "code_challenge_method");

    @Bean
    public BffApplications bffApplications(
            BffProperties properties, GatewayProperties gateway, Environment environment) {
        validate(properties, gateway, Binder.get(environment));
        if (gateway.getSecurity().getMode() == SecurityConfig.Mode.PERMIT_ALL) {
            log.warn("shiguchi.bff is enabled under security.mode PERMIT_ALL: a request without a session"
                    + " cookie or a token still reaches every route anonymously");
        }
        return new BffApplications(properties);
    }

    @Bean
    public BffCookies bffCookies(BffProperties properties) {
        return new BffCookies(properties);
    }

    @Bean
    public BffProviders bffProviders(BffProperties properties, ObjectProvider<WebClient.Builder> webClient) {
        return new BffProviders(properties, webClient.getIfAvailable(WebClient::builder), Clock.systemUTC());
    }

    /**
     * The converter cookie callers share with bearer callers (see {@link JwtPrincipalConverter}),
     * wrapped: as a bean of its own type it would be found by the lookup that selects it.
     */
    @Bean
    public BffPrincipals bffPrincipals(
            ObjectProvider<ReactiveJwtAuthenticationConverter> reactive,
            ObjectProvider<Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>>> any) {
        return new BffPrincipals(JwtPrincipalConverter.resolve(reactive, any));
    }

    /**
     * Renews session access tokens, once per session across instances. One per gateway: the cookie
     * authenticator and the upstream credential source share its in-flight attempts.
     */
    @Bean
    BffTokenRefresher bffTokenRefresher(
            BffSessionStore sessionStore,
            BffProperties properties,
            BffProviders providers,
            BffPrincipals converter,
            ObjectProvider<MeterRegistry> registries,
            ApplicationEventPublisher events) {
        return new BffTokenRefresher(
                ended(sessionStore, events),
                providers::refresh,
                providers::accessDecoder,
                providers::idTokenDecoder,
                converter::convert,
                properties,
                Clock.systemUTC(),
                registries.getIfAvailable());
    }

    /** Cookie to caller. A user-provided {@link BffSessionAuthenticator} bean replaces it. */
    @Bean
    @ConditionalOnMissingBean(BffSessionAuthenticator.class)
    public BffSessionAuthenticator bffSessionAuthenticator(
            BffSessionStore sessionStore,
            BffCookies cookies,
            BffProperties properties,
            BffProviders providers,
            BffPrincipals converter,
            BffTokenRefresher refresher,
            ApplicationEventPublisher events) {
        return new DefaultBffSessionAuthenticator(
                ended(sessionStore, events),
                cookies,
                properties,
                providers::accessDecoder,
                converter::convert,
                Clock.systemUTC(),
                refresher);
    }

    @Bean
    public RouterFunction<ServerResponse> bffRouterFunction(
            BffProperties properties,
            BffApplications applications,
            BffProviders providers,
            BffSessionStore sessionStore,
            BffCookies cookies,
            BffPrincipals converter,
            BffRevocation revocation,
            BffLogoutMeter logouts,
            ApplicationEventPublisher events,
            Environment environment) {
        BffSessionStore store = ended(sessionStore, events);
        log.info("BFF sign-in on at {}", properties.getPath());
        if (properties.getLoginRateLimit().getRequestsPerSecond() <= 0) {
            log.info("shiguchi.bff.login-rate-limit.requests-per-second is 0: sign-in starts are not limited;"
                    + " set it (and burst) to cap how fast one client address can fill the login store");
        }
        String forwardWarning =
                loginLimitWarning(properties, environment.getProperty("server.forward-headers-strategy"));
        if (forwardWarning != null) log.warn(forwardWarning);
        return new BffEndpoints(
                        properties, applications, providers, store, cookies, converter::convert, Clock.systemUTC())
                .loginLimiter(BffLoginLimiter.of(properties.getLoginRateLimit()))
                .handles(BffSessionHandles.from(properties.getSession()))
                .meter(logouts)
                .revocation(revocation)
                .routes();
    }

    /** The issuer's way to end sessions: {@code POST <path>/backchannel-logout/{provider}}. */
    @Bean
    public RouterFunction<ServerResponse> bffBackchannelLogoutRouterFunction(
            BffProperties properties,
            BffProviders providers,
            BffSessionStore sessionStore,
            BffLogoutMeter logouts,
            ApplicationEventPublisher events) {
        return new BffBackchannelLogout(properties, providers, ended(sessionStore, events), Clock.systemUTC(), logouts)
                .routes();
    }

    /** Revokes the tokens of a session the user or the application ended, at the issuer (RFC 7009). */
    @Bean
    public BffRevocation bffRevocation(BffProviders providers, ObjectProvider<MeterRegistry> registries) {
        return new BffRevocation(providers, registries.getIfAvailable());
    }

    /** The application's way to end every session of a user. */
    @Bean
    public BffSessions bffSessions(
            BffProperties properties,
            BffSessionStore sessionStore,
            BffRevocation revocation,
            BffLogoutMeter logouts,
            ApplicationEventPublisher events) {
        return new BffSessions(properties, ended(sessionStore, events), logouts, revocation);
    }

    /** The {@code gateway.bff.logout} counter, shared by every way a session ends. */
    @Bean
    BffLogoutMeter bffLogoutMeter(ObjectProvider<MeterRegistry> registries) {
        return new BffLogoutMeter(registries.getIfAvailable());
    }

    /**
     * The store as session users see it: deleting a session also announces its end. Not a bean, which would be
     * a second {@link BffSessionStore} next to the configured one.
     */
    private static BffSessionStore ended(BffSessionStore sessionStore, ApplicationEventPublisher events) {
        return new BffEndedSessionStore(sessionStore, events::publishEvent);
    }

    /** The {@code gateway-bff-sessions} actuator endpoint, when Actuator is on the classpath. */
    @Configuration
    // Nested configurations are also found by the gateway's package scan on their own, so each repeats the outer
    // switch.
    @ConditionalOnProperty(prefix = "shiguchi.bff", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class Admin {
        @Bean
        public BffSessionsEndpoint bffSessionsEndpoint(BffSessions sessions, BffProperties properties) {
            return new BffSessionsEndpoint(sessions, properties);
        }
    }

    /** What push asks to close a cookie socket when its session ends on another instance. */
    @Bean
    @ConditionalOnMissingBean(CookieSessions.class)
    public CookieSessions bffCookieSessions(BffSessionStore store, BffProperties properties) {
        return new BffCookieSessions(store, properties, Clock.systemUTC());
    }

    @Bean
    public BffSecurityContextRepository bffSecurityContextRepository(
            ObjectProvider<BffSessionAuthenticator> authenticator) {
        return new BffSecurityContextRepository(authenticator.getIfUnique());
    }

    @Bean
    public GatewaySecurityCustomizer bffSecurityCustomizer(
            BffSecurityContextRepository repository,
            ObjectProvider<BffSessionAuthenticator> authenticator,
            BffProperties properties,
            BffApplications applications,
            Environment environment) {
        if ((properties.getAdmin().getRole() == null
                        || properties.getAdmin().getRole().isBlank())
                && sessionsEndpointExposed(environment)) {
            log.warn("shiguchi.bff.admin.role is not set: the exposed gateway-bff-sessions endpoint is disabled"
                    + " and answers 403 to every caller. Set the role operators hold to use it.");
        }
        String graphqlPath =
                environment.getProperty("spring.graphql.http.path", environment.getProperty("spring.graphql.path"));
        return new BffSecurityCustomizer(
                repository,
                authenticator.getIfUnique() != null,
                properties.getPath(),
                new BffOriginFilter(
                        applications,
                        properties.getPath(),
                        graphqlPath,
                        properties.getCsrfHeader().getName(),
                        properties.getCsrfHeader().getValue()),
                new BffActuatorGuardFilter(environment.getProperty("management.endpoints.web.base-path", "/actuator")));
    }

    /** True when {@code management.endpoints.web.exposure.include} names the sessions endpoint or {@code *}. */
    static boolean sessionsEndpointExposed(Environment environment) {
        Set<String> include = Binder.get(environment)
                .bind("management.endpoints.web.exposure.include", Bindable.setOf(String.class))
                .orElse(Set.of());
        return include.stream().map(String::trim).anyMatch(id -> id.equals("*") || id.equals("gateway-bff-sessions"));
    }

    /** Stops anything but the sign-in endpoints from setting the session or a login cookie. */
    @Bean
    public WebFilter bffSetCookieGuard(BffCookies cookies, BffProperties properties) {
        return new BffSetCookieGuard(cookies, properties.getPath());
    }

    @Bean
    @ConditionalOnMissingBean(UpstreamCredentialSource.class)
    public UpstreamCredentialSource bffUpstreamCredentialSource(
            BffSessionStore sessionStore, BffTokenRefresher refresher) {
        return new BffUpstreamCredentialSource(sessionStore, refresher, Clock.systemUTC());
    }

    /**
     * Fails startup, one line per problem, when the configuration cannot work: no application or
     * no provider; an application naming an unknown provider; an origin that is not exactly
     * {@code scheme://host[:port]}; two applications sharing an origin; a {@code return-to} that is
     * not absolute or not under one of its application's origins; a {@code __Host-} cookie or a
     * {@code SameSite=None} cookie that is not {@code secure}; a provider without {@code issuer-uri} or {@code client-id}; a provider id
     * outside {@code [a-z0-9-]{1,32}}; a provider without {@code client-secret}; a {@code path} that
     * does not start with a slash, is a single slash or ends with a slash; a
     * {@code post-logout-redirect} that is not absolute or not under the application's origins; and,
     * from the gateway's own settings, {@code cors.allow-credentials} with a {@code *} origin,
     * {@code set-cookie} (or {@code *}, which forwards it) in {@code rest.forward-response-headers}
     * or a service's, and {@code cookie} in {@code client.forward-headers}. {@link #warnings} lists
     * the layouts that start but are unlikely to work.
     */
    static void validate(BffProperties properties) {
        validate(properties, new GatewayProperties());
    }

    static void validate(BffProperties properties, GatewayProperties gateway) {
        validate(properties, gateway, null);
    }

    static void validate(BffProperties properties, GatewayProperties gateway, Binder binder) {
        List<String> problems = new ArrayList<>();
        if (properties.getApplications().isEmpty()) {
            problems.add("no applications declared under shiguchi.bff.applications");
        }
        if (properties.getProviders().isEmpty()) {
            problems.add("no providers declared under shiguchi.bff.providers");
        }
        properties.getProviders().forEach((id, provider) -> {
            if (!PROVIDER_ID.matcher(id).matches()) {
                problems.add("provider '" + id + "': id must match [a-z0-9-]{1,32}");
            }
            if (blank(provider.getIssuerUri())) problems.add("provider '" + id + "': issuer-uri is required");
            if (blank(provider.getClientId())) problems.add("provider '" + id + "': client-id is required");
            if (blank(provider.getClientSecret())) problems.add("provider '" + id + "': client-secret is required");
            List<String> algorithms = provider.getJwsAlgorithms();
            if (algorithms == null || algorithms.isEmpty()) {
                problems.add("provider '" + id + "': jws-algorithms must list at least one algorithm");
            } else {
                for (String algorithm : algorithms) {
                    String problem = BffProviders.jwsAlgorithmProblem(algorithm);
                    if (problem != null) {
                        problems.add("provider '" + id + "': jws-algorithms value '" + algorithm + "' " + problem);
                    }
                }
            }
            // Removed keys bind silently into nothing: name them so a config is not trusted to mean something.
            if (binder != null
                    && PROVIDER_ID.matcher(id).matches()
                    && binder.bind("shiguchi.bff.providers." + id + ".type", Bindable.of(String.class))
                            .isBound()) {
                problems.add("provider '" + id + "': type was removed (OIDC was its only value): delete the key");
            }
            List<String> passThrough = provider.getPassThroughParameters();
            if (passThrough != null) {
                for (String name : passThrough) {
                    if (name == null || !PARAMETER_NAME.matcher(name).matches()) {
                        problems.add(
                                "provider '" + id + "': pass-through-parameters names must match [A-Za-z0-9_.-]{1,64}");
                    } else if (PROTOCOL_PARAMETERS.contains(name)) {
                        problems.add("provider '" + id + "': pass-through-parameters must not name '" + name
                                + "': the gateway sends it");
                    }
                }
            }
            Map<String, String> authorizeParameters = provider.getAuthorizeParameters();
            if (authorizeParameters != null) {
                authorizeParameters.forEach((name, value) -> {
                    if (blank(name) || value == null) {
                        problems.add("provider '" + id + "': authorize-parameters entries need a name and a value");
                    } else if (PROTOCOL_PARAMETERS.contains(name)) {
                        problems.add("provider '" + id + "': authorize-parameters must not set '" + name
                                + "': the gateway sends it");
                    }
                });
            }
        });
        String path = properties.getPath();
        if (path == null || !path.startsWith("/") || path.equals("/") || path.endsWith("/")) {
            problems.add("path '" + path + "' must start with /, must not be / and must not end with /");
        }
        Map<String, String> originOwner = new HashMap<>();
        properties.getApplications().forEach((id, app) -> {
            if (app.getProviders().isEmpty()) {
                problems.add("application '" + id + "': providers must name at least one provider");
            }
            for (String provider : app.getProviders()) {
                if (!properties.getProviders().containsKey(provider)) {
                    problems.add("application '" + id + "': unknown provider '" + provider + "'");
                }
            }
            if (app.getOrigins().isEmpty()) {
                problems.add("application '" + id + "': origins must list at least one origin");
            }
            List<String> claimNames = app.getSessionClaims();
            if (claimNames == null || claimNames.size() > MAX_SESSION_CLAIMS) {
                problems.add(
                        "application '" + id + "': session-claims must list at most " + MAX_SESSION_CLAIMS + " names");
            } else {
                for (String claim : claimNames) {
                    if (claim == null || !CLAIM_NAME.matcher(claim).matches()) {
                        problems.add(
                                "application '" + id + "': session-claims names must match [A-Za-z0-9_.:/-]{1,64}");
                    }
                }
            }
            List<String> origins = new ArrayList<>();
            for (String origin : app.getOrigins()) {
                String normalized = BffApplications.normalizeOrigin(origin);
                if (normalized == null) {
                    problems.add("application '" + id + "': origin '" + origin
                            + "' must be exactly scheme://host[:port] (http or https, no path, no wildcard)");
                    continue;
                }
                origins.add(normalized);
                String other = originOwner.putIfAbsent(normalized, id);
                if (other != null && !other.equals(id)) {
                    problems.add("applications '" + other + "' and '" + id + "' share origin " + normalized);
                }
            }
            for (String returnTo : app.getReturnTo()) {
                String origin = returnToOrigin(returnTo);
                if (origin == null) {
                    problems.add("application '" + id + "': return-to '" + returnTo + "' must be an absolute URL");
                } else if (!origins.contains(origin)) {
                    problems.add("application '" + id + "': return-to '" + returnTo
                            + "' is not under one of the application's origins");
                }
            }
            String postLogout = app.getPostLogoutRedirect();
            if (postLogout != null) {
                String origin = returnToOrigin(postLogout);
                if (origin == null) {
                    problems.add("application '" + id + "': post-logout-redirect '" + postLogout
                            + "' must be an absolute URL");
                } else if (!origins.contains(origin)) {
                    problems.add("application '" + id + "': post-logout-redirect '" + postLogout
                            + "' is not under one of the application's origins");
                }
            }
        });
        Duration loginTimeout = properties.getLoginTimeout();
        if (loginTimeout == null || loginTimeout.compareTo(Duration.ofMinutes(15)) > 0) {
            problems.add("login-timeout '" + loginTimeout + "' must be at most 15 minutes: every sign-in start"
                    + " keeps a stored login that long");
        }
        BffProperties.LoginRateLimit loginLimit = properties.getLoginRateLimit();
        if (loginLimit.getRequestsPerSecond() < 0 || Double.isNaN(loginLimit.getRequestsPerSecond())) {
            problems.add("login-rate-limit.requests-per-second '" + loginLimit.getRequestsPerSecond()
                    + "' must be at least 0 (0 turns the limit off)");
        }
        if (loginLimit.getBurst() < 0) {
            problems.add("login-rate-limit.burst '" + loginLimit.getBurst()
                    + "' must be at least 0 (0 means twice the rate)");
        }
        Duration skew = properties.getRefreshSkew();
        if (skew == null || skew.isNegative() || skew.compareTo(Duration.ofMinutes(5)) >= 0) {
            problems.add("refresh-skew '" + skew + "' must be at least 0 and less than 5 minutes");
        }
        Duration lease = properties.getRefreshLease();
        if (lease == null || lease.compareTo(BffTokenRefresher.MIN_LEASE) < 0) {
            problems.add("refresh-lease '" + lease + "' must be at least "
                    + BffTokenRefresher.MIN_LEASE.toSeconds() + " seconds: the "
                    + BffTokenRefresher.TOKEN_ENDPOINT_TIMEOUT.toSeconds() + " second token endpoint timeout, a "
                    + BffTokenRefresher.LEASE_MARGIN.toSeconds() + " second margin and a second to check and store");
        }
        BffProperties.Cookie cookie = properties.getCookie();
        if (cookie.getName() != null && cookie.getName().startsWith("__Host-") && !cookie.isSecure()) {
            problems.add(
                    "cookie.name '" + cookie.getName() + "' has the __Host- prefix, which needs cookie.secure true");
        }
        if (cookie.getSameSite() == BffProperties.SameSite.NONE && !cookie.isSecure()) {
            problems.add("cookie.same-site NONE needs cookie.secure true: browsers drop a SameSite=None cookie"
                    + " that is not Secure");
        }
        problems.addAll(BffTokenCipher.problems(properties.getSession()));
        problems.addAll(gatewayProblems(gateway));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid shiguchi.bff configuration:\n  - " + String.join("\n  - ", problems));
        }
        warnings(properties, gateway).forEach(log::warn);
        if (properties.getSession().getStore() == BffProperties.SessionStore.MEMORY
                && !properties.getSession().getEncryption().getKeys().isEmpty()) {
            log.info("shiguchi.bff.session.encryption.keys is set but session.store is MEMORY: the memory store"
                    + " is not encrypted, the keys are ignored");
        }
    }

    private static List<String> gatewayProblems(GatewayProperties gateway) {
        List<String> problems = new ArrayList<>();
        CorsConfig cors = gateway.getCors();
        if (cors.isEnabled()
                && cors.isAllowCredentials()
                && cors.getAllowedOrigins().stream()
                        .anyMatch(o -> o != null && o.trim().equals("*"))) {
            problems.add("cors.allow-credentials is true with '*' in cors.allowed-origins: list the origins instead,"
                    + " a browser would send the session cookie to any site");
        }
        forwardsSetCookie(gateway.getRest().getForwardResponseHeaders(), "rest.forward-response-headers", problems);
        for (ServiceDefinition service : gateway.getServices()) {
            forwardsSetCookie(
                    service.getForwardResponseHeaders(),
                    "services[" + service.getName() + "].forward-response-headers",
                    problems);
        }
        for (String header : gateway.getClient().getForwardHeaders()) {
            if (header != null && header.trim().equalsIgnoreCase("cookie")) {
                problems.add("client.forward-headers lists 'cookie': the session cookie would reach the backends");
            }
        }
        return problems;
    }

    private static void forwardsSetCookie(List<String> headers, String where, List<String> problems) {
        if (headers == null) return;
        for (String header : headers) {
            String name = header == null ? "" : header.trim();
            if (name.equalsIgnoreCase("set-cookie")) {
                problems.add(where + " lists 'set-cookie': a backend could set cookies on the browser");
            } else if (name.equals("*")) {
                problems.add(where + " is '*', which forwards 'set-cookie': a backend could set cookies on the"
                        + " browser; list the headers to forward instead");
            }
        }
    }

    /**
     * Layouts that start but are unlikely to work: an application on a site other than
     * {@code external-base-url}'s while the session cookie is not {@code SameSite=None} (the browser
     * would never send it), and an application served from another origin than the gateway's
     * without CORS letting it call with credentials. Judged only when {@code external-base-url}
     * names the gateway's origin. Also: no {@code external-base-url} while the cookie is
     * {@code secure}, which means the gateway runs behind TLS and builds its {@code redirect_uri}
     * from the request it sees.
     */
    static List<String> warnings(BffProperties properties, GatewayProperties gateway) {
        List<String> warnings = new ArrayList<>();
        BffProperties.Session session = properties.getSession();
        if (session.getStore() != BffProperties.SessionStore.MEMORY
                && session.getEncryption().isDisabled()) {
            warnings.add("shiguchi.bff.session.encryption.disabled is true with session.store "
                    + session.getStore() + ": access, refresh and ID tokens are stored in plaintext, so read access"
                    + " to the store acts as every signed-in user; use this for development only");
        }
        if (blank(properties.getExternalBaseUrl()) && properties.getCookie().isSecure()) {
            warnings.add("shiguchi.bff.external-base-url is not set while cookie.secure is true: the gateway"
                    + " builds its redirect_uri from the request, so behind a TLS terminator that does not send"
                    + " forwarded headers (or without server.forward-headers-strategy) it becomes http:// and every"
                    + " sign-in fails with sign_in_failed; set external-base-url to the public https:// origin");
        }
        String own = BffApplications.normalizeOrigin(properties.getExternalBaseUrl());
        if (own == null) return warnings;
        CorsConfig cors = gateway.getCors();
        properties.getApplications().forEach((id, app) -> {
            for (String origin : app.getOrigins()) {
                String normalized = BffApplications.normalizeOrigin(origin);
                if (normalized == null || normalized.equals(own)) continue;
                if (!sameSite(own, normalized) && properties.getCookie().getSameSite() != BffProperties.SameSite.NONE) {
                    warnings.add("shiguchi.bff application '" + id + "' at " + normalized
                            + " is cross-site to external-base-url " + own + ": its browser does not send the"
                            + " session cookie to the gateway unless cookie.same-site is NONE; serve the application"
                            + " from the gateway's origin or a sibling host");
                }
                boolean corsAllows = cors.isEnabled()
                        && cors.isAllowCredentials()
                        && cors.getAllowedOrigins().stream()
                                .anyMatch(o -> normalized.equals(BffApplications.normalizeOrigin(o)));
                if (!corsAllows) {
                    warnings.add("shiguchi.bff application '" + id + "' at " + normalized
                            + " is not the gateway's own origin " + own + " and cors does not allow it with"
                            + " allow-credentials: the browser blocks its calls; serve it from the gateway's origin"
                            + " or set cors.enabled, cors.allowed-origins, cors.allow-credentials and cors.paths");
                }
            }
        });
        return warnings;
    }

    /**
     * The login limit keys on the remote address; with {@code external-base-url} set the gateway is
     * probably behind a load balancer, and without {@code server.forward-headers-strategy} that
     * address is the balancer's, so every client would share one bucket. {@code null}: nothing to say.
     */
    static String loginLimitWarning(BffProperties properties, String forwardHeadersStrategy) {
        if (properties.getLoginRateLimit().getRequestsPerSecond() <= 0) return null;
        if (blank(properties.getExternalBaseUrl()) || !blank(forwardHeadersStrategy)) return null;
        return "shiguchi.bff.login-rate-limit is on, external-base-url is set and"
                + " server.forward-headers-strategy is not: behind a load balancer the limit keys on the balancer's"
                + " address, so all clients share one bucket; set server.forward-headers-strategy (framework or"
                + " native) so the client address is taken from the forwarded headers";
    }

    /** Same scheme and same registrable domain, approximated as the last two host labels. */
    private static boolean sameSite(String a, String b) {
        URI x = URI.create(a);
        URI y = URI.create(b);
        return x.getScheme().equals(y.getScheme()) && site(x.getHost()).equals(site(y.getHost()));
    }

    private static String site(String host) {
        if (host.contains(":") || host.matches("[0-9.]+")) return host;
        String[] labels = host.split("\\.");
        return labels.length <= 2 ? host : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private static String returnToOrigin(String returnTo) {
        if (blank(returnTo)) return null;
        try {
            URI uri = new URI(returnTo.trim());
            return uri.isAbsolute() && uri.getRawUserInfo() == null ? BffApplications.normalizeOrigin(uri) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
