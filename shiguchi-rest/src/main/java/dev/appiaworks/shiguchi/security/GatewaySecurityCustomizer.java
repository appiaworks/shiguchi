package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.List;
import org.springframework.security.config.web.server.ServerHttpSecurity;

/**
 * Extends the gateway's security chain without replacing it. Every bean of this type is applied,
 * in {@code @Order}, to the chain {@link GatewaySecurityConfig} builds. With no bean the chain is
 * exactly the one the gateway builds on its own.
 *
 * <p>A customizer may open its own paths (a sign-in endpoint, say), add filters and swap the
 * security-context repository. It must not weaken the gateway's surface rules: the always-gated
 * actuator writes are registered before {@link #authorize}, so it cannot reopen them, but nothing
 * stops a customizer from permitting a path a surface would gate. That is the customizer's
 * responsibility.
 *
 * <p>Every method has a no-op default, so the interface can grow without breaking implementations.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface GatewaySecurityCustomizer {

    /**
     * Adds authorization rules. Called after the gateway's always-gated actuator rules and before
     * the surface rules and {@code anyExchange()}, so a matcher registered here wins over the
     * surface it falls in. Spring Security refuses new matchers once {@code anyExchange()} is
     * registered, which is why this hook exists apart from {@link #customize}.
     *
     * @param exchanges the chain's authorization rules
     */
    default void authorize(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {}

    /**
     * Customizes the chain after the gateway's own posture and rules, immediately before it is
     * built: filters, the security-context repository, exception handling.
     *
     * @param http the chain being built
     */
    default void customize(ServerHttpSecurity http) {}

    /**
     * Whether this customizer authenticates callers by itself (a session cookie, say). The gateway
     * refuses to start when a surface requires authentication and no JWT resource server is
     * configured; one customizer returning {@code true} lifts that check. Bearer requests are
     * then refused with 401 unless a JWT decoder is configured too.
     *
     * @return {@code true} when this customizer authenticates callers
     */
    default boolean authenticates() {
        return false;
    }

    /**
     * Path patterns the chain must cover when {@code shiguchi.security.open-rest-bypasses-chain}
     * keeps open transcoded routes out of it. The filters added in {@link #customize} only run on
     * paths the chain covers.
     *
     * @return path patterns, empty by default
     */
    default List<String> chainPaths() {
        return List.of();
    }
}
