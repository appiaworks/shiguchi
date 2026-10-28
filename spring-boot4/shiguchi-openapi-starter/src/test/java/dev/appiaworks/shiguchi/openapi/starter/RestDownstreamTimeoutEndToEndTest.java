package dev.appiaworks.shiguchi.openapi.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A REST_DOWNSTREAM passthrough call to a backend that answers slower than the operation's
 * {@code operation-timeouts} entry: Spring Cloud Gateway's per-route {@code response-timeout}
 * must cut the call off instead of waiting for the backend.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.graphql.enabled=false", "shiguchi.ui.enabled=false"})
@DisplayName("REST_DOWNSTREAM operation timeout end to end (Boot 4, Spring Cloud Gateway)")
class RestDownstreamTimeoutEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final Duration BACKEND_DELAY = Duration.ofSeconds(5);

    private static final DisposableServer STUB = HttpServer.create()
            .port(0)
            .route(routes -> routes.get(
                            "/v3/api-docs",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(fixture("/openapi/slow.json"))))
                    .get(
                            "/slow",
                            (req, res) -> Mono.delay(BACKEND_DELAY)
                                    .then(res.header("Content-Type", "application/json")
                                            .sendString(Mono.just("{\"state\":\"late\"}"))
                                            .then()))
                    .get(
                            "/quick",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just("{\"state\":\"ok\"}"))))
            .bindNow();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "slow");
        registry.add("shiguchi.services[0].mode", () -> "REST_DOWNSTREAM");
        registry.add("shiguchi.services[0].path-prefix", () -> "/slow-svc");
        registry.add("shiguchi.services[0].openapi-url", () -> "http://localhost:" + STUB.port() + "/v3/api-docs");
        registry.add("shiguchi.services[0].operation-timeouts.slowItem", () -> "500ms");
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

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
    @DisplayName("Given an operation timeout shorter than the backend's latency, when called through the gateway, "
            + "should answer 504 at the timeout rather than waiting for the backend")
    void given_operationTimeout_when_backendIsSlower_should_return504Promptly() {
        long started = System.nanoTime();

        client.get().uri("/slow-svc/slow").exchange().expectStatus().isEqualTo(504);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(BACKEND_DELAY);
    }

    @Test
    @DisplayName("Given an operation without its own timeout, when the backend answers promptly, "
            + "should proxy the response under the default http-timeout")
    void given_operationWithoutOverride_when_backendAnswers_should_proxyResponse() {
        client.get()
                .uri("/slow-svc/quick")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("ok");
    }

    private static String fixture(String path) {
        try (InputStream in = RestDownstreamTimeoutEndToEndTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
