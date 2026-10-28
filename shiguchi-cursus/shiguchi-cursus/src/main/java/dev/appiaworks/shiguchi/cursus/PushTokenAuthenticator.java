package dev.appiaworks.shiguchi.cursus;

import java.time.Instant;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import reactor.core.publisher.Mono;

/**
 * Turns a bearer token (a WebSocket {@code connect} or {@code refresh} frame, the {@code authorization}
 * metadata of a gRPC call) into the same
 * {@link Authentication} the security chain would have produced from an {@code Authorization}
 * header: the gateway's {@link ReactiveJwtDecoder} and, when the application defines one, its
 * JWT-to-authentication converter. Browsers cannot set headers on a WebSocket handshake, which is
 * what this exists for.
 */
final class PushTokenAuthenticator {

    private final ReactiveJwtDecoder decoder;
    private final Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> converter;

    PushTokenAuthenticator(
            ReactiveJwtDecoder decoder,
            Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> converter) {
        this.decoder = decoder;
        this.converter = converter != null ? converter : new ReactiveJwtAuthenticationConverter();
    }

    boolean available() {
        return decoder != null;
    }

    Mono<Authentication> authenticate(String token) {
        if (decoder == null) {
            return Mono.error(PushException.unauthenticated(
                    "no JWT decoder is configured: set spring.security.oauth2.resourceserver.jwt.*"));
        }
        if (token == null || token.isBlank()) return Mono.error(PushException.unauthenticated("token is required"));
        return decoder.decode(token.trim())
                .onErrorMap(e -> PushException.unauthenticated("token rejected: " + e.getMessage()))
                .flatMap(jwt -> {
                    Mono<? extends AbstractAuthenticationToken> converted = converter.convert(jwt);
                    return converted == null ? Mono.empty() : converted;
                })
                .onErrorMap(
                        AuthenticationException.class,
                        e -> PushException.unauthenticated("token rejected: " + e.getMessage()))
                .cast(Authentication.class);
    }

    /** When the caller's token expires, or null when it carries no expiry (or is not a JWT). */
    static Instant expiry(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        Jwt jwt = principal instanceof Jwt j ? j : auth.getCredentials() instanceof Jwt j2 ? j2 : null;
        return jwt == null ? null : jwt.getExpiresAt();
    }
}
