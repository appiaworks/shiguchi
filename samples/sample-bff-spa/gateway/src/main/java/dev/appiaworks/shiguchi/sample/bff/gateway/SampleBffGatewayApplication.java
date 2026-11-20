package dev.appiaworks.shiguchi.sample.bff.gateway;

import dev.appiaworks.shiguchi.security.GatewaySecurityCustomizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.web.server.ServerHttpSecurity;

/**
 * A gateway a single-page app signs in through, with no token in the browser: the `shiguchi-bff` module
 * runs the OpenID Connect code flow against the sample issuer and keeps the tokens server-side
 * in the session; the page holds only the session cookie. REST, gRPC-Web and the push socket all
 * authenticate with that cookie. The page itself is served from this gateway (same origin),
 * from {@code src/main/resources/static}, where {@code npm run build} in {@code ../web} puts it.
 */
@SpringBootApplication
public class SampleBffGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleBffGatewayApplication.class, args);
    }

    /**
     * Under {@code security.mode: AUTHENTICATED} every path the gateway serves needs a caller,
     * including the static page, which has to load before anybody can sign in. The sign-in
     * endpoints are opened by the `shiguchi-bff` module itself; the page is opened here.
     */
    @Bean
    GatewaySecurityCustomizer publicPage() {
        return new GatewaySecurityCustomizer() {
            @Override
            public void authorize(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
                exchanges.pathMatchers("/", "/index.html", "/favicon.ico", "/assets/**").permitAll();
            }
        };
    }
}
