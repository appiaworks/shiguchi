package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Authenticates a GraphQL WebSocket from the {@code connection_init} payload. A browser cannot set
 * headers on a WebSocket, so a page such as the console's GraphiQL sends its credential as
 * {@code {"Authorization": "Bearer <jwt>"}} in that message. The token goes through the same
 * {@link ReactiveJwtDecoder} and JWT-to-authentication converter as the HTTP security chain, and every
 * operation sent on the socket then runs as that caller (the subscription's scope checks, the
 * backend credential, the token-expiry binding of {@link CookieSessionBindingInterceptor}).
 *
 * <p>A token the decoder rejects closes the connection. A payload without an {@code Authorization}
 * entry changes nothing: the socket keeps whoever the handshake authenticated (a cookie or
 * header caller) or stays anonymous. Without a {@code ReactiveJwtDecoder} the payload is ignored.
 * Only bearer tokens are read from the payload.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class ConnectionInitAuthInterceptor implements WebSocketGraphQlInterceptor {

    static final String ATTRIBUTE = ConnectionInitAuthInterceptor.class.getName() + ".AUTHENTICATION";
    private static final String BEARER = "bearer ";

    private final ReactiveJwtDecoder decoder;
    private final Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> converter;

    public ConnectionInitAuthInterceptor(
            ObjectProvider<ReactiveJwtDecoder> decoders,
            ObjectProvider<ReactiveJwtAuthenticationConverter> reactiveConverters,
            ObjectProvider<Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>>> converters) {
        this.decoder = decoders.getIfAvailable();
        // The HTTP chain resolves the same converter, so both name the caller the same way.
        this.converter = JwtPrincipalConverter.resolve(reactiveConverters, converters);
    }

    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo sessionInfo, Map<String, Object> payload) {
        String token = bearerToken(payload);
        if (token == null || decoder == null) return Mono.empty();
        return decoder.decode(token)
                .flatMap(jwt -> {
                    Mono<? extends AbstractAuthenticationToken> converted = converter.convert(jwt);
                    return converted == null
                            ? Mono.<AbstractAuthenticationToken>empty()
                            : converted.cast(AbstractAuthenticationToken.class);
                })
                .switchIfEmpty(Mono.error(new InvalidBearerTokenException("The token was not accepted")))
                .onErrorMap(
                        e -> !(e instanceof InvalidBearerTokenException),
                        e -> new InvalidBearerTokenException("The token was not accepted", e))
                .doOnNext(auth -> sessionInfo.getAttributes().put(ATTRIBUTE, auth))
                .then(Mono.empty());
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (request instanceof WebSocketGraphQlRequest socket
                && socket.getSessionInfo().getAttributes().get(ATTRIBUTE) instanceof Authentication auth) {
            return chain.next(request).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }
        return chain.next(request);
    }

    private static String bearerToken(Map<String, Object> payload) {
        if (payload == null) return null;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if ("authorization".equalsIgnoreCase(e.getKey()) && e.getValue() instanceof String value) {
                String v = value.trim();
                if (v.length() > BEARER.length() && v.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                    return v.substring(BEARER.length()).trim();
                }
            }
        }
        return null;
    }
}
