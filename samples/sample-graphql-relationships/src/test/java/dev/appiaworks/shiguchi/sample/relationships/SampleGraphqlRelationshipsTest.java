package dev.appiaworks.shiguchi.sample.relationships;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/** The sample as its README describes it: each declaration path costs one call however many parents there are. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("sample-graphql-relationships")
class SampleGraphqlRelationshipsTest {

    // Started before the context so the gateway finds its documents, on a free port.
    private static final ShopBackend BACKEND = new ShopBackend(0);

    static {
        BACKEND.start();
    }

    @DynamicPropertySource
    static void backendPort(DynamicPropertyRegistry registry) {
        registry.add("sample.backend-port", BACKEND::port);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    ShipmentClient shipments;

    private WebClient client;

    @BeforeEach
    void reset() {
        client = WebClient.builder().baseUrl("http://localhost:" + port).build();
        BACKEND.clearRequests();
        shipments.clearCalls();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> graphql(String query) {
        Map<String, Object> body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(30));
        assertThat(body).doesNotContainKey("errors");
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> data, String field) {
        return (List<Map<String, Object>>) data.get(field);
    }

    @Test
    @DisplayName(
            "GIVEN x-shiguchi extensions WHEN three orders are listed SHOULD fetch their customers in one call")
    void given_extensions_when_ordersListed_should_batchCustomers() {
        Map<String, Object> data = graphql("{ listOrders { id customer { id name } } }");

        assertThat(list(data, "listOrders")).hasSize(4);
        assertThat(list(data, "listOrders").get(0)).extracting("customer").extracting("name").isEqualTo("Ada");
        assertThat(BACKEND.requests()).containsExactly("GET /orders", "GET /customers?ids=c1,c2,c3");
    }

    @Test
    @DisplayName("GIVEN x-shiguchi extensions WHEN customers are listed SHOULD fetch all their orders in one call")
    void given_extensions_when_customersListed_should_batchOrders() {
        Map<String, Object> data = graphql("{ listCustomers { id orders { id total } } }");

        assertThat(list(data, "listCustomers")).hasSize(3);
        assertThat((List<?>) list(data, "listCustomers").get(0).get("orders")).hasSize(2);
        assertThat(BACKEND.requests()).containsExactly("GET /customers", "GET /orders?customerIds=c1,c2,c3");
    }

    @Test
    @DisplayName("GIVEN YAML relationships WHEN products are listed SHOULD fetch all their reviews in one call")
    void given_yaml_when_productsListed_should_batchReviews() {
        Map<String, Object> data = graphql("{ listProducts { id name reviews { stars } } }");

        assertThat(list(data, "listProducts")).hasSize(3);
        assertThat((List<?>) list(data, "listProducts").get(0).get("reviews")).hasSize(2);
        assertThat(BACKEND.requests()).containsExactly("GET /products", "GET /reviews?productIds=p1,p2,p3");
    }

    @Test
    @DisplayName("GIVEN YAML relationships WHEN reviews are listed SHOULD fetch their products in one call")
    void given_yaml_when_reviewsListed_should_batchProducts() {
        Map<String, Object> data = graphql("{ listReviews { stars product { name } } }");

        assertThat(list(data, "listReviews")).hasSize(5);
        assertThat(list(data, "listReviews").get(2)).extracting("product").extracting("name").isEqualTo("Gadget");
        assertThat(BACKEND.requests()).containsExactly("GET /reviews", "GET /products?ids=p1,p2,p3");
    }

    @Test
    @DisplayName("GIVEN a bean loader over a Jackson-style client WHEN orders are listed SHOULD call the client once")
    void given_beanLoader_when_ordersListed_should_callClientOnce() {
        Map<String, Object> data = graphql("{ listOrders { id shipments { carrier status } } }");

        assertThat(list(data, "listOrders")).hasSize(4);
        // o1 has two shipments, o3 none: an order without children answers [], not null
        assertThat((List<?>) list(data, "listOrders").get(0).get("shipments")).hasSize(2);
        assertThat((List<?>) list(data, "listOrders").get(2).get("shipments")).isEmpty();
        assertThat(shipments.calls()).containsExactly(List.of("o1", "o2", "o3", "o4"));
        assertThat(BACKEND.requests()).containsExactly("GET /orders");
    }

    @Test
    @DisplayName("GIVEN the relationships endpoint WHEN read SHOULD name the source of each declaration path")
    void given_endpoint_when_read_should_listEverySource() {
        String report = client.get()
                .uri("/actuator/gateway-relationships")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        assertThat(report).contains("\"extension\"").contains("\"yaml\"").contains("\"bean\"");
    }
}
