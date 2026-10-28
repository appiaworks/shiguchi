package dev.appiaworks.shiguchi.sample.secured;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Both halves of the sample, asserted separately: the gateway refuses unauthenticated callers, and
 * it can still reach a backend that demands a client certificate.
 *
 * <p>The second is the one worth testing deliberately. A transcoded call succeeding over mTLS
 * proves the whole chain — the channel negotiated TLS, the backend accepted the gateway's
 * certificate, and the gateway accepted the backend's — and none of that is visible from the
 * response body, so without this test a broken {@code tls} block would look like any other
 * connection failure.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Setting one indexed property replaces the whole services list, so the entry has to be
        // restated in full — including the tls block, which is the point of the sample.
        properties = {
            "sample.backend-port=19443",
            "shiguchi.services[0].name=catalog",
            "shiguchi.services[0].mode=DYNAMIC",
            "shiguchi.services[0].host=localhost",
            "shiguchi.services[0].port=19443",
            "shiguchi.services[0].tls.trust-cert-collection=${sample.certs.ca}",
            "shiguchi.services[0].tls.cert-chain=${sample.certs.client-cert}",
            "shiguchi.services[0].tls.private-key=${sample.certs.client-key}"
        })
@DisplayName("Secured gateway sample")
class SampleSecuredGatewayTest {

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static String token() {
        return LocalJwtConfiguration.mintToken("test-user", List.of("catalog.read"));
    }

    @Test
    @DisplayName("GIVEN no bearer token WHEN a route is called SHOULD refuse it, because the gateway is AUTHENTICATED")
    void given_noToken_when_calling_should_refuse() {
        client().get().uri("/v1/products/A100").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("GIVEN a token the gateway cannot verify WHEN a route is called SHOULD still refuse it")
    void given_badToken_when_calling_should_refuse() {
        client().get()
                .uri("/v1/products/A100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("GIVEN a valid token WHEN a route is called SHOULD reach the backend over mutual TLS and transcode the reply")
    void given_validToken_when_calling_should_reachTheBackendOverMtls() {
        client().get()
                .uri("/v1/products/A100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo("A100")
                .jsonPath("$.name")
                .isEqualTo("Anvil, 50 kg");
    }

    @Test
    @DisplayName("GIVEN the backend demanded a client certificate WHEN the schema was discovered SHOULD have loaded over the same mutual TLS channel")
    void given_mtlsBackend_when_discovering_should_haveLoadedTheSchema() {
        // Reflection runs over the same channel as the calls, so a loaded schema is independent
        // evidence that the handshake worked — before any RPC was made.
        //
        // The token is needed here too: security.mode AUTHENTICATED covers the actuator surface
        // as well, which is the correct default for an endpoint that lists your internal routes.
        client().get()
                .uri("/actuator/gateway-routes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.loaded")
                .isEqualTo(1)
                .jsonPath("$.services[0].routes[0].http")
                .isEqualTo("GET /v1/products/{sku}");
    }
}
