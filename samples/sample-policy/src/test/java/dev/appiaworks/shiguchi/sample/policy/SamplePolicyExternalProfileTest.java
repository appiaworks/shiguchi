package dev.appiaworks.shiguchi.sample.policy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The {@code external} profile: only methods tagged {@code external} exist. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sample.backend-port=18182", "sample.policy.rules-file=policy-rules.yml"})
@ActiveProfiles("external")
@DisplayName("Policy sample, external instance")
class SamplePolicyExternalProfileTest {

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static String admin() {
        return "Bearer " + LocalJwtConfiguration.mintToken("root", List.of("admin"));
    }

    @Test
    @DisplayName("GIVEN expose-tags external WHEN an external method is called SHOULD answer")
    void given_externalProfile_when_externalMethod_should_answer() {
        client().get()
                .uri("/inv/items/42")
                .header(HttpHeaders.AUTHORIZATION, admin())
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("GIVEN expose-tags external WHEN an internal method is called, even by an admin SHOULD answer 404")
    void given_externalProfile_when_internalMethod_should_notExist() {
        client().delete()
                .uri("/inv/items/43")
                .header(HttpHeaders.AUTHORIZATION, admin())
                .exchange()
                .expectStatus()
                .isNotFound();
    }
}
