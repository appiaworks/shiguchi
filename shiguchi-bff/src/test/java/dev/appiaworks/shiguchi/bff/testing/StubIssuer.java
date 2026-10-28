package dev.appiaworks.shiguchi.bff.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * In-process OIDC issuer for tests: discovery, JWKS, authorization-code + PKCE (S256), the refresh-token grant and a
 * logout endpoint, on a random local port. Not a real identity provider: no login UI, the authorize step approves
 * immediately.
 *
 * <p>Refresh tokens rotate, and reuse is punished the way Spring Authorization Server does: presenting a refresh token that was already
 * used revokes its whole family (every token of the sign-in, including the one the first use returned), so two
 * refreshes with the same token, concurrent or not, end in {@code invalid_grant} for the second and for every later
 * token of the family.
 */
public final class StubIssuer implements AutoCloseable {

    private record CodeGrant(
            String redirectUri, String challenge, String nonce, String clientId, String acr, Instant authTime) {}

    /** All refresh tokens of one sign-in. */
    private static final class Family {
        final String subject;
        volatile boolean revoked;

        Family(String subject) {
            this.subject = subject;
        }
    }

    private static final class RefreshToken {
        final Family family;
        boolean used;

        RefreshToken(Family family) {
            this.family = family;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RSAKey key;
    private final ECKey ecKey;
    private final DisposableServer server;
    private final String issuer;
    private final Map<String, CodeGrant> codes = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger discoveryCalls = new AtomicInteger();
    private final AtomicInteger refreshCalls = new AtomicInteger();
    private final AtomicInteger jwksCalls = new AtomicInteger();
    private final Map<String, RefreshToken> refreshTokens = new ConcurrentHashMap<>();
    private final Object refreshLock = new Object();
    private final AtomicInteger revocationCalls = new AtomicInteger();
    private final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();
    private final List<RevocationRequest> revocationRequests = new CopyOnWriteArrayList<>();

    private volatile String clientId;
    private volatile String clientSecret;
    private volatile String subject;
    private volatile Map<String, Object> accessClaims;
    private volatile Map<String, Object> idClaims;
    private volatile Duration accessTokenLifetime;
    private volatile String nonceOverride;
    private volatile String authorizeError;
    private volatile boolean issuerSession;
    private volatile String acrOverride;
    private volatile List<String> amr;
    private volatile Duration authTimeAge;
    private volatile boolean honorReauth;
    private volatile boolean omitAuthTime;
    private volatile boolean ecSigning;
    private volatile boolean discovery;
    private volatile boolean endSession;
    private volatile Map<String, String> lastAuthorize = Map.of();
    private volatile Map<String, String> lastLogout = Map.of();
    private volatile String lastTokenRequestBody;
    private volatile Duration refreshDelay;
    private volatile int refreshStatus;
    private volatile boolean refreshIssuesIdToken;
    private volatile String refreshSubject;
    private volatile String refreshIdTokenSubject;
    private volatile boolean refreshIdTokenForeign;
    private volatile Duration jwksDelay;
    private volatile boolean familyRevoked;
    private volatile boolean revocationAdvertised;
    private volatile int revocationStatus;
    private volatile Duration revocationDelay;

    /** One request to the revocation endpoint: the token, its hint, and how the client authenticated. */
    public record RevocationRequest(String token, String hint, boolean basicAuth, boolean formAuth) {}

    private StubIssuer() {
        try {
            this.key = new RSAKeyGenerator(2048)
                    .keyID("stub-" + UUID.randomUUID())
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
            this.ecKey = new ECKeyGenerator(Curve.P_256)
                    .keyID("stub-ec-" + UUID.randomUUID())
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        applyDefaults();
        this.server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(r -> r.get("/.well-known/openid-configuration", this::handleDiscovery)
                        .get("/jwks", this::handleJwks)
                        .get("/authorize", this::handleAuthorize)
                        .post("/token", this::handleToken)
                        .post("/revoke", this::handleRevoke)
                        .get("/logout", this::handleLogout))
                .bindNow();
        this.issuer = "http://localhost:" + server.port();
    }

    /** Starts an issuer on a random port with a fresh RSA 2048 key (RS256). */
    public static StubIssuer start() {
        return new StubIssuer();
    }

    private void applyDefaults() {
        clientId = "gateway";
        clientSecret = "secret";
        subject = "alice";
        accessClaims = Map.of();
        idClaims = Map.of();
        accessTokenLifetime = Duration.ofMinutes(10);
        nonceOverride = null;
        authorizeError = null;
        issuerSession = true;
        acrOverride = null;
        amr = List.of("pwd");
        authTimeAge = Duration.ZERO;
        honorReauth = true;
        omitAuthTime = false;
        ecSigning = false;
        discovery = true;
        endSession = true;
        refreshDelay = Duration.ZERO;
        refreshStatus = 0;
        refreshIssuesIdToken = true;
        refreshSubject = null;
        refreshIdTokenSubject = null;
        refreshIdTokenForeign = false;
        jwksDelay = Duration.ZERO;
        revocationAdvertised = true;
        revocationStatus = 0;
        revocationDelay = Duration.ZERO;
    }

    /** The issuer URL, {@code http://localhost:<port>}. */
    public String issuer() {
        return issuer;
    }

    /**
     * Signs every token with the given algorithm and serves the matching key in the JWK set: {@code RS256} (the
     * default, RSA 2048) or {@code ES256} (EC P-256). {@link #reset()} goes back to RS256.
     */
    public StubIssuer signingAlgorithm(String algorithm) {
        if (!"RS256".equals(algorithm) && !"ES256".equals(algorithm)) {
            throw new IllegalArgumentException("unsupported stub algorithm " + algorithm);
        }
        this.ecSigning = "ES256".equals(algorithm);
        return this;
    }

    /** Sets the registered client id and secret (default {@code gateway}/{@code secret}). */
    public StubIssuer client(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        return this;
    }

    /** Sets the {@code sub} of issued tokens (default {@code alice}). */
    public StubIssuer subject(String sub) {
        this.subject = sub;
        return this;
    }

    /** Claims merged into issued access tokens (for example {@code scope} or {@code aud}). */
    public StubIssuer accessClaims(Map<String, Object> extra) {
        this.accessClaims = Map.copyOf(extra);
        return this;
    }

    /** Claims merged into issued ID tokens (for example name, email, picture, sid, idp). */
    public StubIssuer idClaims(Map<String, Object> extra) {
        this.idClaims = Map.copyOf(extra);
        return this;
    }

    /** Access token lifetime (default 10 minutes). */
    public StubIssuer accessTokenLifetime(Duration d) {
        this.accessTokenLifetime = d;
        return this;
    }

    /** ID tokens carry this nonce instead of the requested one; the empty string omits the claim. */
    public StubIssuer nonceOverride(String nonce) {
        this.nonceOverride = nonce;
        return this;
    }

    /** Makes {@code /authorize} redirect back with {@code error=<code>} instead of an authorization code. */
    public StubIssuer authorizeError(String code) {
        this.authorizeError = code;
        return this;
    }

    /**
     * Whether the user has a session at the issuer (default true: {@code /authorize} approves at once). With false,
     * {@code prompt=none} is answered {@code error=login_required}; any other request still approves, as if the user
     * signed in on the spot.
     */
    public StubIssuer issuerSession(boolean present) {
        this.issuerSession = present;
        return this;
    }

    /**
     * The {@code acr} of issued ID tokens, whatever {@code acr_values} asked for. Unset (default): the first value
     * of {@code acr_values} when the request had one, else {@code stub:basic}.
     */
    public StubIssuer acr(String acr) {
        this.acrOverride = acr;
        return this;
    }

    /** The {@code amr} of issued ID tokens (default {@code ["pwd"]}); an empty list omits the claim. */
    public StubIssuer amr(List<String> methods) {
        this.amr = List.copyOf(methods);
        return this;
    }

    /**
     * How long ago the user authenticated, as {@code auth_time} of issued ID tokens (default zero: now). With
     * {@link #honorReauth} (default), a request with {@code prompt=login} or a {@code max_age} smaller than this age
     * makes the user authenticate again, so {@code auth_time} is now.
     */
    public StubIssuer authTimeAge(Duration age) {
        this.authTimeAge = age;
        return this;
    }

    /** False: {@code prompt=login} and {@code max_age} are ignored, so a stale {@code auth_time} comes back. */
    public StubIssuer honorReauth(boolean honor) {
        this.honorReauth = honor;
        return this;
    }

    /** True: ID tokens carry no {@code auth_time}, even when {@code max_age} was asked for. */
    public StubIssuer omitAuthTime(boolean omit) {
        this.omitAuthTime = omit;
        return this;
    }

    /** When false, the discovery document answers 404. */
    public StubIssuer discovery(boolean enabled) {
        this.discovery = enabled;
        return this;
    }

    /** Whether discovery advertises {@code end_session_endpoint} (default true). */
    public StubIssuer endSession(boolean advertised) {
        this.endSession = advertised;
        return this;
    }

    /** Whether discovery advertises {@code revocation_endpoint} (default true). */
    public StubIssuer revocationAdvertised(boolean advertised) {
        this.revocationAdvertised = advertised;
        return this;
    }

    /** Answers every revocation request with this HTTP status (for example 500); 0 turns it off (answers 200). */
    public StubIssuer revocationStatus(int status) {
        this.revocationStatus = status;
        return this;
    }

    /** Holds every revocation request this long before answering (default none). */
    public StubIssuer revocationDelay(Duration d) {
        this.revocationDelay = d;
        return this;
    }

    /** Number of revocation requests received, authenticated or not. */
    public int revocationCalls() {
        return revocationCalls.get();
    }

    /** Tokens revoked by an authenticated request answered 2xx. */
    public Set<String> revokedTokens() {
        return Set.copyOf(revokedTokens);
    }

    /** Every revocation request received, in order. */
    public List<RevocationRequest> revocationRequests() {
        return List.copyOf(revocationRequests);
    }

    /** The last revocation request, or null when there was none. */
    public RevocationRequest lastRevocation() {
        return revocationRequests.isEmpty() ? null : revocationRequests.get(revocationRequests.size() - 1);
    }

    /** Holds every {@code refresh_token} grant this long before answering (default none). */
    public StubIssuer refreshDelay(Duration d) {
        this.refreshDelay = d;
        return this;
    }

    /** Answers every {@code refresh_token} grant with this HTTP status (for example 503); 0 turns it off. */
    public StubIssuer refreshStatus(int status) {
        this.refreshStatus = status;
        return this;
    }

    /** Whether a refresh response carries a new ID token (default true). */
    public StubIssuer refreshIssuesIdToken(boolean issued) {
        this.refreshIssuesIdToken = issued;
        return this;
    }

    /** Refreshed tokens carry this {@code sub} instead of the family's; null restores it. */
    public StubIssuer refreshSubject(String sub) {
        this.refreshSubject = sub;
        return this;
    }

    /** The {@code sub} of the ID token a refresh response carries, when it should differ from the access token's. */
    public StubIssuer refreshIdTokenSubject(String sub) {
        this.refreshIdTokenSubject = sub;
        return this;
    }

    /** Signs the ID token of a refresh response with a key the JWK set does not hold. */
    public StubIssuer refreshIdTokenForeign(boolean foreign) {
        this.refreshIdTokenForeign = foreign;
        return this;
    }

    /** Holds every JWK set request this long before answering (default none). */
    public StubIssuer jwksDelay(Duration d) {
        this.jwksDelay = d;
        return this;
    }

    /** Number of JWK set requests received. */
    public int jwksCalls() {
        return jwksCalls.get();
    }

    /** A new refresh token in a new family, as if {@code sub} had signed in; for tests that seed sessions directly. */
    public String issueRefreshToken(String sub) {
        return newRefreshToken(new Family(sub));
    }

    /** Revokes every family, as the real issuer does when a session is ended there. */
    public void revokeAllRefreshTokens() {
        synchronized (refreshLock) {
            refreshTokens.values().forEach(t -> t.family.revoked = true);
            familyRevoked = true;
        }
    }

    /** Number of {@code refresh_token} grants received, accepted or not. */
    public int refreshCalls() {
        return refreshCalls.get();
    }

    /** Whether any family has been revoked, by reuse or by {@link #revokeAllRefreshTokens}. */
    public boolean familyRevoked() {
        return familyRevoked;
    }

    /** Decoded query parameters of the last {@code /authorize} request. */
    public Map<String, String> lastAuthorize() {
        return lastAuthorize;
    }

    /** Decoded query parameters of the last {@code /logout} request. */
    public Map<String, String> lastLogout() {
        return lastLogout;
    }

    /** Number of {@code /token} requests received. */
    public int tokenCalls() {
        return tokenCalls.get();
    }

    /** Number of discovery document requests received. */
    public int discoveryCalls() {
        return discoveryCalls.get();
    }

    /** Raw form body of the last {@code /token} request. */
    public String lastTokenRequestBody() {
        return lastTokenRequestBody;
    }

    /** A valid signed access token for {@code sub}, built like the ones {@code /token} issues. */
    public String accessToken(String sub) {
        return sign(accessTokenClaims(sub));
    }

    /** Signs arbitrary claims with the issuer key (RS256, or ES256 after {@link #signingAlgorithm}; with kid). */
    public String sign(Map<String, Object> claims) {
        return signWith(activeKey(), claims, null);
    }

    private JWK activeKey() {
        return ecSigning ? ecKey : key;
    }

    /**
     * Signs claims with the issuer key like {@link #sign}, with the given JOSE header {@code typ} (a back-channel
     * logout token carries {@code logout+jwt}); a null {@code typ} leaves the header without one.
     */
    public String signAs(Map<String, Object> claims, String typ) {
        return signWith(activeKey(), claims, typ);
    }

    /** Signs claims with a key the JWK set does not hold, under this issuer's {@code kid}: a forged signature. */
    public String signForeign(Map<String, Object> claims, String typ) {
        try {
            JWK foreign = ecSigning
                    ? new ECKeyGenerator(Curve.P_256).keyID(ecKey.getKeyID()).generate()
                    : new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
            return signWith(foreign, claims, typ);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The claims as an unsecured JWT ({@code alg: none}), which no verifier may accept. */
    public String unsigned(Map<String, Object> claims) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
        claims.forEach(b::claim);
        return new PlainJWT(b.build()).serialize();
    }

    /** The registered client id, as the {@code aud} of tokens meant for the gateway. */
    public String clientId() {
        return clientId;
    }

    private static String signWith(JWK signingKey, Map<String, Object> claims, String typ) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
            claims.forEach(b::claim);
            boolean ec = signingKey instanceof ECKey;
            JWSHeader.Builder header =
                    new JWSHeader.Builder(ec ? JWSAlgorithm.ES256 : JWSAlgorithm.RS256).keyID(signingKey.getKeyID());
            if (typ != null) header.type(new JOSEObjectType(typ));
            SignedJWT jwt = new SignedJWT(header.build(), b.build());
            JWSSigner signer = ec ? new ECDSASigner((ECKey) signingKey) : new RSASSASigner((RSAKey) signingKey);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Restores defaults and zeroes the counters; recorded codes are forgotten. */
    public void reset() {
        applyDefaults();
        codes.clear();
        tokenCalls.set(0);
        discoveryCalls.set(0);
        refreshCalls.set(0);
        jwksCalls.set(0);
        refreshTokens.clear();
        revocationCalls.set(0);
        revokedTokens.clear();
        revocationRequests.clear();
        familyRevoked = false;
        lastAuthorize = Map.of();
        lastLogout = Map.of();
        lastTokenRequestBody = null;
    }

    @Override
    public void close() {
        server.disposeNow();
    }

    // --- handlers -------------------------------------------------------------------------------------------

    private Publisher<Void> handleDiscovery(HttpServerRequest req, HttpServerResponse res) {
        discoveryCalls.incrementAndGet();
        if (!discovery) {
            return text(res, 404, "not found");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", issuer + "/authorize");
        doc.put("token_endpoint", issuer + "/token");
        doc.put("jwks_uri", issuer + "/jwks");
        if (endSession) {
            doc.put("end_session_endpoint", issuer + "/logout");
        }
        if (revocationAdvertised) {
            doc.put("revocation_endpoint", issuer + "/revoke");
        }
        doc.put("response_types_supported", List.of("code"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of(ecSigning ? "ES256" : "RS256"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        return json(res, 200, JSONObjectUtils.toJSONString(doc));
    }

    private Publisher<Void> handleJwks(HttpServerRequest req, HttpServerResponse res) {
        jwksCalls.incrementAndGet();
        Duration delay = jwksDelay;
        String body = new JWKSet(activeKey().toPublicJWK()).toString();
        if (delay == null || delay.isZero() || delay.isNegative()) return json(res, 200, body);
        return Mono.delay(delay).thenMany(json(res, 200, body));
    }

    private Publisher<Void> handleAuthorize(HttpServerRequest req, HttpServerResponse res) {
        Map<String, String> q = parseForm(rawQuery(req.uri()));
        lastAuthorize = q;
        for (String required : new String[] {"response_type", "client_id", "redirect_uri", "state", "code_challenge"}) {
            if (blank(q.get(required))) {
                return text(res, 400, "missing " + required);
            }
        }
        if (!"code".equals(q.get("response_type"))) {
            return text(res, 400, "response_type must be code");
        }
        if (!"S256".equals(q.get("code_challenge_method"))) {
            return text(res, 400, "code_challenge_method must be S256");
        }
        String redirect = q.get("redirect_uri");
        String sep = redirect.contains("?") ? "&" : "?";
        String state = enc(q.get("state"));
        String location;
        if (authorizeError != null) {
            location = redirect + sep + "error=" + enc(authorizeError) + "&state=" + state;
        } else if ("none".equals(q.get("prompt")) && !issuerSession) {
            location = redirect + sep + "error=login_required&state=" + state;
        } else {
            String code = randomToken();
            codes.put(
                    code,
                    new CodeGrant(
                            redirect,
                            q.get("code_challenge"),
                            q.get("nonce"),
                            q.get("client_id"),
                            acrFor(q),
                            authTimeFor(q)));
            location = redirect + sep + "code=" + code + "&state=" + state;
        }
        return res.status(302).header("Location", location).send();
    }

    private String acrFor(Map<String, String> q) {
        if (acrOverride != null) return acrOverride;
        String requested = q.get("acr_values");
        if (!blank(requested)) return requested.trim().split("\\s+")[0];
        return "stub:basic";
    }

    private Instant authTimeFor(Map<String, String> q) {
        Duration age = authTimeAge;
        if (honorReauth) {
            String prompt = q.get("prompt");
            boolean login = prompt != null && List.of(prompt.split(" ")).contains("login");
            String maxAge = q.get("max_age");
            if (login || (!blank(maxAge) && Long.parseLong(maxAge) < age.toSeconds())) age = Duration.ZERO;
        }
        return Instant.now().minus(age);
    }

    private Publisher<Void> handleToken(HttpServerRequest req, HttpServerResponse res) {
        String authorization = req.requestHeaders().get("Authorization");
        return req.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
            tokenCalls.incrementAndGet();
            lastTokenRequestBody = body;
            Map<String, String> form = parseForm(body);
            if (!authenticated(authorization, form)) {
                return Mono.from(json(res, 401, "{\"error\":\"invalid_client\"}"));
            }
            if ("refresh_token".equals(form.get("grant_type"))) {
                return Mono.from(handleRefresh(res, form));
            }
            CodeGrant grant = form.get("code") == null ? null : codes.remove(form.get("code"));
            if (!"authorization_code".equals(form.get("grant_type"))
                    || grant == null
                    || !grant.redirectUri().equals(form.get("redirect_uri"))
                    || !pkceMatches(form.get("code_verifier"), grant.challenge())) {
                return Mono.from(json(res, 400, "{\"error\":\"invalid_grant\"}"));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("access_token", sign(accessTokenClaims(subject)));
            out.put("token_type", "Bearer");
            out.put("expires_in", accessTokenLifetime.toSeconds());
            out.put("refresh_token", newRefreshToken(new Family(subject)));
            out.put("id_token", sign(idTokenClaims(grant)));
            out.put("scope", "openid profile email");
            return Mono.from(json(res, 200, JSONObjectUtils.toJSONString(out)));
        });
    }

    private Publisher<Void> handleRefresh(HttpServerResponse res, Map<String, String> form) {
        refreshCalls.incrementAndGet();
        Duration delay = refreshDelay;
        return Mono.delay(delay.isNegative() ? Duration.ZERO : delay).flatMap(t -> {
            if (refreshStatus != 0) {
                return Mono.from(json(res, refreshStatus, "{\"error\":\"temporarily_unavailable\"}"));
            }
            Family family;
            synchronized (refreshLock) {
                String presentedToken = form.get("refresh_token");
                RefreshToken presented = presentedToken == null ? null : refreshTokens.get(presentedToken);
                if (presented == null || presented.family.revoked) {
                    return Mono.from(json(res, 400, "{\"error\":\"invalid_grant\"}"));
                }
                if (presented.used) {
                    presented.family.revoked = true;
                    familyRevoked = true;
                    return Mono.from(json(res, 400, "{\"error\":\"invalid_grant\"}"));
                }
                presented.used = true;
                family = presented.family;
            }
            String sub = refreshSubject != null ? refreshSubject : family.subject;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("access_token", sign(accessTokenClaims(sub)));
            out.put("token_type", "Bearer");
            out.put("expires_in", accessTokenLifetime.toSeconds());
            out.put("refresh_token", newRefreshToken(family));
            if (refreshIssuesIdToken) {
                String idSub = refreshIdTokenSubject != null ? refreshIdTokenSubject : sub;
                Map<String, Object> idClaimSet =
                        idTokenClaims(new CodeGrant(null, null, null, clientId, null, null), idSub);
                out.put("id_token", refreshIdTokenForeign ? signForeign(idClaimSet, null) : sign(idClaimSet));
            }
            out.put("scope", "openid profile email");
            return Mono.from(json(res, 200, JSONObjectUtils.toJSONString(out)));
        });
    }

    private Publisher<Void> handleRevoke(HttpServerRequest req, HttpServerResponse res) {
        String authorization = req.requestHeaders().get("Authorization");
        return req.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
            revocationCalls.incrementAndGet();
            Map<String, String> form = parseForm(body);
            boolean basic = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
            revocationRequests.add(new RevocationRequest(
                    form.get("token"), form.get("token_type_hint"), basic, form.containsKey("client_id")));
            Duration delay = revocationDelay;
            return Mono.delay(delay.isNegative() ? Duration.ZERO : delay).flatMap(t -> {
                if (!authenticated(authorization, form)) {
                    return Mono.from(json(res, 401, "{\"error\":\"invalid_client\"}"));
                }
                if (revocationStatus != 0) {
                    return Mono.from(json(res, revocationStatus, "{\"error\":\"server_error\"}"));
                }
                String token = form.get("token");
                if (token != null) {
                    revokedTokens.add(token);
                    RefreshToken known = refreshTokens.get(token);
                    if (known != null) {
                        known.family.revoked = true;
                    }
                }
                // RFC 7009 section 2.2: 200 also for a token the issuer does not know.
                return Mono.from(text(res, 200, ""));
            });
        });
    }

    private String newRefreshToken(Family family) {
        String token = randomToken();
        refreshTokens.put(token, new RefreshToken(family));
        return token;
    }

    private Publisher<Void> handleLogout(HttpServerRequest req, HttpServerResponse res) {
        lastLogout = parseForm(rawQuery(req.uri()));
        return text(res, 200, "logged out");
    }

    // --- token building -------------------------------------------------------------------------------------

    private Map<String, Object> accessTokenClaims(String sub) {
        Instant now = Instant.now();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", issuer);
        c.put("sub", sub);
        c.put("aud", "gateway-api");
        c.put("iat", now.getEpochSecond());
        c.put("exp", now.plus(accessTokenLifetime).getEpochSecond());
        c.put("scope", "openid profile email");
        c.put("jti", UUID.randomUUID().toString());
        c.putAll(accessClaims);
        return c;
    }

    private Map<String, Object> idTokenClaims(CodeGrant grant) {
        return idTokenClaims(grant, subject);
    }

    private Map<String, Object> idTokenClaims(CodeGrant grant, String sub) {
        Instant now = Instant.now();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", issuer);
        c.put("sub", sub);
        c.put("aud", grant.clientId());
        c.put("azp", grant.clientId());
        c.put("iat", now.getEpochSecond());
        c.put("exp", now.plus(Duration.ofMinutes(5)).getEpochSecond());
        String nonce = nonceOverride != null ? nonceOverride : grant.nonce();
        if (!blank(nonce)) {
            c.put("nonce", nonce);
        }
        c.put("sid", "sid-" + randomToken());
        c.put("idp", "stub");
        if (grant.acr() != null) c.put("acr", grant.acr());
        if (grant.authTime() != null && !omitAuthTime)
            c.put("auth_time", grant.authTime().getEpochSecond());
        if (grant.acr() != null && !amr.isEmpty()) c.put("amr", amr);
        c.putAll(idClaims);
        return c;
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private boolean authenticated(String authorization, Map<String, String> form) {
        String id;
        String secret;
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(
                        Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon < 0) {
                    return false;
                }
                id = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
                secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return false;
            }
        } else {
            id = form.get("client_id");
            secret = form.get("client_secret");
        }
        return clientId.equals(id) && clientSecret.equals(secret);
    }

    private static boolean pkceMatches(String verifier, String challenge) {
        if (verifier == null) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest)
                    .equals(challenge);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Publisher<Void> json(HttpServerResponse res, int status, String body) {
        return res.status(status)
                .header("Content-Type", "application/json")
                .sendString(Mono.just(body))
                .then();
    }

    private static Publisher<Void> text(HttpServerResponse res, int status, String body) {
        return res.status(status)
                .header("Content-Type", "text/plain")
                .sendString(Mono.just(body))
                .then();
    }

    private static String rawQuery(String uri) {
        int q = uri.indexOf('?');
        return q < 0 ? "" : uri.substring(q + 1);
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
