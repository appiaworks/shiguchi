package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.security.GatewaySecurityCustomizer;
import java.util.List;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;

/**
 * Installs {@link BffSecurityContextRepository} in the gateway's chain, so a request without an
 * {@code Authorization} header may be authenticated by its session cookie, opens the sign-in
 * endpoints ({@code login/**}, {@code callback/**}, {@code providers}, {@code session},
 * {@code logout} and {@code backchannel-logout/**} under the BFF path) to anonymous callers, and puts everything under the BFF path
 * in the chain even when open REST routes bypass it. Adds the {@link BffOriginFilter} right after
 * authentication, a {@link BffActuatorGuardFilter} that refuses cookie callers on actuator endpoints, and a filter first in the chain that answers {@code 503} when a session's token
 * could not be refreshed ({@link BffSessionUnavailableException}).
 */
final class BffSecurityCustomizer implements GatewaySecurityCustomizer {

    private final BffSecurityContextRepository repository;
    private final boolean authenticates;
    private final String path;
    private final BffOriginFilter originFilter;
    private final BffActuatorGuardFilter actuatorGuard;

    BffSecurityCustomizer(
            BffSecurityContextRepository repository, boolean authenticates, String path, BffOriginFilter originFilter) {
        this(repository, authenticates, path, originFilter, null);
    }

    BffSecurityCustomizer(
            BffSecurityContextRepository repository,
            boolean authenticates,
            String path,
            BffOriginFilter originFilter,
            BffActuatorGuardFilter actuatorGuard) {
        this.repository = repository;
        this.authenticates = authenticates;
        this.path = path;
        this.originFilter = originFilter;
        this.actuatorGuard = actuatorGuard;
    }

    @Override
    public void authorize(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
        exchanges
                .pathMatchers(
                        path + "/login/**",
                        path + "/callback/**",
                        path + "/providers",
                        path + "/session",
                        path + "/logout",
                        path + "/backchannel-logout/**")
                .permitAll();
    }

    @Override
    public List<String> chainPaths() {
        return List.of(path + "/**");
    }

    @Override
    public void customize(ServerHttpSecurity http) {
        http.securityContextRepository(repository);
        http.addFilterAt(new BffUnavailableFilter(), SecurityWebFiltersOrder.FIRST);
        if (actuatorGuard != null) http.addFilterAfter(actuatorGuard, SecurityWebFiltersOrder.AUTHENTICATION);
        if (originFilter != null) http.addFilterAfter(originFilter, SecurityWebFiltersOrder.AUTHENTICATION);
    }

    /** Only with a session authenticator: without one nobody can sign in, and the usual startup check stays. */
    @Override
    public boolean authenticates() {
        return authenticates;
    }
}
