package dev.appiaworks.shiguchi.sample.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.function.client.WebClient;

/** The sample as its README describes it: the burst passes, the request after it is refused. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // A property source that sets any shiguchi.services[0].* key replaces the whole list
        // from application.yml, so the service entry is restated here on a port no developer's
        // local backend is likely to hold.
        properties = {
            "sample.backend-port=19090",
            "shiguchi.services[0].name=catalog",
            "shiguchi.services[0].mode=DYNAMIC",
            "shiguchi.services[0].host=localhost",
            "shiguchi.services[0].port=19090"
        })
@DisplayName("sample-rate-limited-gateway")
class SampleRateLimitedGatewayTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("GIVEN a burst of 5 WHEN one caller sends six requests SHOULD serve five and refuse the sixth with 429")
    void given_burst_when_exceeded_should_refuseSixth() {
        WebClient client = WebClient.create("http://localhost:" + port);
        int[] codes = new int[6];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = client.get()
                    .uri("/v1/products/A100")
                    .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value()))
                    .block();
        }
        assertThat(codes).containsExactly(200, 200, 200, 200, 200, 429);
    }

    @Test
    @DisplayName("GIVEN the backend starts before the gateway WHEN health is read SHOULD be UP, and actuator itself is never limited")
    void given_backendUp_when_readingHealth_should_beUpAndUnlimited() {
        WebClient client = WebClient.create("http://localhost:" + port);
        for (int i = 0; i < 8; i++) {
            String body = client.get().uri("/actuator/health").retrieve().bodyToMono(String.class).block();
            assertThat(body).contains("\"status\":\"UP\"");
        }
    }
}
