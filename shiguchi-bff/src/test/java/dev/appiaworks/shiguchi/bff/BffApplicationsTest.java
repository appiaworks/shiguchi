package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

@DisplayName("BffApplications: which configured application a request belongs to")
class BffApplicationsTest {

    private static BffProperties.Application application(String... origins) {
        BffProperties.Application app = new BffProperties.Application();
        app.setOrigins(List.of(origins));
        app.setProviders(List.of("idp"));
        return app;
    }

    private static BffApplications applications(String... singleOrMany) {
        // Pairs of (id, origin) so tests read as "eve at https://eve.example.com".
        BffProperties properties = new BffProperties();
        for (int i = 0; i < singleOrMany.length; i += 2) {
            properties.getApplications().put(singleOrMany[i], application(singleOrMany[i + 1]));
        }
        return new BffApplications(properties);
    }

    private static String resolvedId(BffApplications applications, ServerWebExchange exchange) {
        return applications.resolve(exchange).map(BffApplications.Resolved::id).orElse(null);
    }

    private static ServerWebExchange exchangeWithOrigin(String origin) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("https://gateway.example.com/pkg.Svc/Get")
                .header(HttpHeaders.ORIGIN, origin)
                .build());
    }

    @Test
    @DisplayName("GIVEN one application WHEN a request with a foreign Origin arrives SHOULD resolve to it anyway")
    void given_oneApplication_when_foreignOriginArrives_should_resolveToIt() {
        BffApplications applications = applications("eve", "https://eve.example.com");

        assertThat(resolvedId(applications, exchangeWithOrigin("https://attacker.example.net")))
                .isEqualTo("eve");
    }

    @Test
    @DisplayName("GIVEN several applications WHEN a request's Origin matches one SHOULD resolve to that one")
    void given_severalApplications_when_originMatches_should_resolveByOrigin() {
        BffApplications applications = applications(
                "eve", "https://eve.example.com",
                "fin", "https://fin.example.com:8443");

        assertThat(resolvedId(applications, exchangeWithOrigin("https://fin.example.com:8443")))
                .isEqualTo("fin");
    }

    @Test
    @DisplayName(
            "GIVEN several applications WHEN Origin differs in case and spells the default port SHOULD still match")
    void given_severalApplications_when_originDiffersInCaseAndDefaultPort_should_stillMatch() {
        BffApplications applications = applications(
                "eve", "https://eve.example.com",
                "fin", "https://fin.example.com:8443");

        assertThat(resolvedId(applications, exchangeWithOrigin("HTTPS://EVE.Example.com:443")))
                .isEqualTo("eve");
    }

    @Test
    @DisplayName("GIVEN several applications WHEN no Origin header arrives SHOULD resolve by the request's own origin")
    void given_severalApplications_when_noOriginHeader_should_resolveByRequestUri() {
        BffApplications applications = applications(
                "eve", "https://eve.example.com",
                "fin", "https://fin.example.com:8443");
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://fin.example.com:8443/x").build());

        assertThat(resolvedId(applications, exchange)).isEqualTo("fin");
    }

    @Test
    @DisplayName("GIVEN several applications WHEN the Origin matches none SHOULD resolve to nothing")
    void given_severalApplications_when_originMatchesNone_should_resolveToEmpty() {
        BffApplications applications = applications(
                "eve", "https://eve.example.com",
                "fin", "https://fin.example.com:8443");

        assertThat(applications.resolve(exchangeWithOrigin("https://other.example.com")))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN several applications WHEN the Origin header is the literal null SHOULD resolve to nothing")
    void given_severalApplications_when_originIsNull_should_resolveToEmpty() {
        BffApplications applications = applications(
                "eve", "https://eve.example.com",
                "fin", "https://fin.example.com:8443");

        assertThat(applications.resolve(exchangeWithOrigin("null"))).isEmpty();
    }
}
