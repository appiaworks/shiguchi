package dev.appiaworks.shiguchi.bff;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWKSecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * What the module needs from each configured identity provider: its endpoints (explicit, else
 * discovered lazily and cached on success), the token exchange, and the decoders for its tokens.
 * Built without {@code spring-security-oauth2-client}, whose builders differ between Spring
 * Security 6.5 and 7.1. A down issuer never fails startup: nothing is fetched until a sign-in
 * needs it, and a failure is not cached.
 */
final class BffProviders {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** How long decoding a token (and so fetching the issuer's JWK set) may take. */
    static final Duration JWKS_TIMEOUT = Duration.ofSeconds(5);

    static final Duration REVOCATION_TIMEOUT = Duration.ofSeconds(5);
    private volatile Duration revocationTimeout = REVOCATION_TIMEOUT;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    /** Resolved endpoints of one provider; {@code endSessionUri} and {@code revocationUri} may be {@code null}. */
    record Endpoints(
            String authorizationUri, String tokenUri, String jwkSetUri, String endSessionUri, String revocationUri) {}

    /** The parts of a token response the module uses. */
    record Tokens(String accessToken, String idToken, String refreshToken) {}

    private final BffProperties properties;
    private final WebClient webClient;
    private final Clock clock;
    private final ConcurrentHashMap<String, Endpoints> endpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReactiveJwtDecoder> accessDecoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReactiveJwtDecoder> idDecoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReactiveJwtDecoder> logoutDecoders = new ConcurrentHashMap<>();

    BffProviders(BffProperties properties, WebClient.Builder webClientBuilder) {
        this(properties, webClientBuilder, Clock.systemUTC());
    }

    BffProviders(BffProperties properties, WebClient.Builder webClientBuilder, Clock clock) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.clock = clock;
    }

    BffProperties.Provider settings(String id) {
        return properties.getProviders().get(id);
    }

    /** The provider's endpoints; errors when discovery fails (and is retried by the next call). */
    Mono<Endpoints> endpoints(String id) {
        return Mono.defer(() -> {
            Endpoints cached = endpoints.get(id);
            if (cached != null) return Mono.just(cached);
            BffProperties.Provider p = settings(id);
            if (p == null) return Mono.error(new IllegalArgumentException("unknown provider " + id));
            if (!blank(p.getAuthorizationUri()) && !blank(p.getTokenUri()) && !blank(p.getJwkSetUri())) {
                Endpoints explicit = new Endpoints(
                        p.getAuthorizationUri(),
                        p.getTokenUri(),
                        p.getJwkSetUri(),
                        emptyToNull(p.getEndSessionUri()),
                        emptyToNull(p.getRevocationUri()));
                endpoints.put(id, explicit);
                return Mono.just(explicit);
            }
            return discover(p).doOnNext(e -> endpoints.put(id, e));
        });
    }

    private Mono<Endpoints> discover(BffProperties.Provider p) {
        String issuer = p.getIssuerUri();
        String url = stripTrailingSlash(issuer) + "/.well-known/openid-configuration";
        return webClient
                .get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JSON_OBJECT)
                .timeout(TIMEOUT)
                .flatMap(doc -> {
                    Object documentIssuer = doc.get("issuer");
                    if (documentIssuer == null
                            || !stripTrailingSlash(documentIssuer.toString()).equals(stripTrailingSlash(issuer))) {
                        return Mono.error(new IllegalStateException(
                                "discovery document of " + issuer + " names issuer " + documentIssuer));
                    }
                    String authorization = pick(p.getAuthorizationUri(), doc.get("authorization_endpoint"));
                    String token = pick(p.getTokenUri(), doc.get("token_endpoint"));
                    String jwks = pick(p.getJwkSetUri(), doc.get("jwks_uri"));
                    if (authorization == null || token == null || jwks == null) {
                        return Mono.error(new IllegalStateException(
                                "discovery document of " + issuer + " lacks an authorization, token or jwks endpoint"));
                    }
                    return Mono.just(new Endpoints(
                            authorization,
                            token,
                            jwks,
                            pick(p.getEndSessionUri(), doc.get("end_session_endpoint")),
                            pick(p.getRevocationUri(), doc.get("revocation_endpoint"))));
                });
    }

    /** Exchanges an authorization code (PKCE) for tokens. A response without access and ID token is an error. */
    Mono<Tokens> exchange(String id, String code, String redirectUri, String codeVerifier) {
        BffProperties.Provider p = settings(id);
        return endpoints(id)
                .flatMap(e -> {
                    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                    form.add("grant_type", "authorization_code");
                    form.add("code", code);
                    form.add("redirect_uri", redirectUri);
                    form.add("code_verifier", codeVerifier);
                    return tokenRequest(p, e, form)
                            .retrieve()
                            .bodyToMono(JSON_OBJECT)
                            .timeout(TIMEOUT);
                })
                .flatMap(body -> {
                    String access = string(body.get("access_token"));
                    String idToken = string(body.get("id_token"));
                    if (access == null || idToken == null) {
                        return Mono.error(new IllegalStateException("token response lacks access_token or id_token"));
                    }
                    return Mono.just(new Tokens(access, idToken, string(body.get("refresh_token"))));
                });
    }

    /** What a {@code refresh_token} grant returned; the ID token and the rotated refresh token may be absent. */
    record Refreshed(String accessToken, String idToken, String refreshToken) {}

    /** The token endpoint answered 400 or 401: the refresh token (or the client) is refused. */
    static final class TokenRefusedException extends RuntimeException {
        TokenRefusedException(int status) {
            super("the token endpoint answered " + status + " to a refresh_token grant");
        }
    }

    /**
     * Exchanges a refresh token. A 400 or 401 answer is a {@link TokenRefusedException}; every other
     * failure (a timeout after {@link BffTokenRefresher#TOKEN_ENDPOINT_TIMEOUT}, a connection error,
     * a 5xx or other status, a body without an access token) is an ordinary error, which callers
     * treat as the issuer being unavailable.
     */
    Mono<Refreshed> refresh(String id, String refreshToken) {
        BffProperties.Provider p = settings(id);
        if (p == null) return Mono.error(new IllegalArgumentException("unknown provider " + id));
        return endpoints(id)
                .flatMap(e -> {
                    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                    form.add("grant_type", "refresh_token");
                    form.add("refresh_token", refreshToken);
                    return tokenRequest(p, e, form).exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status == 400 || status == 401) {
                            return response.releaseBody()
                                    .then(Mono.<Map<String, Object>>error(new TokenRefusedException(status)));
                        }
                        if (status < 200 || status >= 300) {
                            return response.releaseBody()
                                    .then(Mono.<Map<String, Object>>error(new IllegalStateException(
                                            "the token endpoint answered " + status + " to a refresh_token grant")));
                        }
                        return response.bodyToMono(JSON_OBJECT);
                    });
                })
                .timeout(BffTokenRefresher.TOKEN_ENDPOINT_TIMEOUT)
                .flatMap(body -> {
                    String access = string(body.get("access_token"));
                    if (access == null) {
                        return Mono.error(new IllegalStateException("refresh response lacks access_token"));
                    }
                    return Mono.just(
                            new Refreshed(access, string(body.get("id_token")), string(body.get("refresh_token"))));
                });
    }

    /**
     * Revokes a token at the provider's revocation endpoint (RFC 7009), authenticating the client as for
     * the token endpoint. Completes empty on a 2xx answer, and also when the provider has no revocation
     * endpoint ({@link #hasRevocation} tells which); any other answer, a timeout or a connection error is an error.
     * The error never carries the token.
     */
    Mono<Void> revoke(String id, String token, String hint) {
        BffProperties.Provider p = settings(id);
        if (p == null) return Mono.error(new IllegalArgumentException("unknown provider " + id));
        return endpoints(id)
                .flatMap(e -> {
                    if (e.revocationUri() == null) return Mono.<Void>empty();
                    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                    form.add("token", token);
                    form.add("token_type_hint", hint);
                    return postForm(p, e.revocationUri(), form).exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        return response.releaseBody()
                                .then(
                                        status >= 200 && status < 300
                                                ? Mono.<Void>empty()
                                                : Mono.<Void>error(new RevocationFailedException(status)));
                    });
                })
                .timeout(revocationTimeout);
    }

    /** Shortens the revocation timeout, for tests. */
    BffProviders revocationTimeout(Duration timeout) {
        this.revocationTimeout = timeout;
        return this;
    }

    /** Whether the provider has a revocation endpoint (explicit or discovered); errors when discovery fails. */
    Mono<Boolean> hasRevocation(String id) {
        return endpoints(id).map(e -> e.revocationUri() != null);
    }

    /** The revocation endpoint answered something other than 2xx. */
    static final class RevocationFailedException extends RuntimeException {
        RevocationFailedException(int status) {
            super("the revocation endpoint answered " + status);
        }
    }

    /** A form POST to the token endpoint with the client authenticated as the provider is configured to. */
    private WebClient.RequestHeadersSpec<?> tokenRequest(
            BffProperties.Provider p, Endpoints e, MultiValueMap<String, String> form) {
        return postForm(p, e.tokenUri(), form);
    }

    private WebClient.RequestHeadersSpec<?> postForm(
            BffProperties.Provider p, String uri, MultiValueMap<String, String> form) {
        WebClient.RequestBodySpec request = webClient.post().uri(uri);
        if (p.getClientAuthenticationMethod() == BffProperties.ClientAuthenticationMethod.POST) {
            form.add("client_id", p.getClientId());
            form.add("client_secret", p.getClientSecret());
        } else {
            request.headers(h -> h.setBasicAuth(formEncode(p.getClientId()), formEncode(p.getClientSecret())));
        }
        return request.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromFormData(form));
    }

    /** Verifies access tokens: signature, expiry, issuer, and the audiences when configured. */
    Mono<ReactiveJwtDecoder> accessDecoder(String id) {
        return endpoints(id)
                .map(e -> accessDecoders.computeIfAbsent(id, k -> {
                    BffProperties.Provider p = settings(id);
                    List<String> audiences = List.copyOf(p.getAudiences());
                    OAuth2TokenValidator<Jwt> base = new DelegatingOAuth2TokenValidator<>(
                            new JwtTimestampValidator(), new JwtIssuerValidator(p.getIssuerUri()));
                    OAuth2TokenValidator<Jwt> validator = audiences.isEmpty()
                            ? base
                            : new DelegatingOAuth2TokenValidator<>(base, jwt -> {
                                List<String> aud = jwt.getAudience();
                                return aud != null && aud.stream().anyMatch(audiences::contains)
                                        ? OAuth2TokenValidatorResult.success()
                                        : failure("The access token audience is not accepted");
                            });
                    return decoder(p, e.jwkSetUri(), validator, null);
                }));
    }

    /**
     * Verifies ID tokens: signature, expiry, issuer, audience contains the client id, {@code azp}
     * equals it when present. The nonce is the caller's to compare (it differs per login).
     */
    Mono<ReactiveJwtDecoder> idTokenDecoder(String id) {
        return endpoints(id)
                .map(e -> idDecoders.computeIfAbsent(id, k -> {
                    BffProperties.Provider p = settings(id);
                    String clientId = p.getClientId();
                    OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                            new JwtTimestampValidator(), new JwtIssuerValidator(p.getIssuerUri()), jwt -> {
                                List<String> aud = jwt.getAudience();
                                if (aud == null || !aud.contains(clientId))
                                    return failure("The ID token audience is wrong");
                                String azp = jwt.getClaimAsString("azp");
                                return azp != null && !azp.equals(clientId)
                                        ? failure("The ID token authorized party is wrong")
                                        : OAuth2TokenValidatorResult.success();
                            });
                    return decoder(p, e.jwkSetUri(), validator, null);
                }));
    }

    /** The event a logout token must announce (OpenID Connect Back-Channel Logout 1.0, section 2.4). */
    static final String BACKCHANNEL_LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";

    /** Clock skew tolerated on {@code iat} and {@code exp}, as for the other tokens. */
    static final Duration LOGOUT_TOKEN_SKEW = Duration.ofSeconds(60);

    /** The longest {@code jti} the replay cache stores. */
    static final int MAX_JTI_LENGTH = 255;

    /**
     * Verifies back-channel logout tokens: signature by the provider's JWK set (the one that signs
     * its ID tokens), {@code typ} {@code logout+jwt} or absent, issuer, audience contains the client id,
     * {@code iat} present and not in the future, {@code exp} present and not passed, an
     * {@code events} claim naming the back-channel logout event, a {@code sid} or {@code sub}, a
     * {@code jti}, and no {@code nonce}. Replay is the caller's to check (it needs the store).
     */
    Mono<ReactiveJwtDecoder> logoutTokenDecoder(String id) {
        return endpoints(id)
                .map(e -> logoutDecoders.computeIfAbsent(id, k -> {
                    BffProperties.Provider p = settings(id);
                    String clientId = p.getClientId();
                    OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                            logoutTimestamps(clock),
                            new JwtIssuerValidator(p.getIssuerUri()),
                            jwt -> logoutClaims(jwt, clientId, clock));
                    return decoder(
                            p,
                            e.jwkSetUri(),
                            validator,
                            processor -> processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<
                                    JWKSecurityContext>(new JOSEObjectType("logout+jwt"), null)));
                }));
    }

    private static JwtTimestampValidator logoutTimestamps(Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(LOGOUT_TOKEN_SKEW);
        timestamps.setClock(clock);
        return timestamps;
    }

    static OAuth2TokenValidatorResult logoutClaims(Jwt jwt, String clientId, Clock clock) {
        List<String> aud = jwt.getAudience();
        if (aud == null || !aud.contains(clientId)) return failure("The logout token audience is wrong");
        Instant now = clock.instant();
        Instant iat = jwt.getIssuedAt();
        if (iat == null) return failure("The logout token has no iat");
        if (iat.isAfter(now.plus(LOGOUT_TOKEN_SKEW))) return failure("The logout token iat is in the future");
        if (jwt.getExpiresAt() == null) return failure("The logout token has no exp");
        Object events = jwt.getClaim("events");
        if (!(events instanceof Map<?, ?> map) || !map.containsKey(BACKCHANNEL_LOGOUT_EVENT)) {
            return failure("The logout token does not announce the back-channel logout event");
        }
        if (blank(jwt.getClaimAsString("sid")) && blank(jwt.getClaimAsString("sub"))) {
            return failure("The logout token has neither sid nor sub");
        }
        if (jwt.hasClaim("nonce")) return failure("A logout token must not carry a nonce");
        String jti = jwt.getId();
        if (blank(jti) || jti.length() > MAX_JTI_LENGTH) return failure("The logout token jti is missing or too long");
        return OAuth2TokenValidatorResult.success();
    }

    /**
     * The one place a token decoder is built: verifies against the provider's JWK set, accepting only the
     * provider's {@code jws-algorithms} (default RS256). {@code processorCustomizer} (nullable) adjusts the Nimbus
     * processor, for example the accepted JOSE {@code typ}.
     */
    private static ReactiveJwtDecoder decoder(
            BffProperties.Provider provider,
            String jwkSetUri,
            OAuth2TokenValidator<Jwt> validator,
            Consumer<ConfigurableJWTProcessor<JWKSecurityContext>> processorCustomizer) {
        Set<SignatureAlgorithm> algorithms = parseJwsAlgorithms(provider.getJwsAlgorithms());
        NimbusReactiveJwtDecoder.JwkSetUriReactiveJwtDecoderBuilder builder = NimbusReactiveJwtDecoder.withJwkSetUri(
                        jwkSetUri)
                .jwsAlgorithms(set -> {
                    set.clear();
                    set.addAll(algorithms);
                });
        if (processorCustomizer != null) builder.jwtProcessorCustomizer(processorCustomizer);
        NimbusReactiveJwtDecoder decoder = builder.build();
        decoder.setJwtValidator(validator);
        // The JWK set fetch has no timeout of its own: bound the whole decode, which is where it hides.
        return token -> decoder.decode(token).timeout(JWKS_TIMEOUT);
    }

    /**
     * Parses {@code jws-algorithms} the way {@link SignatureAlgorithm#from} does (case-sensitive, so {@code RS256}).
     * Startup validation rejects anything refused here, so a failure at this point is a programming error.
     */
    static Set<SignatureAlgorithm> parseJwsAlgorithms(List<String> names) {
        Set<SignatureAlgorithm> parsed = new LinkedHashSet<>();
        for (String name : names) {
            SignatureAlgorithm algorithm = name == null ? null : SignatureAlgorithm.from(name);
            if (algorithm == null) throw new IllegalArgumentException("unknown JWS algorithm '" + name + "'");
            parsed.add(algorithm);
        }
        return parsed;
    }

    /** The problem with one {@code jws-algorithms} entry, or null when the entry is usable. */
    static String jwsAlgorithmProblem(String name) {
        if (name == null || name.isBlank()) return "is blank";
        if ("none".equalsIgnoreCase(name)) return "is not allowed: unsigned tokens are never accepted";
        if (name.equals("HS256") || name.equals("HS384") || name.equals("HS512")) {
            return "is not supported: HMAC needs a shared secret, not a JWK set";
        }
        if (SignatureAlgorithm.from(name) == null) {
            return "is not a known JWS algorithm (use the exact name, for example RS256 or ES256)";
        }
        return null;
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    private static String pick(String explicit, Object discovered) {
        return !blank(explicit) ? explicit : discovered instanceof String s && !s.isBlank() ? s : null;
    }

    private static String string(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String emptyToNull(String s) {
        return blank(s) ? null : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
