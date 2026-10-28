package dev.appiaworks.shiguchi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.SecurityConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@DisplayName("GatewaySecurityConfig with GatewaySecurityCustomizer beans")
class GatewaySecurityCustomizerTest {

    /** Records the order customizers run in. */
    private final List<String> calls = new ArrayList<>();

    private static GatewaySecurityConfig configWith(GatewaySecurityCustomizer... customizers) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        // As in an application context, so @Order on a customizer counts.
        factory.setDependencyComparator(AnnotationAwareOrderComparator.INSTANCE);
        for (int i = 0; i < customizers.length; i++) factory.registerSingleton("customizer" + i, customizers[i]);
        GatewaySecurityConfig config = new GatewaySecurityConfig();
        ObjectProvider<GatewaySecurityCustomizer> provider = factory.getBeanProvider(GatewaySecurityCustomizer.class);
        config.setCustomizers(provider);
        return config;
    }

    private static ObjectProvider<ReactiveJwtDecoder> decoders(ReactiveJwtDecoder... decoders) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < decoders.length; i++) factory.registerSingleton("decoder" + i, decoders[i]);
        return factory.getBeanProvider(ReactiveJwtDecoder.class);
    }

    private static SecurityWebFilterChain chain(GatewaySecurityConfig config, GatewayProperties props) {
        return config.securityFilterChain(ServerHttpSecurity.http(), props, decoders(), "", "", "/actuator");
    }

    private static WebTestClient client(SecurityWebFilterChain chain) {
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    return exchange.getResponse().setComplete();
                })
                .webFilter(new WebFilterChainProxy(chain))
                .build();
    }

    private static GatewayProperties authenticated() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setMode(SecurityConfig.Mode.AUTHENTICATED);
        return props;
    }

    private static List<String> filterNames(SecurityWebFilterChain chain) {
        return chain.getWebFilters()
                .map(f -> f.getClass().getName())
                .collectList()
                .block();
    }

    private static List<String> fixture(String name) throws IOException {
        try (InputStream in = GatewaySecurityCustomizerTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .toList();
        }
    }

    /** Opens {@code /signin/**} and authenticates by itself, like a cookie sign-in would. */
    private static final class SignIn implements GatewaySecurityCustomizer {
        @Override
        public void authorize(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
            exchanges.pathMatchers("/signin/**").permitAll();
        }

        @Override
        public boolean authenticates() {
            return true;
        }

        @Override
        public List<String> chainPaths() {
            return List.of("/signin/**");
        }
    }

    @Order(2)
    private final class Second implements GatewaySecurityCustomizer {
        @Override
        public void customize(ServerHttpSecurity http) {
            calls.add("second");
        }
    }

    @Order(1)
    private final class First implements GatewaySecurityCustomizer {
        @Override
        public void customize(ServerHttpSecurity http) {
            calls.add("first");
        }
    }

    @Test
    @DisplayName("GIVEN no customizer bean WHEN building the default chain SHOULD hold the same filters as before")
    void given_noCustomizer_when_buildingChain_should_keepTheFilterList() throws IOException {
        assertThat(filterNames(chain(configWith(), new GatewayProperties())))
                .containsExactlyElementsOf(fixture("default-chain-filters.txt"));
    }

    @Test
    @DisplayName("GIVEN a customizer adding a filter WHEN building the chain SHOULD include that filter")
    void given_customizerAddingFilter_when_buildingChain_should_includeIt() {
        WebFilter marker = (exchange, next) -> next.filter(exchange);
        GatewaySecurityCustomizer addsFilter = new GatewaySecurityCustomizer() {
            @Override
            public void customize(ServerHttpSecurity http) {
                http.addFilterAt(marker, org.springframework.security.config.web.server.SecurityWebFiltersOrder.FIRST);
            }
        };

        List<WebFilter> filters = chain(configWith(addsFilter), new GatewayProperties())
                .getWebFilters()
                .collectList()
                .block();

        assertThat(filters).contains(marker);
    }

    @Test
    @DisplayName("GIVEN two customizers WHEN building the chain SHOULD apply them in @Order")
    void given_twoCustomizers_when_buildingChain_should_applyInOrder() {
        chain(configWith(new Second(), new First()), new GatewayProperties());

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    @DisplayName(
            "GIVEN mode AUTHENTICATED and a customizer permitting a path WHEN calling without credentials SHOULD reach that path and get 401 elsewhere")
    void given_customizerPermitsPath_when_calledAnonymously_should_reachOnlyThatPath() {
        ReactiveJwtDecoder decoder = token -> Mono.error(new BadJwtException("bad"));
        SecurityWebFilterChain chain = configWith(new SignIn())
                .securityFilterChain(
                        ServerHttpSecurity.http(), authenticated(), decoders(decoder), "", "", "/actuator");
        WebTestClient client = client(chain);

        client.get().uri("/signin/start").exchange().expectStatus().isOk();
        client.get().uri("/pkg.Svc/Get").exchange().expectStatus().isUnauthorized();
        client.get().uri("/graphql").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName(
            "GIVEN a customizer permitting every actuator path WHEN calling gateway-refresh anonymously SHOULD still refuse it")
    void given_customizerPermitsActuator_when_callingRefresh_should_stillRefuse() {
        GatewaySecurityCustomizer opensActuator = new GatewaySecurityCustomizer() {
            @Override
            public void authorize(ServerHttpSecurity.AuthorizeExchangeSpec exchanges) {
                exchanges.pathMatchers("/actuator/**").permitAll();
            }
        };
        ReactiveJwtDecoder decoder = token -> Mono.error(new BadJwtException("bad"));
        WebTestClient client = client(configWith(opensActuator)
                .securityFilterChain(
                        ServerHttpSecurity.http(), new GatewayProperties(), decoders(decoder), "", "", "/actuator"));

        client.post().uri("/actuator/gateway-refresh").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("GIVEN auth required and no JWT decoder WHEN no customizer authenticates SHOULD refuse to start")
    void given_noDecoder_when_noCustomizerAuthenticates_should_failFast() {
        GatewaySecurityCustomizer passive = new GatewaySecurityCustomizer() {};

        assertThatThrownBy(() -> chain(configWith(passive), authenticated()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an OAuth2 JWT decoder");
    }

    @Test
    @DisplayName(
            "GIVEN auth required and no JWT decoder WHEN a customizer authenticates SHOULD start and answer a bearer request with a bare 401")
    void given_noDecoder_when_customizerAuthenticates_should_startAndRefuseBearer() {
        WebTestClient client = client(chain(configWith(new SignIn()), authenticated()));

        client.get()
                .uri("/pkg.Svc/Get")
                .header(HttpHeaders.AUTHORIZATION, "Bearer abc")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
        client.get().uri("/signin/start").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName(
            "GIVEN open REST routes bypassing the chain WHEN a customizer names chain paths SHOULD keep those paths in the chain")
    void given_restBypass_when_customizerNamesPaths_should_keepThemInTheChain() {
        GatewayProperties props = new GatewayProperties();
        props.getSecurity().setOpenRestBypassesChain(true);
        GatewaySecurityConfig config = configWith(new SignIn());

        SecurityWebFilterChain chain = chain(config, props);

        assertThat(chain.matches(MockServerWebExchange.from(
                                MockServerHttpRequest.get("/signin/start").build()))
                        .block())
                .isTrue();
        assertThat(chain.matches(MockServerWebExchange.from(
                                MockServerHttpRequest.post("/pkg.Svc/Get").build()))
                        .block())
                .isFalse();
    }
}
