package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * The {@code gateway-push} actuator endpoint on the Boot 4 gateway over a real port (mirrors
 * {@code PushAdminEndToEndTest} in spring-boot3/shiguchi-cursus-starter; the push module is
 * byte-identical between the lines): the overview
 * and the lists read anonymously; the writes need a JWT (the chain) and, here, the configured
 * admin role; an operator's disconnect ends the subscriber's stream with a {@code close} frame;
 * a test publish reaches a subscriber. The console's config advertises the endpoint.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=true",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push admin endpoint end to end (Boot 4)")
class PushAdminEndToEndTest {

    static final String ADMIN_TOKEN = "admin-token";
    static final String USER_TOKEN = "user-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** Two fixed tokens stand in for an issuer: one with the admin scope, one without. */
        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> switch (token) {
                case ADMIN_TOKEN -> Mono.just(jwt("ops", "PUSH_ADMIN"));
                case USER_TOKEN -> Mono.just(jwt("alice", "read"));
                default -> Mono.error(new BadJwtException("unknown token"));
            };
        }

        private static Jwt jwt(String subject, String scope) {
            return Jwt.withTokenValue(subject)
                    .header("alg", "none")
                    .subject(subject)
                    .claim("scope", scope)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    private WebClient client;

    @BeforeEach
    void client() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    @Test
    @DisplayName("GIVEN the endpoint exposed WHEN read anonymously SHOULD answer the overview and the lists")
    void given_exposed_when_readAnonymously_should_answerOverviewAndLists() {
        JsonNode overview = get("/actuator/gateway-push", null);
        assertThat(overview.get("engine").get("broker").asText()).isEqualTo("MEMORY");
        assertThat(overview.get("namespaces").asInt()).isEqualTo(2);
        assertThat(overview.get("path").asText()).isEqualTo("/push");
        JsonNode namespaces = get("/actuator/gateway-push/namespaces", null).get("namespaces");
        assertThat(namespaces).extracting(n -> n.get("name").asText()).containsExactlyInAnyOrder("ticker", "orders");
        assertThat(get("/actuator/gateway-push/topics", null).get("topics")).isEmpty();
        assertThat(get("/actuator/gateway-push/connections", null).get("connections"))
                .isEmpty();
        assertThat(status("GET", "/actuator/gateway-push/bogus", null)).isEqualTo(404);
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber WHEN the operator lists topics and connections SHOULD see it, and WHEN it disconnects it SHOULD end the stream with a close frame")
    void given_subscriber_when_operatorDisconnects_should_closeStream() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        int before = metrics.connections();
        Disposable stream = client.get()
                .uri("/push/subscribe?topic=ticker:btc")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null)
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections() == before + 1);

        JsonNode topics = get("/actuator/gateway-push/topics?q=ticker", null).get("topics");
        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).get("topic").asText()).isEqualTo("ticker:btc");
        assertThat(topics.get(0).get("subscribers").asInt()).isEqualTo(1);
        JsonNode connections =
                get("/actuator/gateway-push/connections?topic=ticker:btc", null).get("connections");
        assertThat(connections).hasSize(1);
        String id = connections.get(0).get("id").asText();
        assertThat(connections.get(0).get("transport").asText()).isEqualTo("sse");
        assertThat(connections.get(0).get("principal").isNull()).isTrue();

        assertThat(status("DELETE", "/actuator/gateway-push/connections/" + id, null))
                .isEqualTo(401);
        assertThat(status("DELETE", "/actuator/gateway-push/connections/" + id, USER_TOKEN))
                .isEqualTo(403);
        assertThat(status("DELETE", "/actuator/gateway-push/connections/" + id, ADMIN_TOKEN))
                .isEqualTo(200);

        awaitTrue(() -> !frames.isEmpty()
                && "close".equals(frames.get(frames.size() - 1).event()));
        assertThat(frames.get(frames.size() - 1).data()).isEqualTo("{\"reason\":\"admin\"}");
        awaitTrue(() -> metrics.connections() == before);
        assertThat(status("DELETE", "/actuator/gateway-push/connections/" + id, ADMIN_TOKEN))
                .isEqualTo(404);
        stream.dispose();
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber WHEN the operator publishes a test event SHOULD deliver it with from=admin, and refuse anonymous or unprivileged callers")
    void given_subscriber_when_operatorPublishes_should_deliver() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        int before = metrics.connections();
        Disposable stream = client.get()
                .uri("/push/subscribe?topic=orders:77")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null)
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections() == before + 1);

        String body = "{\"topic\":\"orders:77\",\"data\":\"{\\\"n\\\":1}\"}";
        assertThat(post("/actuator/gateway-push/publish", body, null)
                        .statusCode()
                        .value())
                .isEqualTo(401);
        assertThat(post("/actuator/gateway-push/publish", body, USER_TOKEN)
                        .statusCode()
                        .value())
                .isEqualTo(403);
        ClientResponse ok = post("/actuator/gateway-push/publish", body, ADMIN_TOKEN);
        assertThat(ok.statusCode().value()).isEqualTo(200);
        assertThat(post("/actuator/gateway-push/publish", "{\"topic\":\"nope:x\",\"data\":\"1\"}", ADMIN_TOKEN)
                        .statusCode()
                        .value())
                .isEqualTo(404);

        awaitTrue(() -> !frames.isEmpty());
        assertThat(frames.get(0).data()).isEqualTo("{\"n\":1}");
        assertThat(frames.get(0).event()).isEqualTo("orders:77");
        stream.dispose();
    }

    @Test
    @DisplayName(
            "GIVEN the console on WHEN its config is read SHOULD advertise the push surface and its admin endpoint")
    void given_consoleOn_when_configRead_should_advertisePush() {
        JsonNode config = get("/grpc-ui/api/config", null);
        assertThat(config.get("pushEnabled").asBoolean()).isTrue();
        assertThat(config.get("pushPath").asText()).isEqualTo("/push");
        assertThat(config.get("pushAdminPath").asText()).isEqualTo("/actuator/gateway-push");
    }

    // ---- helpers ----

    private JsonNode get(String path, String token) {
        String body = client.get()
                .uri(path)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        try {
            return JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw new AssertionError(body, e);
        }
    }

    private int status(String method, String path, String token) {
        return client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
    }

    private ClientResponse post(String path, String json, String token) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .bodyValue(json)
                .exchangeToMono(Mono::just)
                .block(Duration.ofSeconds(30));
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return Map.of();
    }
}
