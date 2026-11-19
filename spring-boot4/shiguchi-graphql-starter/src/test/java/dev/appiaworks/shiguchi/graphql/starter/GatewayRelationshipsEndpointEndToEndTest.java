package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.graphql.BatchLoaderDefinition;
import dev.appiaworks.shiguchi.graphql.RelationshipDefinition;
import dev.appiaworks.shiguchi.relationships.GatewayRelationshipsEndpoint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The {@code gateway-relationships} endpoint over HTTP, on a gateway whose relationships come from three
 * places: {@code shop} declares them as OpenAPI extensions, {@code depot} declares one in YAML and one
 * as a Spring bean, and depot's document also has an {@code Invoice.orderId} nobody declared, which
 * the endpoint can only suggest.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "management.endpoints.web.exposure.include=gateway-relationships"
        })
@DisplayName("gateway-relationships endpoint end to end (Boot 4)")
class GatewayRelationshipsEndpointEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        BatchLoaderDefinition depotOrdersByCustomer() {
            return BatchLoaderDefinition.grouped("DepotOrder", "customerId", (keys, metadata) -> Mono.just(List.of()));
        }

        @Bean
        RelationshipDefinition depotCustomerOrders() {
            return RelationshipDefinition.many("DepotCustomer.id", "DepotOrder", "customerId")
                    .named("orders");
        }
    }

    private static final DisposableServer STUB = HttpServer.create()
            .port(0)
            .route(routes -> routes.get(
                            "/v3/api-docs",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(fixture("/openapi/shop-ext.json"))))
                    .get(
                            "/depot-docs",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(fixture("/openapi/depot.json")))))
            .bindNow();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + STUB.port();
        registry.add("shiguchi.services[0].name", () -> "shop");
        registry.add("shiguchi.services[0].mode", () -> "REST_DOWNSTREAM");
        registry.add("shiguchi.services[0].path-prefix", () -> "/shop");
        registry.add("shiguchi.services[0].graphql-type-prefix", () -> "Shop");
        registry.add("shiguchi.services[0].openapi-url", () -> base + "/v3/api-docs");
        registry.add("shiguchi.services[1].name", () -> "depot");
        registry.add("shiguchi.services[1].mode", () -> "REST_DOWNSTREAM");
        registry.add("shiguchi.services[1].path-prefix", () -> "/depot");
        registry.add("shiguchi.services[1].graphql-type-prefix", () -> "Depot");
        registry.add("shiguchi.services[1].openapi-url", () -> base + "/depot-docs");
        registry.add("shiguchi.services[1].relationships[0].from", () -> "DepotOrder.customerId");
        registry.add("shiguchi.services[1].relationships[0].to", () -> "DepotCustomer");
        registry.add("shiguchi.services[1].rest-batch-loaders[0].type", () -> "DepotCustomer");
        registry.add("shiguchi.services[1].rest-batch-loaders[0].endpoint", () -> "/customers");
        registry.add("shiguchi.services[1].rest-batch-loaders[0].ids-param", () -> "ids");
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN graphql is present and the endpoint is exposed WHEN the context starts SHOULD register the endpoint bean")
    void GIVEN_graphqlPresent_WHEN_contextStarts_SHOULD_registerEndpoint() {
        assertThat(context.getBeansOfType(GatewayRelationshipsEndpoint.class)).hasSize(1);
    }

    @Test
    @DisplayName(
            "GIVEN extension, YAML and bean relationships WHEN the endpoint is read SHOULD report each with its source and loader")
    void GIVEN_mixedSources_WHEN_endpointRead_SHOULD_reportSourcesAndLoaders() {
        client.get()
                .uri("/actuator/gateway-relationships")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.types[?(@.type=='ShopOrder')].relationships[0].source")
                .isEqualTo("extension")
                .jsonPath("$.types[?(@.type=='ShopOrder')].relationships[0].loader.kind")
                .isEqualTo("rest")
                .jsonPath("$.types[?(@.type=='DepotOrder')].relationships[0].source")
                .isEqualTo("yaml")
                .jsonPath("$.types[?(@.type=='DepotCustomer')].relationships[0].source")
                .isEqualTo("bean")
                .jsonPath("$.types[?(@.type=='DepotCustomer')].relationships[0].loader.kind")
                .isEqualTo("bean")
                .jsonPath("$.types[?(@.type=='DepotCustomer')].relationships[0].loader.grouped")
                .isEqualTo(true)
                .jsonPath("$.loaders[?(@.name=='ShopCustomer')].kind")
                .isEqualTo("rest")
                .jsonPath("$.batchKeys[?(@.type=='ShopCustomer')].source")
                .isEqualTo("extension");
    }

    @Test
    @DisplayName(
            "GIVEN a REST document with an undeclared reference WHEN the endpoint is read SHOULD suggest it as not wired")
    void GIVEN_undeclaredReference_WHEN_endpointRead_SHOULD_suggestNotWired() {
        client.get()
                .uri("/actuator/gateway-relationships")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.suggestions[?(@.parentType=='DepotInvoice')].source")
                .isEqualTo("suggested")
                .jsonPath("$.suggestions[?(@.parentType=='DepotInvoice')].wired")
                .isEqualTo(false)
                .jsonPath("$.suggestions[?(@.parentType=='DepotInvoice')].hint.yaml")
                .value(v -> assertThat(v.toString()).contains("DepotInvoice.orderId"))
                .jsonPath("$.suggestions[?(@.parentType=='DepotOrder' && @.field=='customer')]")
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN a type query parameter WHEN the endpoint is read SHOULD report only that type")
    void GIVEN_typeParameter_WHEN_endpointRead_SHOULD_reportOnlyThatType() {
        client.get()
                .uri("/actuator/gateway-relationships?type=ShopOrder")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.types.length()")
                .isEqualTo(1)
                .jsonPath("$.types[0].type")
                .isEqualTo("ShopOrder")
                .jsonPath("$.suggestions")
                .isEmpty();
    }

    private static String fixture(String path) {
        try (InputStream in = GatewayRelationshipsEndpointEndToEndTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
