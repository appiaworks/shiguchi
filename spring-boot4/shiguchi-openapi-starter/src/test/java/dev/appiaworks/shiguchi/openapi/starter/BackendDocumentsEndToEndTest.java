package dev.appiaworks.shiguchi.openapi.starter;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The per-backend document route and the Swagger UI config in a gateway with no backends. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.graphql.enabled=false", "shiguchi.ui.enabled=false"})
@DisplayName("Per-backend OpenAPI documents end to end (Boot 4)")
class BackendDocumentsEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName("GIVEN an unknown backend WHEN its document is requested SHOULD answer 404")
    void given_unknownBackend_when_documentRequested_should_answerNotFound() {
        client.get().uri("/v3/api-docs/backends/nope").exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GIVEN fewer than two backends WHEN swagger-config is fetched SHOULD NOT list a document dropdown")
    void given_noBackends_when_swaggerConfigFetched_should_omitUrls() {
        client.get()
                .uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).doesNotContain("All services"));
    }
}
