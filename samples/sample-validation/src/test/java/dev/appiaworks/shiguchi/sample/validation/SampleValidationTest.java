package dev.appiaworks.shiguchi.sample.validation;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The constraints declared in {@code orders.proto}, enforced at the gateway.
 *
 * <p>Each case names the rule it violates, because "returns 400" is not the interesting part —
 * which rule caught it, and that the call was refused rather than passed on, is.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "sample.backend-port=19190",
            "shiguchi.services[0].name=orders",
            "shiguchi.services[0].mode=DYNAMIC",
            "shiguchi.services[0].host=localhost",
            "shiguchi.services[0].port=19190",
            "shiguchi.validation.gateway-enabled=true"
        })
@DisplayName("Validation sample")
class SampleValidationTest {

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private WebTestClient.ResponseSpec post(String json) {
        return client().post()
                .uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange();
    }

    @Test
    @DisplayName("GIVEN a request satisfying every constraint WHEN posted SHOULD reach the backend and come back as an order")
    void given_validRequest_when_posted_should_reachTheBackend() {
        post("{\"sku\":\"ABC123\",\"quantity\":5,\"customerEmail\":\"buyer@example.com\"}")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .exists()
                .jsonPath("$.sku")
                .isEqualTo("ABC123")
                .jsonPath("$.quantity")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("GIVEN a SKU that does not match the declared pattern WHEN posted SHOULD be refused with 400 before the call leaves")
    void given_badSku_when_posted_should_beRefused() {
        post("{\"sku\":\"nope\",\"quantity\":5,\"customerEmail\":\"buyer@example.com\"}")
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("GIVEN a quantity above the declared maximum WHEN posted SHOULD be refused")
    void given_quantityTooLarge_when_posted_should_beRefused() {
        post("{\"sku\":\"ABC123\",\"quantity\":9999,\"customerEmail\":\"buyer@example.com\"}")
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("GIVEN a quantity below the declared minimum WHEN posted SHOULD be refused, since proto3 cannot express 'required and positive' on its own")
    void given_quantityZero_when_posted_should_beRefused() {
        // Worth its own case: 0 is the proto3 default, so a field left out entirely is
        // indistinguishable from one set to 0 — the constraint is what makes it an error.
        post("{\"sku\":\"ABC123\",\"quantity\":0,\"customerEmail\":\"buyer@example.com\"}")
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("GIVEN a malformed email WHEN posted SHOULD be refused")
    void given_badEmail_when_posted_should_beRefused() {
        post("{\"sku\":\"ABC123\",\"quantity\":5,\"customerEmail\":\"not-an-email\"}")
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("GIVEN a rejected request WHEN the error body is read SHOULD name the field that failed, so the caller can fix it")
    void given_rejection_when_readingTheBody_should_nameTheField() {
        String body = new String(post("{\"sku\":\"nope\",\"quantity\":5,\"customerEmail\":\"buyer@example.com\"}")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .returnResult()
                .getResponseBodyContent());

        // The point of validating at the gateway rather than letting the backend do it: the
        // caller learns which field is wrong.
        org.assertj.core.api.Assertions.assertThat(body).contains("sku");
    }
}
