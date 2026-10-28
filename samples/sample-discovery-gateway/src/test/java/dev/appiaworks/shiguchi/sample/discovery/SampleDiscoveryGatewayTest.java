package dev.appiaworks.shiguchi.sample.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The sample end to end: the gateway finds the catalog instances through the
 * {@link org.springframework.cloud.client.discovery.DiscoveryClient}, transcodes REST to gRPC, and
 * spreads calls over every instance.
 *
 * <p>The assertions are about balancing rather than about one call working, because a sample whose
 * round-robin quietly degraded to pick_first would still answer 200 to everything.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sample.instances=3", "shiguchi.client.discovery-refresh=200ms"})
@DisplayName("Discovery sample")
// The failover test kills an instance the others expect to be up. Ordering it last, plus the
// @DirtiesContext on it, keeps the fleet whole for everything before it.
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class SampleDiscoveryGatewayTest {

    @LocalServerPort
    int port;

    @Autowired
    CatalogFleet fleet;

    private WebTestClient client;

    private WebTestClient client() {
        if (client == null) {
            client = WebTestClient.bindToServer()
                    .baseUrl("http://localhost:" + port)
                    .responseTimeout(Duration.ofSeconds(30))
                    .build();
        }
        return client;
    }

    /** The id of the instance that answered one {@code GET /v1/products}. */
    private String servedBy() {
        byte[] body = client().get()
                .uri("/v1/products")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBodyContent();
        return new String(body).replaceAll("(?s).*\"servedBy\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("GIVEN three registered instances WHEN the same route is called repeatedly SHOULD reach every one of them, not just the first")
    void given_threeInstances_when_callingRepeatedly_should_reachAllOfThem() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 30 && seen.size() < 3; i++) {
            seen.add(servedBy());
        }

        assertThat(seen)
                .as("round_robin spreads calls over every discovered instance")
                .containsExactlyInAnyOrder("catalog-1", "catalog-2", "catalog-3");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("GIVEN the route was discovered WHEN a single product is fetched SHOULD transcode the path variable to the gRPC request")
    void given_discoveredRoute_when_fetchingOne_should_transcode() {
        client().get()
                .uri("/v1/products/B200")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo("B200")
                .jsonPath("$.name")
                .isEqualTo("Bird seed, 10 kg");
    }

    // Kills an instance the other tests expect to be running, so this one gets the context last
    // and nobody else inherits a two-instance fleet.
    @Test
    @org.junit.jupiter.api.Order(4)
    @org.springframework.test.annotation.DirtiesContext
    @DisplayName("GIVEN an instance goes away WHEN calls continue SHOULD keep serving on the rest, which is the reason for asking discovery at all")
    void given_instanceStops_when_callingAgain_should_keepServingOnTheRest() {
        String stopped = fleet.stopOne();

        // The channel needs the next discovery refresh (200 ms here) to drop the address. Until
        // then a call may land on the dead instance; what must not happen is calls failing for
        // good once the list has been re-read.
        Set<String> seen = new HashSet<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && seen.size() < 2) {
            try {
                seen.add(servedBy());
            } catch (RuntimeException transientFailure) {
                // in flight when the instance died; the refresh has not landed yet
            }
        }

        assertThat(seen).doesNotContain(stopped);
        assertThat(seen)
                .as("the surviving instances keep answering")
                .containsExactlyInAnyOrder("catalog-1", "catalog-2");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("GIVEN the gateway discovered the backend WHEN the routes endpoint is read SHOULD show the discovery target rather than a host and port")
    void given_discoveryBackend_when_readingRoutes_should_showTheDiscoveryTarget() {
        client().get()
                .uri("/actuator/gateway-routes")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.loaded")
                .isEqualTo(1)
                .jsonPath("$.services[0].name")
                .isEqualTo("catalog")
                // The target is the discovery name, not an address: which instances are behind it
                // is the resolver's business and changes between refreshes.
                .jsonPath("$.services[0].target")
                .isEqualTo("catalog-service")
                // Routes are sorted by method name, so GetProduct comes before ListProducts.
                .jsonPath("$.services[0].routes[0].http")
                .isEqualTo("GET /v1/products/{sku}")
                .jsonPath("$.services[0].routes[1].http")
                .isEqualTo("GET /v1/products");
    }
}
