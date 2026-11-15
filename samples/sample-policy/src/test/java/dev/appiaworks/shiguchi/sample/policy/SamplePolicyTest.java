package dev.appiaworks.shiguchi.sample.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The tag-driven rules end to end: allow, deny, throttle, and a reload that changes a decision.
 *
 * <p>The rules file is a temporary copy of {@code policy-rules.yml}, so the tests can edit it. The
 * reload is triggered by calling {@link FileBackedPolicy#reloadIfChanged()} directly instead of
 * waiting for the scheduler.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sample.backend-port=18181", "sample.policy.reload-interval=1h"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Policy sample")
class SamplePolicyTest {

    private static final Path RULES = rulesCopy();

    @DynamicPropertySource
    static void rules(DynamicPropertyRegistry registry) {
        registry.add("sample.policy.rules-file", RULES::toString);
    }

    @LocalServerPort
    int port;

    @Autowired
    FileBackedPolicy policy;

    @BeforeEach
    void restoreRules() throws IOException {
        Files.writeString(RULES, Files.readString(Path.of("policy-rules.yml")));
        policy.reloadIfChanged();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static String bearer(String subject, String... scopes) {
        return "Bearer " + LocalJwtConfiguration.mintToken(subject, List.of(scopes));
    }

    @Test
    @DisplayName("GIVEN an external method WHEN an ordinary caller reads it SHOULD allow it")
    void given_externalTag_when_ordinaryCaller_should_allow() {
        client().get()
                .uri("/inv/items/42")
                .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo("42");
    }

    @Test
    @DisplayName("GIVEN an internal method WHEN a non-admin calls it SHOULD deny with 403, and let an admin through")
    void given_internalTag_when_nonAdmin_should_deny() {
        client().delete()
                .uri("/inv/items/43")
                .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                .exchange()
                .expectStatus()
                .isForbidden();
        client().delete()
                .uri("/inv/items/43")
                .header(HttpHeaders.AUTHORIZATION, bearer("root", "admin"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    @DisplayName("GIVEN the readonly scope WHEN the caller writes SHOULD deny it, while reads stay allowed")
    void given_readonlyScope_when_writing_should_deny() {
        client().post()
                .uri("/inv/items")
                .header(HttpHeaders.AUTHORIZATION, bearer("viewer", "readonly"))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"sku\":\"x\",\"name\":\"X\"}")
                .exchange()
                .expectStatus()
                .isForbidden();
        client().get()
                .uri("/inv/items/42")
                .header(HttpHeaders.AUTHORIZATION, bearer("viewer", "readonly"))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @Order(Integer.MAX_VALUE)
    @DisplayName("GIVEN an expensive tag with 2 calls per window WHEN it is called a third time SHOULD answer 429 with Retry-After")
    void given_expensiveTag_when_overLimit_should_throttle() {
        for (int i = 0; i < 2; i++) {
            client().get()
                    .uri("/inv/items")
                    .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
        client().get()
                .uri("/inv/items")
                .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .exists(HttpHeaders.RETRY_AFTER);
    }

    @Test
    @DisplayName("GIVEN the rules file is edited WHEN it is reloaded SHOULD change the decision, and a bad file SHOULD keep the old rules")
    void given_edit_when_reloaded_should_changeDecision() throws IOException {
        String delete = "/inv/items/43";
        client().delete()
                .uri(delete)
                .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                .exchange()
                .expectStatus()
                .isForbidden();

        // A file that does not parse is ignored: the internal rule still denies.
        Files.writeString(RULES, "rules: [this is: not, valid: yaml: at all");
        assertThat(policy.reloadIfChanged()).isFalse();
        assertThat(policy.ruleIds()).contains("internal-needs-admin");
        client().delete()
                .uri(delete)
                .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                .exchange()
                .expectStatus()
                .isForbidden();

        // A valid file without the internal rule takes effect at once.
        Files.writeString(RULES, "rules: []\n");
        assertThat(policy.reloadIfChanged()).isTrue();
        client().delete()
                .uri(delete)
                .header(HttpHeaders.AUTHORIZATION, bearer("alice"))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    @DisplayName("GIVEN a rules file that does not parse WHEN the policy is created SHOULD refuse to start")
    void given_badFile_when_starting_should_fail() throws IOException {
        Path bad = Files.createTempFile("bad-rules", ".yml");
        Files.writeString(bad, "rules:\n  - id: x\n    type: no-such-type\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FileBackedPolicy(bad))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Path rulesCopy() {
        try {
            Path copy = Files.createTempFile("policy-rules", ".yml");
            copy.toFile().deleteOnExit();
            Files.writeString(copy, Files.readString(Path.of("policy-rules.yml")));
            return copy;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
