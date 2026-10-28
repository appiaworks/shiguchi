package dev.appiaworks.shiguchi.sample.bff.issuer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * The two filter chains an authorization server needs: the protocol endpoints (authorize, token,
 * JWKS, discovery, userinfo) with OpenID Connect on, which send a browser that is not signed in to
 * the form login; and everything else behind that form login.
 *
 * <p>Boot's auto-configuration registers the clients and the user from {@code application.yml},
 * but with a chain of your own present it no longer adds a login page, so this class does.
 */
@Configuration(proxyBeanMethods = false)
class IssuerSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer server = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(server.getEndpointsMatcher())
                .with(server, s -> s.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // userinfo accepts the access token it issued
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain login(HttpSecurity http) throws Exception {
        // Requests the browser makes on its own (Chrome asks every origin for the DevTools file) must
        // not be saved as the page to return to after login: they would replace the saved authorize
        // request, and the browser would land on a 404 instead of going back to the gateway.
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/.well-known/appspecific/**", "/favicon.ico", "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
