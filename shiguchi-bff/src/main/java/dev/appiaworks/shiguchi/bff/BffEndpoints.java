package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionClaims;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.ratelimit.RateLimiter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * The sign-in endpoints under {@code shiguchi.bff.path}: login, callback, session, logout and
 * providers. Every response carries {@code Cache-Control: no-store}. See the slice plan
 * ({@code .claude/plans/bff/G1-sign-in-session.md}) for the flow.
 */
final class BffEndpoints {

    private static final Logger log = LoggerFactory.getLogger(BffEndpoints.class);

    private static final Pattern HINT = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,64}");
    private static final String SIGN_IN_FAILED = "sign_in_failed";
    private static final String LOGIN_REQUIRED = "login_required";
    /** Issuer errors that mean "the user has to sign in interactively", reported to the SPA as one code. */
    private static final Set<String> INTERACTION_ERRORS =
            Set.of(LOGIN_REQUIRED, "interaction_required", "consent_required", "account_selection_required");

    private static final Set<String> PROMPT_VALUES = Set.of("none", "login", "consent", "select_account");
    private static final Pattern MAX_AGE = Pattern.compile("[0-9]{1,9}");
    private static final int MAX_PARAMETER_LENGTH = 256;
    private static final int MAX_USER_AGENT = 200;
    /** The most sessions the listing returns. */
    private static final int MAX_LISTED_SESSIONS = 100;
    /** Tolerance when comparing {@code auth_time} with {@code max_age}. */
    private static final Duration AUTH_TIME_SKEW = Duration.ofSeconds(60);

    private final BffProperties properties;
    private final BffApplications applications;
    private final BffReturnTo returnTo;
    private final BffProviders providers;
    private final BffSessionStore store;
    private final BffCookies cookies;
    private final Function<Jwt, Mono<AbstractAuthenticationToken>> converter;
    private final Clock clock;
    private final String path;
    private BffLogoutMeter meter = new BffLogoutMeter(null);
    private BffRevocation revocation;
    private BffLoginLimiter loginLimiter;
    private BffSessionHandles handles = new BffSessionHandles(new byte[32]);

    BffEndpoints(
            BffProperties properties,
            BffApplications applications,
            BffProviders providers,
            BffSessionStore store,
            BffCookies cookies,
            Function<Jwt, Mono<AbstractAuthenticationToken>> converter,
            Clock clock) {
        this.properties = properties;
        this.applications = applications;
        this.returnTo = new BffReturnTo(applications);
        this.providers = providers;
        this.store = store;
        this.cookies = cookies;
        this.converter = converter;
        this.clock = clock;
        this.path = properties.getPath();
    }

    /** Counts the browser's own logouts. */
    BffEndpoints meter(BffLogoutMeter meter) {
        this.meter = meter;
        return this;
    }

    /** Revokes the session's token at the issuer after the browser's own logout. */
    BffEndpoints revocation(BffRevocation revocation) {
        this.revocation = revocation;
        return this;
    }

    /** The handles the session listing gives out; the default is a fixed key, for tests that do not care. */
    BffEndpoints handles(BffSessionHandles handles) {
        this.handles = handles;
        return this;
    }

    /** Caps sign-in starts per client address; {@code null}: no limit. */
    BffEndpoints loginLimiter(BffLoginLimiter loginLimiter) {
        this.loginLimiter = loginLimiter;
        return this;
    }

    RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .GET(path + "/login/{provider}", this::login)
                .GET(path + "/callback/{provider}", this::callback)
                .GET(path + "/session", this::session)
                .POST(path + "/logout", this::logout)
                .GET(path + "/sessions", this::mySessions)
                .DELETE(path + "/sessions/{handle}", this::endMySession)
                .GET(path + "/providers", this::providers)
                .build();
    }

    // ---- login -------------------------------------------------------------------------------

    Mono<ServerResponse> login(ServerRequest request) {
        if (loginLimiter != null) {
            RateLimiter.Decision decision =
                    loginLimiter.tryAcquire(request.exchange().getRequest());
            if (!decision.allowed()) {
                return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, decision.retryAfterSeconds())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("error", "too_many_requests"));
            }
        }
        String provider = request.pathVariable("provider");
        BffProperties.Provider settings = providers.settings(provider);
        if (settings == null) return error(HttpStatus.NOT_FOUND, "unknown_provider");
        Optional<BffReturnTo.Match> match =
                returnTo.resolve(request.queryParam("return_to").orElse(null));
        if (match.isEmpty()) return error(HttpStatus.BAD_REQUEST, "invalid_return_to");
        BffApplications.Resolved application = match.get().application();
        if (!application.settings().getProviders().contains(provider)) {
            return error(HttpStatus.FORBIDDEN, "provider_not_allowed");
        }
        String hint = request.queryParam("hint").orElse(null);
        if (hint != null && !HINT.matcher(hint).matches()) return error(HttpStatus.BAD_REQUEST, "invalid_hint");
        Map<String, String> passThrough = new LinkedHashMap<>();
        for (String name : settings.getPassThroughParameters()) {
            List<String> values = request.queryParams().get(name);
            if (values == null || values.isEmpty()) continue;
            if (values.size() > 1 || !validParameter(name, values.get(0))) {
                return invalidParameter(name);
            }
            passThrough.put(name, values.get(0));
        }
        Long maxAge = passThrough.containsKey("max_age") ? Long.valueOf(passThrough.get("max_age")) : null;
        String redirectUri = externalBase(request) + path + "/callback/" + provider;
        return providers
                .endpoints(provider)
                .map(Optional::of)
                .doOnError(e -> log.warn("Discovery for provider '{}' failed: {}", provider, e.toString()))
                .onErrorReturn(Optional.empty())
                .flatMap(endpoints -> {
                    if (endpoints.isEmpty()) return error(HttpStatus.BAD_GATEWAY, "provider_unavailable");
                    String state = BffSessionKeys.newState();
                    String nonce = BffSessionKeys.newNonce();
                    String verifier = BffSessionKeys.newCodeVerifier();
                    BffLogin login = new BffLogin(
                            state,
                            verifier,
                            nonce,
                            provider,
                            application.id(),
                            match.get().returnTo(),
                            redirectUri,
                            clock.instant(),
                            maxAge);
                    // Static parameters first, then the request's pass-through values (which replace a
                    // static one of the same name); validation keeps both off the protocol names below,
                    // and the hint replaces the one it names.
                    Map<String, String> query = new LinkedHashMap<>(settings.getAuthorizeParameters());
                    query.putAll(passThrough);
                    query.put("response_type", "code");
                    query.put("client_id", settings.getClientId());
                    query.put("redirect_uri", redirectUri);
                    query.put("scope", String.join(" ", settings.getScopes()));
                    query.put("state", state);
                    query.put("nonce", nonce);
                    query.put("code_challenge", BffSessionKeys.challenge(verifier));
                    query.put("code_challenge_method", "S256");
                    if (hint != null) query.put(settings.getHintParameter(), hint);
                    String location = withQuery(endpoints.get().authorizationUri(), query);
                    return store.saveLogin(login, properties.getLoginTimeout())
                            .then(Mono.defer(() -> redirect(location, cookies.login(state))));
                });
    }

    // ---- callback ----------------------------------------------------------------------------

    Mono<ServerResponse> callback(ServerRequest request) {
        String provider = request.pathVariable("provider");
        if (providers.settings(provider) == null) return error(HttpStatus.NOT_FOUND, "unknown_provider");
        String state = request.queryParam("state").orElse(null);
        if (state == null || state.isBlank()) return error(HttpStatus.BAD_REQUEST, "invalid_state");
        String cookieValue = cookies.loginValue(request.exchange().getRequest());
        if (cookieValue == null || !constantTimeEquals(cookieValue, state)) {
            // Expired, never set, or replaced by a newer sign-in in another tab: that cookie is not
            // this callback's to clear, so it is left alone.
            return error(HttpStatus.BAD_REQUEST, "login_expired");
        }
        return store.takeLogin(state)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> {
                    if (found.isEmpty()) return error(HttpStatus.BAD_REQUEST, "invalid_state", cookies.clearLogin());
                    BffLogin login = found.get();
                    if (!login.provider().equals(provider)) {
                        return error(HttpStatus.BAD_REQUEST, "invalid_state", cookies.clearLogin());
                    }
                    BffApplications.Resolved application = applications.all().stream()
                            .filter(a -> a.id().equals(login.application()))
                            .findFirst()
                            .orElse(null);
                    if (application == null
                            || !application.settings().getProviders().contains(provider)) {
                        return error(HttpStatus.FORBIDDEN, "provider_not_allowed", cookies.clearLogin());
                    }
                    Optional<String> issuerError = request.queryParam("error");
                    if (issuerError.isPresent()) {
                        String code = ERROR_CODE.matcher(issuerError.get()).matches() ? issuerError.get() : "error";
                        if (INTERACTION_ERRORS.contains(code)) code = LOGIN_REQUIRED;
                        return failureRedirect(login, state, code);
                    }
                    String code = request.queryParam("code").orElse(null);
                    if (code == null || code.isBlank()) return failureRedirect(login, state, SIGN_IN_FAILED);
                    String presented = cookies.sessionValue(request.exchange().getRequest());
                    return signIn(login, application, code, userAgent(request))
                            .flatMap(session -> {
                                Mono<Void> rotate =
                                        presented == null ? Mono.empty() : store.delete(BffSessionKeys.key(presented));
                                return rotate.then(
                                                Mono.defer(() -> store.save(session.session(), ttl(session.session()))))
                                        .then(Mono.defer(() -> redirect(
                                                login.returnTo(),
                                                cookies.session(session.cookieValue()),
                                                cookies.clearLogin())));
                            })
                            .onErrorResume(e -> {
                                log.warn("Sign-in through provider '{}' failed: {}", provider, e.toString());
                                return failureRedirect(login, state, SIGN_IN_FAILED);
                            });
                });
    }

    private record NewSession(BffSession session, String cookieValue) {}

    /** The browser's User-Agent cut to 200 characters without control characters; {@code null} when off or absent. */
    private String userAgent(ServerRequest request) {
        if (!properties.getSession().isRecordUserAgent()) return null;
        String raw = request.headers().firstHeader(HttpHeaders.USER_AGENT);
        if (raw == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < MAX_USER_AGENT; i++) {
            char c = raw.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        String cut = out.toString().trim();
        return cut.isEmpty() ? null : cut;
    }

    private Mono<NewSession> signIn(
            BffLogin login, BffApplications.Resolved application, String code, String userAgent) {
        String provider = login.provider();
        return providers
                .exchange(provider, code, login.redirectUri(), login.codeVerifier())
                .flatMap(tokens -> providers
                        .idTokenDecoder(provider)
                        .flatMap(d -> d.decode(tokens.idToken()))
                        .flatMap(idJwt -> {
                            String nonce = idJwt.getClaimAsString("nonce");
                            if (nonce == null || !constantTimeEquals(nonce, login.nonce())) {
                                return Mono.error(new IllegalStateException("ID token nonce does not match"));
                            }
                            return providers
                                    .accessDecoder(provider)
                                    .flatMap(d -> d.decode(tokens.accessToken()))
                                    .flatMap(accessJwt -> converter
                                            .apply(accessJwt)
                                            .map(auth -> {
                                                Instant expires = accessJwt.getExpiresAt();
                                                if (expires == null)
                                                    throw new IllegalStateException("access token has no exp");
                                                String cookieValue = BffSessionKeys.newCookieValue();
                                                Instant now = clock.instant();
                                                Instant authTime = authTime(idJwt);
                                                if (login.maxAge() != null
                                                        && (authTime == null
                                                                || authTime.plusSeconds(login.maxAge())
                                                                        .plus(AUTH_TIME_SKEW)
                                                                        .isBefore(now))) {
                                                    throw new IllegalStateException(
                                                            "ID token auth_time is missing or older than the max_age requested");
                                                }
                                                BffSession session = new BffSession(
                                                        BffSessionKeys.key(cookieValue),
                                                        auth.getName(),
                                                        provider,
                                                        application.id(),
                                                        idJwt.getClaimAsString("sid"),
                                                        idJwt.getClaimAsString("name"),
                                                        idJwt.getClaimAsString("email"),
                                                        idJwt.getClaimAsString("picture"),
                                                        tokens.accessToken(),
                                                        expires,
                                                        tokens.refreshToken(),
                                                        tokens.idToken(),
                                                        now,
                                                        now,
                                                        0,
                                                        idJwt.getClaimAsString("acr"),
                                                        amr(idJwt),
                                                        authTime,
                                                        BffSessionClaims.select(
                                                                idJwt.getClaims(),
                                                                application
                                                                        .settings()
                                                                        .getSessionClaims(),
                                                                application.id()),
                                                        userAgent);
                                                return new NewSession(session, cookieValue);
                                            }));
                        }));
    }

    /** The {@code auth_time} claim, or {@code null} when absent or not a number. */
    private static Instant authTime(Jwt idJwt) {
        Object claim = idJwt.getClaims().get("auth_time");
        if (claim instanceof Instant i) return i;
        return claim instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
    }

    /** The {@code amr} claim as a list of short tokens; anything else is dropped. */
    private static List<String> amr(Jwt idJwt) {
        Object claim = idJwt.getClaims().get("amr");
        if (!(claim instanceof Collection<?> values)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object v : values) {
            if (v instanceof String s && !s.isEmpty() && s.length() <= 64 && s.indexOf(' ') < 0) out.add(s);
        }
        return out;
    }

    /** Length, control characters and, for {@code prompt} and {@code max_age}, their syntax. */
    private static boolean validParameter(String name, String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_PARAMETER_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return switch (name) {
            case "prompt" -> {
                String[] parts = value.split(" ", -1);
                for (String part : parts) {
                    if (!PROMPT_VALUES.contains(part)) yield false;
                }
                yield !value.contains("none") || parts.length == 1;
            }
            case "max_age" -> MAX_AGE.matcher(value).matches();
            default -> true;
        };
    }

    /** 400 naming the parameter (one of the configured names), never its value. */
    private static Mono<ServerResponse> invalidParameter(String name) {
        return json(HttpStatus.BAD_REQUEST, Map.of("error", "invalid_parameter", "parameter", name));
    }

    private Mono<ServerResponse> failureRedirect(BffLogin login, String state, String code) {
        String location = UriComponentsBuilder.fromUriString(login.returnTo())
                .queryParam("bff_error", code)
                .build(true)
                .toUriString();
        return redirect(location, cookies.clearLogin());
    }

    // ---- session -----------------------------------------------------------------------------

    Mono<ServerResponse> session(ServerRequest request) {
        String cookie = cookies.sessionValue(request.exchange().getRequest());
        if (cookie == null) return json(HttpStatus.OK, Map.of("authenticated", false));
        String key = BffSessionKeys.key(cookie);
        return store.find(key)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> {
                    Instant now = clock.instant();
                    if (found.isEmpty()) return signedOut(request);
                    BffSession s = found.get();
                    Instant expiresAt = sessionEnd(s);
                    if (!now.isBefore(expiresAt)) return store.delete(key).then(Mono.defer(() -> signedOut(request)));
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("authenticated", true);
                    body.put("subject", s.subject());
                    body.put("provider", s.provider());
                    body.put("application", s.application());
                    List<String> listed = sessionClaims(s);
                    if (listed.contains("name")) putIfPresent(body, "name", s.name());
                    if (listed.contains("email")) putIfPresent(body, "email", s.email());
                    if (listed.contains("picture")) putIfPresent(body, "picture", s.picture());
                    Map<String, Object> claims = new LinkedHashMap<>();
                    for (String name : listed) {
                        if (s.claims().containsKey(name))
                            claims.put(name, s.claims().get(name));
                    }
                    if (!claims.isEmpty()) body.put("claims", claims);
                    putIfPresent(body, "acr", s.acr());
                    if (!s.amr().isEmpty()) body.put("amr", s.amr());
                    if (s.authTime() != null) body.put("authTime", s.authTime().toString());
                    body.put("expiresAt", expiresAt.toString());
                    return json(HttpStatus.OK, body);
                });
    }

    /** The claim names the session's application lists now; the defaults for an application that is gone. */
    private List<String> sessionClaims(BffSession s) {
        return applicationOf(s)
                .map(a -> a.settings().getSessionClaims())
                .orElseGet(() -> new BffProperties.Application().getSessionClaims());
    }

    private Optional<BffApplications.Resolved> applicationOf(BffSession s) {
        return applications.all().stream()
                .filter(a -> a.id().equals(s.application()))
                .findFirst();
    }

    private Mono<ServerResponse> signedOut(ServerRequest request) {
        boolean alreadyCleared = request.exchange()
                .getResponse()
                .getCookies()
                .containsKey(properties.getCookie().getName());
        return alreadyCleared
                ? json(HttpStatus.OK, Map.of("authenticated", false))
                : json(HttpStatus.OK, Map.of("authenticated", false), cookies.clearSession());
    }

    private Instant sessionEnd(BffSession s) {
        Instant absolute = s.created().plus(properties.getSession().getAbsoluteTimeout());
        Instant idle = s.lastSeen().plus(properties.getSession().getIdleTimeout());
        return absolute.isBefore(idle) ? absolute : idle;
    }

    // ---- logout ------------------------------------------------------------------------------

    Mono<ServerResponse> logout(ServerRequest request) {
        String origin = BffApplications.normalizeOrigin(request.headers().firstHeader(HttpHeaders.ORIGIN));
        if (origin == null) return error(HttpStatus.FORBIDDEN, "origin_not_allowed");
        String cookie = cookies.sessionValue(request.exchange().getRequest());
        Mono<Optional<BffSession>> lookup = cookie == null
                ? Mono.just(Optional.<BffSession>empty())
                : store.find(BffSessionKeys.key(cookie)).map(Optional::of).defaultIfEmpty(Optional.empty());
        return lookup.flatMap(found -> {
            List<String> allowed = new ArrayList<>();
            BffApplications.Resolved owner = found.flatMap(s -> applications.all().stream()
                            .filter(a -> a.id().equals(s.application()))
                            .findFirst())
                    .orElse(null);
            if (owner != null) {
                owner.settings().getOrigins().forEach(o -> allowed.add(BffApplications.normalizeOrigin(o)));
            } else {
                applications
                        .all()
                        .forEach(a -> a.settings()
                                .getOrigins()
                                .forEach(o -> allowed.add(BffApplications.normalizeOrigin(o))));
            }
            if (!allowed.contains(origin)) return error(HttpStatus.FORBIDDEN, "origin_not_allowed");
            if (cookie == null) return json(HttpStatus.OK, Map.of());
            if (found.isEmpty()) return json(HttpStatus.OK, Map.of(), cookies.clearSession());
            return endAndRespond(found.get(), owner);
        });
    }

    /** The browser's own logout of {@code s}: deleted, revoked at the issuer, cookie cleared, end-session URL given. */
    private Mono<ServerResponse> endAndRespond(BffSession s, BffApplications.Resolved owner) {
        return store.delete(s.key())
                .doOnSuccess(v -> {
                    meter.count("user", 1);
                    if (revocation != null) revocation.revokeDetached(s);
                })
                .then(Mono.defer(() -> logoutUrl(s, owner)
                        .flatMap(url -> json(
                                HttpStatus.OK,
                                url.isEmpty() ? Map.of() : Map.of("logoutUrl", url.get()),
                                cookies.clearSession()))));
    }

    private Mono<Optional<String>> logoutUrl(BffSession s, BffApplications.Resolved owner) {
        BffProperties.Provider settings = providers.settings(s.provider());
        if (settings == null) return Mono.just(Optional.empty());
        return providers
                .endpoints(s.provider())
                .map(e -> {
                    if (e.endSessionUri() == null) return Optional.<String>empty();
                    Map<String, String> query = new LinkedHashMap<>();
                    if (s.idToken() != null) query.put("id_token_hint", s.idToken());
                    String redirect = postLogoutRedirect(owner);
                    if (redirect != null) query.put("post_logout_redirect_uri", redirect);
                    query.put("client_id", settings.getClientId());
                    return Optional.of(withQuery(e.endSessionUri(), query));
                })
                .onErrorResume(e -> {
                    log.warn("No end-session endpoint for provider '{}': {}", s.provider(), e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    private static String postLogoutRedirect(BffApplications.Resolved owner) {
        if (owner == null) return null;
        BffProperties.Application app = owner.settings();
        if (app.getPostLogoutRedirect() != null && !app.getPostLogoutRedirect().isBlank()) {
            return app.getPostLogoutRedirect();
        }
        return app.getReturnTo().isEmpty() ? null : app.getReturnTo().get(0);
    }

    // ---- my sessions -------------------------------------------------------------------------

    /**
     * {@code GET <path>/sessions}: the signed-in user's own sessions at the same provider, across
     * applications. Cookie callers only; a request with an {@code Authorization} header is refused.
     */
    Mono<ServerResponse> mySessions(ServerRequest request) {
        return caller(
                request,
                false,
                caller -> store.findBySubject(caller.provider(), caller.subject())
                        .filter(s -> live(s) && s.subject().equals(caller.subject()))
                        .take(MAX_LISTED_SESSIONS)
                        .collectList()
                        .flatMap(found -> {
                            List<Map<String, Object>> body = new ArrayList<>();
                            found.stream()
                                    .sorted(java.util.Comparator.comparing(BffSession::lastSeen)
                                            .reversed())
                                    .forEach(s -> {
                                        Map<String, Object> item = new LinkedHashMap<>();
                                        item.put("id", handles.handle(s.key()));
                                        item.put("application", s.application());
                                        item.put("created", s.created().toString());
                                        item.put("lastSeen", s.lastSeen().toString());
                                        item.put("current", s.key().equals(caller.key()));
                                        putIfPresent(item, "userAgent", s.userAgent());
                                        body.add(item);
                                    });
                            return json(HttpStatus.OK, Map.of("sessions", body));
                        }));
    }

    /**
     * {@code DELETE <path>/sessions/{handle}}: ends one of the caller's own sessions. A handle that is not
     * one of the caller's current sessions is {@code 404}, whoever it belongs to. Ending the session the
     * request carries is a logout (cookie cleared, token revoked, {@code logoutUrl} returned).
     */
    Mono<ServerResponse> endMySession(ServerRequest request) {
        String handle = request.pathVariable("handle");
        return caller(
                request,
                true,
                caller -> store.findBySubject(caller.provider(), caller.subject())
                        .filter(s ->
                                live(s) && s.subject().equals(caller.subject()) && handles.matches(handle, s.key()))
                        .next()
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(found -> {
                            if (found.isEmpty()) return error(HttpStatus.NOT_FOUND, "session_not_found");
                            BffSession target = found.get();
                            if (target.key().equals(caller.key()))
                                return endAndRespond(
                                        target, applicationOf(target).orElse(null));
                            return store.delete(target.key()).then(Mono.defer(() -> {
                                meter.count("user", 1);
                                if (revocation != null) revocation.revokeDetached(target);
                                return json(HttpStatus.OK, Map.of());
                            }));
                        }));
    }

    /**
     * Runs {@code next} with the caller's session. {@code 401} without a session cookie, with an unknown or
     * ended session, or with an {@code Authorization} header (cookie-only feature); {@code 403} when the
     * {@code Origin} is not one of the session's application's origins: required for an unsafe method, checked
     * when a safe request carries one.
     */
    private Mono<ServerResponse> caller(
            ServerRequest request, boolean unsafe, Function<BffSession, Mono<ServerResponse>> next) {
        String cookie = cookies.sessionValue(request.exchange().getRequest());
        if (cookie == null || request.headers().firstHeader(HttpHeaders.AUTHORIZATION) != null) {
            return error(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return store.find(BffSessionKeys.key(cookie))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> {
                    if (found.isEmpty() || !live(found.get())) return error(HttpStatus.UNAUTHORIZED, "unauthenticated");
                    BffSession s = found.get();
                    String header = request.headers().firstHeader(HttpHeaders.ORIGIN);
                    if (unsafe || header != null) {
                        String origin = BffApplications.normalizeOrigin(header);
                        boolean allowed = origin != null
                                && applicationOf(s)
                                        .map(a -> a.settings().getOrigins().stream()
                                                .map(BffApplications::normalizeOrigin)
                                                .anyMatch(origin::equals))
                                        .orElse(false);
                        if (!allowed) return error(HttpStatus.FORBIDDEN, "origin_not_allowed");
                    }
                    return next.apply(s);
                });
    }

    private boolean live(BffSession s) {
        return clock.instant().isBefore(sessionEnd(s));
    }

    // ---- providers ---------------------------------------------------------------------------

    Mono<ServerResponse> providers(ServerRequest request) {
        BffApplications.Resolved application;
        Optional<String> app = request.queryParam("app");
        if (app.isPresent()) {
            application = applications.all().stream()
                    .filter(a -> a.id().equals(app.get()))
                    .findFirst()
                    .orElse(null);
            if (application == null) return error(HttpStatus.NOT_FOUND, "unknown_application");
        } else {
            application = applications.resolve(request.exchange()).orElse(null);
            if (application == null) return error(HttpStatus.BAD_REQUEST, "application_unknown");
        }
        List<Map<String, String>> body = new ArrayList<>();
        for (String id : application.settings().getProviders()) {
            BffProperties.Provider p = providers.settings(id);
            if (p == null) continue;
            String name = p.getDisplayName() != null && !p.getDisplayName().isBlank() ? p.getDisplayName() : id;
            body.add(Map.of("id", id, "displayName", name));
        }
        return json(HttpStatus.OK, body);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Duration ttl(BffSession s) {
        Duration idle = properties.getSession().getIdleTimeout();
        Duration absolute = properties.getSession().getAbsoluteTimeout();
        return absolute.compareTo(idle) < 0 ? absolute : idle;
    }

    private String externalBase(ServerRequest request) {
        String configured = properties.getExternalBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        URI uri = request.uri();
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    /** Appends query parameters with their values percent-encoded; {@code base} may already have a query. */
    static String withQuery(String base, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base);
        params.forEach((k, v) -> builder.queryParam(URLEncoder.encode(k, StandardCharsets.UTF_8), encode(v)));
        return builder.build(true).toUriString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null) body.put(key, value);
    }

    private static Mono<ServerResponse> redirect(String location, ResponseCookie... cookies) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(HttpStatus.FOUND)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, location);
        for (ResponseCookie cookie : cookies) builder.cookie(cookie);
        return builder.build();
    }

    private static Mono<ServerResponse> error(HttpStatus status, String code, ResponseCookie... cookies) {
        return json(status, Map.of("error", code), cookies);
    }

    private static Mono<ServerResponse> json(HttpStatus status, Object body, ResponseCookie... cookies) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON);
        for (ResponseCookie cookie : cookies) builder.cookie(cookie);
        return builder.bodyValue(body);
    }
}
