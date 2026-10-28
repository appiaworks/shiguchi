package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import reactor.core.publisher.Mono;

/**
 * The JWT-to-authentication converter of every gateway path that reads a bearer token: the HTTP
 * security chain, and push's WebSocket {@code connect} frame and gRPC calls. One lookup for all of
 * them, so they cannot name the same caller differently.
 *
 * <p>{@link #resolve} picks, in order: a {@link ReactiveJwtAuthenticationConverter} bean (yours,
 * or the one Spring Boot registers when
 * {@code spring.security.oauth2.resourceserver.jwt.principal-claim-name} or an authorities
 * property is set), then a single bean of any other {@code Converter<Jwt, Mono<…>>} type, then
 * Spring's default converter (the principal is the {@code sub} claim).
 *
 * <p>A token whose authentication has no name (the principal claim is missing from it) is
 * rejected as an invalid bearer token, so the caller gets a 401. Spring's own converter would
 * accept it with a null name, which every consumer of the name ({@code x-principal}, the rate
 * limit key, push sessions) would then fail on.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class JwtPrincipalConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private static final Logger log = LoggerFactory.getLogger(JwtPrincipalConverter.class);

    private final Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> delegate;

    public JwtPrincipalConverter(Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> delegate) {
        this.delegate = delegate != null ? delegate : new ReactiveJwtAuthenticationConverter();
    }

    /** The converter the application's beans select (see the class description). */
    public static JwtPrincipalConverter resolve(
            ObjectProvider<ReactiveJwtAuthenticationConverter> reactive,
            ObjectProvider<Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>>> any) {
        Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> chosen = reactive.getIfUnique();
        if (chosen != null && any.stream().count() > 1) {
            log.warn("A ReactiveJwtAuthenticationConverter bean (yours, or Spring Boot's for"
                    + " spring.security.oauth2.resourceserver.jwt.principal-claim-name) takes precedence over the"
                    + " other JWT authentication converter beans, which are ignored");
        }
        if (chosen == null) chosen = any.getIfUnique();
        if (chosen == null && any.stream().findAny().isPresent()) {
            log.warn("Several JWT authentication converter beans and none is a single"
                    + " ReactiveJwtAuthenticationConverter: using Spring's default converter (principal = sub)");
        }
        return new JwtPrincipalConverter(chosen);
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        Mono<? extends AbstractAuthenticationToken> converted = delegate.convert(jwt);
        if (converted == null) return Mono.empty();
        return converted.cast(AbstractAuthenticationToken.class).flatMap(auth -> {
            String name = auth.getName();
            return name == null || name.isBlank()
                    ? Mono.error(new InvalidBearerTokenException("The token carries no principal name"))
                    : Mono.just(auth);
        });
    }
}
