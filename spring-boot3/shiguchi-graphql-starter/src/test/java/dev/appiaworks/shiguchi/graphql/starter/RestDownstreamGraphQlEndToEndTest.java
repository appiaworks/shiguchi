package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The whole Boot 3 gateway with the GraphQL surface (DGS) in front of a reactor-netty stub that
 * serves an OpenAPI document and JSON endpoints. Proves that a REST_DOWNSTREAM backend appears in
 * the generated schema and that queries and mutations are dispatched over HTTP: path parameters
 * substituted, array responses returned as lists, bodies posted as JSON, hidden operations absent,
 * scoped operations rejected, and the REST passthrough still serving next to GraphQL.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("REST_DOWNSTREAM over GraphQL end to end (Boot 3, DGS)")
class RestDownstreamGraphQlEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    /** Requests the stub backend received: "METHOD path [body]". */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    private static final DisposableServer STUB = HttpServer.create()
            .port(0)
            .route(routes -> routes.get(
                            "/v3/api-docs",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(fixture("/openapi/inventory.json"))))
                    .get("/items/{sku}", (req, res) -> {
                        RECEIVED.add("GET " + req.fullPath());
                        return res.header("Content-Type", "application/json")
                                .sendString(
                                        Mono.just(
                                                "{\"sku\":\"" + req.param("sku")
                                                        + "\",\"name\":\"Widget\",\"qty\":7,\"category\":{\"id\":3,\"name\":\"tools\"}}"));
                    })
                    .get("/items", (req, res) -> {
                        RECEIVED.add("GET " + req.uri());
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.just("[{\"sku\":\"1\",\"qty\":1},{\"sku\":\"2\",\"qty\":2}]"));
                    })
                    .post(
                            "/items",
                            (req, res) -> req.receive()
                                    .aggregate()
                                    .asString()
                                    .defaultIfEmpty("")
                                    .flatMap(body -> {
                                        RECEIVED.add("POST /items " + body);
                                        return res.status(201)
                                                .header("Content-Type", "application/json")
                                                .sendString(Mono.just("{\"sku\":\"9\",\"name\":\"New\",\"qty\":1}"))
                                                .then();
                                    }))
                    .get("/secret", (req, res) -> {
                        RECEIVED.add("GET /secret");
                        return res.sendString(Mono.just("{\"secret\":true}"));
                    }))
            .bindNow();

    /** All {@code services[0].*} keys in one source: Boot binds a list from a single property source. */
    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "inventory");
        registry.add("shiguchi.services[0].mode", () -> "REST_DOWNSTREAM");
        registry.add("shiguchi.services[0].path-prefix", () -> "/inv");
        registry.add("shiguchi.services[0].exclude-methods[0]", () -> "deleteItem");
        registry.add("shiguchi.services[0].openapi-url", () -> "http://localhost:" + STUB.port() + "/v3/api-docs");
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

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

    private WebTestClient.BodyContentSpec graphql(String query) {
        return client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    @Test
    @DisplayName("Given a GET operation with a path parameter, when queried through GraphQL, "
            + "should call the backend path with the argument substituted and return the selected fields")
    void given_getOperationWithPathParam_when_queried_should_dispatchOverHttpAndReturnFields() {
        graphql("{ getItem(sku: \"42\") { sku qty category { name } } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.getItem.sku")
                .isEqualTo("42")
                .jsonPath("$.data.getItem.qty")
                .isEqualTo(7)
                .jsonPath("$.data.getItem.category.name")
                .isEqualTo("tools");

        assertThat(RECEIVED).containsExactly("GET /items/42");
    }

    @Test
    @DisplayName("Given an operation returning a JSON array with a query parameter, when queried, "
            + "should send the query string and return a list")
    void given_arrayOperationWithQueryParam_when_queried_should_sendQueryAndReturnList() {
        graphql("{ listItems(status: \"ACTIVE\") { sku } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.listItems.length()")
                .isEqualTo(2)
                .jsonPath("$.data.listItems[1].sku")
                .isEqualTo("2");

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0)).isEqualTo("GET /items?status=ACTIVE");
    }

    @Test
    @DisplayName("Given a POST operation with a body schema, when mutated through GraphQL, "
            + "should post the input object as a JSON body and return the created item")
    void given_postOperation_when_mutated_should_postJsonBody() {
        graphql("mutation { createItem(input: { sku: \"9\", name: \"New\", qty: \"1\" }) { sku name } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.createItem.sku")
                .isEqualTo("9");

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0))
                .startsWith("POST /items ")
                .contains("\"sku\":\"9\"")
                .contains("\"name\":\"New\"");
    }

    @Test
    @DisplayName("Given an operation hidden by exclude-methods, when the schema is introspected, "
            + "should not expose it while exposing the others")
    @SuppressWarnings("unchecked")
    void given_excludedOperation_when_introspected_should_beAbsentFromSchema() {
        graphql("{ __type(name: \"Mutation\") { fields { name } } }")
                .jsonPath("$.data.__type.fields[*].name")
                .value(names -> {
                    assertThat((List<Object>) names).contains("createItem").doesNotContain("deleteItem");
                });
    }

    @Test
    @DisplayName("Given an operation declaring x-gateway-auth-scopes, when queried anonymously, "
            + "should return a GraphQL error and never reach the backend")
    void given_scopedOperation_when_queriedAnonymously_should_errorBeforeProxying() {
        graphql("{ getSecret { secret } }").jsonPath("$.errors[0].message").exists();

        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("Given GraphQL enabled for the service, when the REST passthrough is called, "
            + "should still proxy with the prefix stripped")
    void given_graphqlEnabled_when_restPassthroughCalled_should_stillProxy() {
        client.get()
                .uri("/inv/items/42")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo("42");

        assertThat(RECEIVED).containsExactly("GET /items/42");
    }

    private static String fixture(String path) {
        try (InputStream in = RestDownstreamGraphQlEndToEndTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
