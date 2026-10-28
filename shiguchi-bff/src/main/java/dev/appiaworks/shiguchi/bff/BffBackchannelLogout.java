package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * {@code POST <bff path>/backchannel-logout/{provider}}: OpenID Connect Back-Channel Logout 1.0. The
 * issuer posts a form with a {@code logout_token}; when it verifies (see
 * {@link BffProviders#logoutTokenDecoder}) and its {@code jti} is new, the sessions it names end:
 * those with its {@code sid} at that provider, or, with only a {@code sub}, every session of that
 * subject at that provider. The answer is {@code 200} with {@code Cache-Control: no-store} (also when
 * the token names no session), {@code 400 {"error":"invalid_request"}} for a token that is not
 * acceptable, and {@code 503} when the provider's keys could not be fetched or the store failed, so
 * the issuer retries. The endpoint is not cookie-authenticated, hence outside the origin check.
 *
 * <p>The {@code jti} is recorded before the sessions are deleted (a replayed token must not delete
 * sessions created since) and forgotten again if the deletion fails, so the retry of the same token
 * is not refused as a replay.
 */
final class BffBackchannelLogout {

    private static final Logger log = LoggerFactory.getLogger(BffBackchannelLogout.class);

    private static final Duration MIN_JTI_TTL = Duration.ofSeconds(1);
    private static final Duration MAX_JTI_TTL = Duration.ofDays(1);

    private final BffProviders providers;
    private final BffSessionStore store;
    private final Clock clock;
    private final BffLogoutMeter meter;
    private final String path;

    BffBackchannelLogout(
            BffProperties properties,
            BffProviders providers,
            BffSessionStore store,
            Clock clock,
            BffLogoutMeter meter) {
        this.providers = providers;
        this.store = store;
        this.clock = clock;
        this.meter = meter;
        this.path = properties.getPath();
    }

    RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route()
                .POST(path + "/backchannel-logout/{provider}", this::handle)
                .build();
    }

    Mono<ServerResponse> handle(ServerRequest request) {
        String provider = request.pathVariable("provider");
        if (providers.settings(provider) == null) return invalid();
        return request.formData().flatMap(form -> {
            String token = form.getFirst("logout_token");
            if (token == null || token.isBlank()) return invalid();
            return providers
                    .logoutTokenDecoder(provider)
                    .flatMap(decoder -> decoder.decode(token))
                    .flatMap(jwt -> end(provider, jwt))
                    .onErrorResume(BadJwtException.class, e -> {
                        log.warn("Refused a logout token for provider '{}': {}", provider, e.getMessage());
                        return invalid();
                    })
                    .onErrorResume(UnsupportedOperationException.class, e -> {
                        log.warn("Back-channel logout needs a session store with the lifecycle methods: {}", e);
                        return status(HttpStatus.NOT_IMPLEMENTED, "unsupported");
                    })
                    .onErrorResume(e -> {
                        log.warn("Back-channel logout for provider '{}' failed, the issuer may retry: {}", provider, e);
                        return status(HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable");
                    });
        });
    }

    private Mono<ServerResponse> end(String provider, Jwt jwt) {
        String sid = emptyToNull(jwt.getClaimAsString("sid"));
        String sub = emptyToNull(jwt.getClaimAsString("sub"));
        String jti = jwt.getId();
        Duration ttl = Duration.between(clock.instant(), jwt.getExpiresAt()).plus(BffProviders.LOGOUT_TOKEN_SKEW);
        if (ttl.compareTo(MIN_JTI_TTL) < 0) ttl = MIN_JTI_TTL;
        if (ttl.compareTo(MAX_JTI_TTL) > 0) ttl = MAX_JTI_TTL;
        return store.markJtiSeen(provider, jti, ttl).flatMap(first -> {
            if (!first) {
                log.warn("Refused a replayed logout token for provider '{}'", provider);
                return invalid();
            }
            Mono<List<String>> deletion =
                    sid != null ? store.deleteBySid(provider, sid) : store.deleteBySubject(provider, sub);
            return deletion.doOnNext(keys -> meter.count("backchannel", keys.size()))
                    .flatMap(keys -> {
                        log.info(
                                "Back-channel logout from provider '{}' ended {} session(s) ({})",
                                provider,
                                keys.size(),
                                sid != null ? "by sid" : "by subject");
                        return ServerResponse.ok()
                                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                .build();
                    })
                    .onErrorResume(e -> store.forgetJti(provider, jti)
                            .onErrorResume(ignored -> Mono.empty())
                            .then(Mono.error(e)));
        });
    }

    private static Mono<ServerResponse> invalid() {
        return status(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    private static Mono<ServerResponse> status(HttpStatus status, String code) {
        return ServerResponse.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("error", code));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
