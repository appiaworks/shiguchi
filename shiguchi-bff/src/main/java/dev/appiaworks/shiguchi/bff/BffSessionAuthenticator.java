package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Turns a request's session cookie into the caller. Asked only for requests without an
 * {@code Authorization} header, at most once per request.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Return an authenticated {@code JwtAuthenticationToken} built from the session's upstream
 *       token and converted by the same converter as a bearer request, so authorities, the
 *       principal name, {@code x-principal} and push topic matching are the same for a user
 *       whichever way they arrive. Its token value is what the gateway sends upstream.</li>
 *   <li>Return an empty {@code Mono} when there is no valid session: the request is then
 *       anonymous. Any other error fails the request.</li>
 *   <li>Fail with {@link BffSessionUnavailableException} when a session exists but cannot be
 *       judged right now (its access token has expired and the issuer could not be reached to
 *       renew it): the gateway answers {@code 503} with {@code Retry-After: 1}, also on open
 *       routes. Do not return empty for that: it would sign the user out, or send the request
 *       on anonymously. The default authenticator refreshes tokens that expire within
 *       {@code shiguchi.bff.refresh-skew}.</li>
 *   <li>Never register a {@code ReactiveJwtDecoder} bean to build the token: Boot's issuer decoder
 *       backs off when one exists, and every bearer caller would then be validated by it.</li>
 *   <li>Never block.</li>
 * </ul>
 *
 * <p>With {@code shiguchi.bff.enabled=true} and no bean of this type, nobody is
 * authenticated by cookie.
 */
@FunctionalInterface
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface BffSessionAuthenticator {

    /**
     * The caller this request's session belongs to.
     *
     * @param exchange a request carrying no {@code Authorization} header
     * @return the caller, or empty without a valid session
     */
    Mono<Authentication> authenticate(ServerWebExchange exchange);
}
