package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName("BffUpstreamCredentialSource: per-route token exchange")
class BffUpstreamCredentialSourceExchangeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String SERVICE = "acme.orders.v1.OrderService";

    private static StubIssuer issuer;

    private final Clock clock = Clock.systemUTC();
    private final ServerHttpRequest request =
            MockServerHttpRequest.get("/orders").build();
    private MemoryBffSessionStore store;
    private BffProperties properties;
    private BffUpstreamCredentialSource source;
    private BffSession session;

    @BeforeAll
    static void startIssuer() {
        issuer = StubIssuer.start();
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @BeforeEach
    void setUp() {
        issuer.reset();
        store = new MemoryBffSessionStore(clock);
        properties = BffTestFixtures.properties();
        BffProperties.Provider idp = properties.getProviders().get("idp");
        idp.setIssuerUri(issuer.issuer());
        idp.setAuthorizationUri(null);
        idp.setTokenUri(null);
        idp.setJwkSetUri(null);
        idp.setEndSessionUri(null);
        idp.setClientId(issuer.clientId());
        idp.setClientSecret("secret");
        idp.setUpstreamTokenMode(BffProperties.UpstreamTokenMode.EXCHANGE);
        BffProperties.ExchangeTarget target = new BffProperties.ExchangeTarget();
        target.setAudience("orders-api");
        idp.getExchange().getTargets().put(SERVICE, target);
        BffTokenExchanger exchanger =
                new BffTokenExchanger(new BffProviders(properties, WebClient.builder()), properties);
        source = new BffUpstreamCredentialSource(store, null, clock, exchanger);
        Instant now = clock.instant();
        session = BffTestFixtures.session("cookie", now.minusSeconds(60), now, now.plusSeconds(600));
        store.save(session, Duration.ofHours(1)).block();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private JwtAuthenticationToken caller() {
        Instant now = clock.instant();
        Jwt jwt = Jwt.withTokenValue(session.accessToken())
                .header("alg", "none")
                .subject("alice")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(600))
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of(), "alice");
        auth.setDetails(new CookieAuthenticated(null, session.key(), session.application()));
        return auth;
    }

    private static RouteDefinition route(String service) {
        return RouteDefinition.builder(service, "Get", MethodDescriptor.MethodType.UNARY, "localhost:1")
                .build();
    }

    private static Status.Code code(Throwable t) {
        return ((StatusRuntimeException) t).getStatus().getCode();
    }

    @Test
    @DisplayName("GIVEN an EXCHANGE provider WHEN asked for a mapped route SHOULD send the exchanged token")
    void given_exchange_provider_when_mapped_route_should_send_exchanged_token() {
        String value = source.authorization(request, caller(), route(SERVICE)).block(TIMEOUT);

        assertThat(value).startsWith("Bearer ").doesNotContain(session.accessToken());
        assertThat(issuer.exchangeCalls()).isEqualTo(1);
        assertThat(issuer.lastExchangeRequestBody()).contains("audience=orders-api");
    }

    @Test
    @DisplayName("GIVEN a RELAY provider WHEN asked with a route SHOULD send the held token and not exchange")
    void given_relay_provider_when_asked_with_route_should_relay() {
        properties.getProviders().get("idp").setUpstreamTokenMode(BffProperties.UpstreamTokenMode.RELAY);

        String value = source.authorization(request, caller(), route(SERVICE)).block(TIMEOUT);

        assertThat(value).isEqualTo("Bearer " + session.accessToken());
        assertThat(issuer.exchangeCalls()).isZero();
    }

    @Test
    @DisplayName("GIVEN no route and no default target WHEN the provider exchanges SHOULD be PERMISSION_DENIED")
    void given_two_arg_call_when_exchange_provider_should_be_permission_denied() {
        assertThatThrownBy(() -> source.authorization(request, caller()).block(TIMEOUT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    @DisplayName("GIVEN a route with no mapping WHEN asked SHOULD be PERMISSION_DENIED and not relay")
    void given_unmapped_route_when_asked_should_be_permission_denied() {
        assertThatThrownBy(() -> source.authorization(request, caller(), route("other.Service"))
                        .block(TIMEOUT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThat(issuer.exchangeCalls()).isZero();
    }

    @Test
    @DisplayName("GIVEN the issuer refuses the exchange WHEN asked SHOULD be PERMISSION_DENIED without any token")
    void given_refused_exchange_when_asked_should_be_permission_denied() {
        issuer.exchangeStatus(400).exchangeError("invalid_target");

        assertThatThrownBy(() ->
                        source.authorization(request, caller(), route(SERVICE)).block(TIMEOUT))
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(String.valueOf(e.getMessage())).doesNotContain(session.accessToken());
                });
    }

    @Test
    @DisplayName("GIVEN the issuer is down WHEN asked SHOULD be UNAVAILABLE")
    void given_issuer_error_when_asked_should_be_unavailable() {
        issuer.exchangeStatus(500).exchangeError("server_error");

        assertThatThrownBy(() ->
                        source.authorization(request, caller(), route(SERVICE)).block(TIMEOUT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(Status.Code.UNAVAILABLE));
    }

    @Test
    @DisplayName("GIVEN the session ended WHEN asked SHOULD be UNAUTHENTICATED")
    void given_ended_session_when_asked_should_be_unauthenticated() {
        store.delete(session.key()).block();

        assertThatThrownBy(() ->
                        source.authorization(request, caller(), route(SERVICE)).block(TIMEOUT))
                .satisfies(e -> assertThat(code(e)).isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
