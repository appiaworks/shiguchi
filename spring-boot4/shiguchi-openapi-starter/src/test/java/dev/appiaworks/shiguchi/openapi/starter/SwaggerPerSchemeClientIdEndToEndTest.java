package dev.appiaworks.shiguchi.openapi.starter;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Swagger UI's {@code swagger-initializer.js} as the running gateway serves it, with three OAuth2
 * schemes of which two set a public client id.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.graphql.enabled=false",
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.security-schemes.keycloak.type=oauth2",
            "shiguchi.openapi.security-schemes.keycloak.client-credentials.token-url=https://sso.example.com/token",
            "shiguchi.openapi.security-schemes.keycloak-sso.type=oauth2",
            "shiguchi.openapi.security-schemes.keycloak-sso.authorization-code.authorization-url=https://sso.example.com/auth",
            "shiguchi.openapi.security-schemes.keycloak-sso.authorization-code.token-url=https://sso.example.com/token",
            "shiguchi.openapi.security-schemes.keycloak-sso.authorization-code.client-id=kc-public",
            "shiguchi.openapi.security-schemes.eve-sso.type=oauth2",
            "shiguchi.openapi.security-schemes.eve-sso.authorization-code.authorization-url=https://eve.example.com/auth",
            "shiguchi.openapi.security-schemes.eve-sso.authorization-code.token-url=https://eve.example.com/token",
            "shiguchi.openapi.security-schemes.eve-sso.authorization-code.client-id=</script>eve"
        })
@DisplayName("Swagger UI per-scheme OAuth client ids end to end (Boot 4)")
class SwaggerPerSchemeClientIdEndToEndTest {

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
    @DisplayName(
            "GIVEN three OAuth2 schemes, two with client ids WHEN the initializer is fetched SHOULD carry a per-scheme map "
                    + "of exactly those two, escaped, and enable PKCE")
    void given_severalSchemes_when_initializerFetched_should_carryPerSchemeIds() {
        var js = client.get()
                .uri("/swagger-ui/swagger-initializer.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(js)
                .contains("shiguchiOAuthClientIds")
                .contains("\"keycloak-sso\":\"kc-public\"")
                .contains("\"eve-sso\":\"<\\/script>eve\"")
                .doesNotContain("\"keycloak\":")
                .doesNotContain("</script>")
                // springdoc's own output is kept
                .contains("SwaggerUIBundle")
                .contains("usePkceWithAuthorizationCodeGrant");
    }
}
