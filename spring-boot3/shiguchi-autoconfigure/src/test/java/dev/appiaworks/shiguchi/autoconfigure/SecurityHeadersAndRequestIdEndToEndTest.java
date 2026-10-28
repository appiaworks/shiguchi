package dev.appiaworks.shiguchi.autoconfigure;

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
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The security headers and the request id as a running Boot 3 gateway actually serves them —
 * which is the part a unit test cannot show, since both depend on the filters being registered
 * and ordered in a real chain.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=true",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "management.endpoints.web.exposure.include=health"
        })
@DisplayName("Security headers and request id end to end (Boot 3)")
class SecurityHeadersAndRequestIdEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private HttpHeaders headersOf(String uri, String... requestHeaders) {
        return client.get()
                .uri(uri)
                .headers(h -> {
                    for (int i = 0; i < requestHeaders.length; i += 2) {
                        h.set(requestHeaders[i], requestHeaders[i + 1]);
                    }
                })
                .exchangeToMono(r -> r.releaseBody().thenReturn(r.headers().asHttpHeaders()))
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN the console WHEN a browser loads it SHOULD carry a content-security policy and the other browser headers")
    void given_console_when_loaded_should_carrySecurityHeaders() {
        HttpHeaders headers = headersOf("/grpc-ui/");

        assertThat(headers.getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(headers.getFirst("Permissions-Policy")).contains("camera=()");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        // Spring Security's own defaults are still there; the gateway adds to them, not replaces.
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    @DisplayName("GIVEN a non-browser path WHEN it responds SHOULD not carry the browser-only headers")
    void given_nonBrowserPath_when_responding_should_omitBrowserHeaders() {
        HttpHeaders headers = headersOf("/actuator/health");

        assertThat(headers.getFirst("Content-Security-Policy")).isNull();
        assertThat(headers.getFirst("Permissions-Policy")).isNull();
    }

    @Test
    @DisplayName("GIVEN no request id WHEN a request arrives SHOULD answer with a generated one")
    void given_noRequestId_when_requesting_should_generateOne() {
        String id = headersOf("/actuator/health").getFirst("X-Request-Id");

        assertThat(id).isNotBlank();
        assertThat(java.util.UUID.fromString(id)).isNotNull();
    }

    @Test
    @DisplayName("GIVEN the caller sent a request id WHEN it answers SHOULD echo that one back")
    void given_callerRequestId_when_requesting_should_echoIt() {
        HttpHeaders headers = headersOf("/actuator/health", "X-Request-Id", "trace-42");

        assertThat(headers.getFirst("X-Request-Id")).isEqualTo("trace-42");
    }

    @Test
    @DisplayName(
            "GIVEN two requests WHEN neither sends an id SHOULD get different ids, so the logs can tell them apart")
    void given_twoRequests_when_generated_should_differ() {
        String first = headersOf("/actuator/health").getFirst("X-Request-Id");
        String second = headersOf("/actuator/health").getFirst("X-Request-Id");

        assertThat(first).isNotEqualTo(second);
    }
}
