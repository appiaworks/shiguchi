package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Holds the gateway's {@link JwtPrincipalConverter} for the module. A separate type on purpose:
 * {@code JwtPrincipalConverter} is itself a {@code Converter<Jwt, Mono<...>>}, so as a bean it
 * would be found by the very lookup that selects it.
 */
final class BffPrincipals {

    private final JwtPrincipalConverter converter;

    BffPrincipals(JwtPrincipalConverter converter) {
        this.converter = converter;
    }

    Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        return converter.convert(jwt);
    }
}
