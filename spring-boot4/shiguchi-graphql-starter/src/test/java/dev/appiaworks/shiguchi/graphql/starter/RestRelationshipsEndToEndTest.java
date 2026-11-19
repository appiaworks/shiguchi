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
 * A REST_DOWNSTREAM service with an unannotated OpenAPI document: the relationships and their loaders
 * are declared in YAML only. {@code ShopOrder.customerId} resolves to a {@code ShopCustomer} through a
 * by-id loader, and {@code ShopCustomer.id} to its orders through a {@code group-by} loader. Each is
 * one batched downstream call however many parents the query returns.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("REST relationships declared in YAML end to end (Boot 4)")
class RestRelationshipsEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    /** Requests the stub backend received: "METHOD uri". */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    private static final DisposableServer STUB = HttpServer.create()
            .port(0)
            .route(routes -> routes.get(
                            "/v3/api-docs",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(fixture("/openapi/shop.json"))))
                    .get("/customers", (req, res) -> {
                        RECEIVED.add("GET " + req.uri());
                        String uri = req.uri();
                        String body;
                        if (uri.contains("ids=")) {
                            StringBuilder sb = new StringBuilder("[");
                            for (String id :
                                    uri.substring(uri.indexOf("ids=") + 4).split(",")) {
                                if (sb.length() > 1) sb.append(',');
                                sb.append("{\"id\":\"")
                                        .append(id)
                                        .append("\",\"name\":\"name-")
                                        .append(id)
                                        .append("\"}");
                            }
                            body = sb.append(']').toString();
                        } else {
                            body =
                                    "[{\"id\":\"c1\",\"name\":\"A\"},{\"id\":\"c2\",\"name\":\"B\"},{\"id\":\"c3\",\"name\":\"C\"}]";
                        }
                        return res.header("Content-Type", "application/json").sendString(Mono.just(body));
                    })
                    .get("/orders", (req, res) -> {
                        RECEIVED.add("GET " + req.uri());
                        String uri = req.uri();
                        String body;
                        if (uri.contains("customerIds=")) {
                            StringBuilder sb = new StringBuilder("[");
                            for (String id : uri.substring(uri.indexOf("customerIds=") + 12)
                                    .split(",")) {
                                for (int n = 1; n <= 2; n++) {
                                    if (sb.length() > 1) sb.append(',');
                                    sb.append("{\"id\":\"")
                                            .append(id)
                                            .append("-o")
                                            .append(n)
                                            .append("\",\"customerId\":\"")
                                            .append(id)
                                            .append("\",\"total\":")
                                            .append(n * 10)
                                            .append('}');
                                }
                            }
                            body = sb.append(']').toString();
                        } else {
                            body = "[{\"id\":\"o1\",\"customerId\":\"c1\",\"total\":5},"
                                    + "{\"id\":\"o2\",\"customerId\":\"c2\",\"total\":6},"
                                    + "{\"id\":\"o3\",\"customerId\":\"c1\",\"total\":7}]";
                        }
                        return res.header("Content-Type", "application/json").sendString(Mono.just(body));
                    }))
            .bindNow();

    /** All {@code services[0].*} keys in one source: Boot binds a list from a single property source. */
    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "shop");
        registry.add("shiguchi.services[0].mode", () -> "REST_DOWNSTREAM");
        registry.add("shiguchi.services[0].path-prefix", () -> "/shop");
        registry.add("shiguchi.services[0].graphql-type-prefix", () -> "Shop");
        registry.add("shiguchi.services[0].openapi-url", () -> "http://localhost:" + STUB.port() + "/v3/api-docs");
        registry.add("shiguchi.services[0].relationships[0].from", () -> "ShopOrder.customerId");
        registry.add("shiguchi.services[0].relationships[0].to", () -> "ShopCustomer");
        registry.add("shiguchi.services[0].relationships[1].from", () -> "ShopCustomer.id");
        registry.add("shiguchi.services[0].relationships[1].to", () -> "ShopOrder");
        registry.add("shiguchi.services[0].relationships[1].many", () -> "true");
        registry.add("shiguchi.services[0].relationships[1].field", () -> "orders");
        registry.add("shiguchi.services[0].relationships[1].group-by", () -> "customerId");
        registry.add("shiguchi.services[0].rest-batch-loaders[0].type", () -> "ShopCustomer");
        registry.add("shiguchi.services[0].rest-batch-loaders[0].endpoint", () -> "/customers");
        registry.add("shiguchi.services[0].rest-batch-loaders[0].ids-param", () -> "ids");
        registry.add("shiguchi.services[0].rest-batch-loaders[1].type", () -> "ShopOrder");
        registry.add("shiguchi.services[0].rest-batch-loaders[1].endpoint", () -> "/orders");
        registry.add("shiguchi.services[0].rest-batch-loaders[1].ids-param", () -> "customerIds");
        registry.add("shiguchi.services[0].rest-batch-loaders[1].group-by", () -> "customerId");
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
    @DisplayName(
            "GIVEN a YAML many-to-one relationship WHEN three orders are listed SHOULD fetch their customers in one batched call")
    void GIVEN_yamlManyToOne_WHEN_threeOrdersListed_SHOULD_oneBatchedCustomerCall() {
        graphql("{ listOrders { id customer { id name } } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.listOrders.length()")
                .isEqualTo(3)
                .jsonPath("$.data.listOrders[0].customer.name")
                .isEqualTo("name-c1")
                .jsonPath("$.data.listOrders[1].customer.name")
                .isEqualTo("name-c2")
                .jsonPath("$.data.listOrders[2].customer.name")
                .isEqualTo("name-c1");

        assertThat(RECEIVED).containsExactly("GET /orders", "GET /customers?ids=c1,c2");
    }

    @Test
    @DisplayName(
            "GIVEN a YAML one-to-many relationship WHEN three customers are listed SHOULD fetch all their orders in one batched call")
    void GIVEN_yamlOneToMany_WHEN_threeCustomersListed_SHOULD_oneBatchedOrderCall() {
        graphql("{ listCustomers { id orders { id total } } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.listCustomers.length()")
                .isEqualTo(3)
                .jsonPath("$.data.listCustomers[1].orders.length()")
                .isEqualTo(2)
                .jsonPath("$.data.listCustomers[1].orders[0].id")
                .isEqualTo("c2-o1")
                .jsonPath("$.data.listCustomers[2].orders[1].total")
                .isEqualTo(20);

        assertThat(RECEIVED).containsExactly("GET /customers", "GET /orders?customerIds=c1,c2,c3");
    }

    private static String fixture(String path) {
        try (InputStream in = RestRelationshipsEndToEndTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
