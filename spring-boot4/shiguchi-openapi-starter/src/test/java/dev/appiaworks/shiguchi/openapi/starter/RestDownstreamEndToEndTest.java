package dev.appiaworks.shiguchi.openapi.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The whole Boot 4 gateway (Spring Cloud Gateway, springdoc, the REST_DOWNSTREAM loader) in front
 * of a reactor-netty stub that serves an OpenAPI document and a couple of endpoints. Proves the
 * three promises of REST_DOWNSTREAM end to end: requests are proxied with the prefix stripped,
 * hidden operations are not routable, and the document lands in the consolidated spec.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.graphql.enabled=false", "shiguchi.ui.enabled=false"})
@DisplayName("REST_DOWNSTREAM end to end (Boot 4, Spring Cloud Gateway + springdoc)")
class RestDownstreamEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    /** Paths the stub backend actually received, so prefix stripping can be asserted. */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    /** Started eagerly: the port must be known before the Spring context resolves properties. */
    private static final DisposableServer STUB = HttpServer.create()
            .port(0)
            .route(routes -> routes.get(
                            "/v3/api-docs",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(fixture("/openapi/inventory.json"))))
                    .get("/items/{sku}", (req, res) -> {
                        RECEIVED.add(req.fullPath());
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"sku\":\"" + req.param("sku") + "\",\"qty\":7}"));
                    })
                    .get("/items", (req, res) -> {
                        RECEIVED.add(req.fullPath());
                        return res.sendString(Mono.just("[]"));
                    })
                    .get("/secret", (req, res) -> {
                        RECEIVED.add(req.fullPath());
                        return res.sendString(Mono.just("{\"secret\":true}"));
                    }))
            .bindNow();

    /**
     * Every {@code services[0].*} key lives in this one source on purpose: Spring Boot binds a
     * list from the highest-precedence source that defines it and does not merge list elements
     * across sources, so splitting them between {@code properties} and here loses the rest.
     */
    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "inventory");
        registry.add("shiguchi.services[0].mode", () -> "REST_DOWNSTREAM");
        registry.add("shiguchi.services[0].path-prefix", () -> "/inv");
        registry.add("shiguchi.services[0].exclude-methods[0]", () -> "listItems");
        registry.add("shiguchi.services[0].openapi-url", () -> "http://localhost:" + STUB.port() + "/v3/api-docs");
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    /**
     * Bound to the random server port rather than autowired: Boot 3 registers a
     * {@code WebTestClient} bean from the test starter, Boot 4 needs an extra module for that,
     * and binding by hand keeps this test identical on both lines.
     */
    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClientAndClearReceived() {
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
        RECEIVED.clear();
    }

    @Test
    @DisplayName("Given a routed operation under a path prefix, when called through the gateway, "
            + "should proxy to the backend with the prefix stripped and return its body")
    void given_routedOperationUnderPrefix_when_calledThroughGateway_should_proxyWithPrefixStripped() {
        client.get()
                .uri("/inv/items/42")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo("42")
                .jsonPath("$.qty")
                .isEqualTo(7);

        assertThat(RECEIVED).containsExactly("/items/42");
    }

    @Test
    @DisplayName("Given an operation hidden by exclude-methods, when called through the gateway, "
            + "should return 404 and never reach the backend")
    void given_excludedOperation_when_calledThroughGateway_should_return404WithoutProxying() {
        client.get().uri("/inv/items").exchange().expectStatus().isNotFound();

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("Given an operation declaring x-gateway-auth-scopes, when called anonymously, "
            + "should be rejected before the backend is reached")
    void given_scopedOperation_when_calledAnonymously_should_rejectBeforeProxying() {
        client.get().uri("/inv/secret").exchange().expectStatus().is4xxClientError();

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName(
            "Given a REST_DOWNSTREAM service, when the consolidated OpenAPI document is fetched, "
                    + "should contain its prefixed routed paths and namespaced components, and omit hidden and un-prefixed ones")
    void given_downstreamService_when_apiDocsFetched_should_containPrefixedPathsAndNamespacedComponents() {
        client.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.paths['/inv/items/{sku}'].get.operationId")
                .isEqualTo("inventory_getItem")
                .jsonPath("$.paths['/inv/items/{sku}'].get.tags[0]")
                .isEqualTo("inventory / Items")
                .jsonPath("$.paths['/inv/items/{sku}'].get.parameters[0]['$ref']")
                .isEqualTo("#/components/parameters/inventory.SkuParam")
                .jsonPath("$.paths['/inv/items/{sku}'].get.responses['404']['$ref']")
                .isEqualTo("#/components/responses/inventory.NotFound")
                .jsonPath("$.paths['/inv/items']")
                .doesNotExist()
                .jsonPath("$.paths['/items/{sku}']")
                .doesNotExist()
                .jsonPath("$.components.schemas['inventory.Item']")
                .exists()
                .jsonPath("$.components.schemas['inventory.Error']")
                .exists()
                .jsonPath("$.components.schemas.Item")
                .doesNotExist()
                .jsonPath("$.components.securitySchemes.bearer")
                .doesNotExist()
                .jsonPath("$.components.securitySchemes['inventory.bearer']")
                .doesNotExist();
    }

    private static String fixture(String path) {
        try (InputStream in = RestDownstreamEndToEndTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
