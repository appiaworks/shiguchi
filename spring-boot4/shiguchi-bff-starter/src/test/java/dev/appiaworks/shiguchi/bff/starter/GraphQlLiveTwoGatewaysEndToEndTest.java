package dev.appiaworks.shiguchi.bff.starter;

import static dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.LIVE_QUERY;
import static dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.MUTATION_PREFIX;
import static dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.Backend;
import dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.Socket;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import redis.embedded.RedisServer;

/**
 * Two gateway applications over one embedded Redis with Cursus on and the same gRPC backend: a
 * mutation served by gateway A re-runs a {@code @live} query held open on gateway B through the
 * Cursus broker. The 5-minute refresh is the only other thing that could re-run it, so a second
 * result inside the test can only come from the invalidation that crossed Redis.
 */
@DisplayName("GraphQL live query across two gateways over Redis end to end (Boot 4)")
class GraphQlLiveTwoGatewaysEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Backend BACKEND = new Backend();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }
    }

    private static RedisServer redis;
    private static ConfigurableApplicationContext gatewayA;
    private static ConfigurableApplicationContext gatewayB;
    private static int portA;
    private static int portB;
    private static WebClient clientA;

    @BeforeAll
    static void start() throws IOException {
        int redisPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(redisPort).build();
        redis.start();
        gatewayA = gateway(redisPort, "gateway-a");
        gatewayB = gateway(redisPort, "gateway-b");
        portA = BffRefreshSupport.port(gatewayA);
        portB = BffRefreshSupport.port(gatewayB);
        clientA = BffRefreshSupport.client(portA);
    }

    private static ConfigurableApplicationContext gateway(int redisPort, String instanceId) {
        List<String> properties =
                new ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND.server, BACKEND.descriptors));
        properties.removeIf(p -> p.startsWith("shiguchi.graphql.enabled=") || p.startsWith("shiguchi.services["));
        properties.addAll(BACKEND.gatewayProperties());
        properties.addAll(List.of(
                "shiguchi.graphql.enabled=true",
                "spring.graphql.websocket.path=/graphql/ws",
                "shiguchi.graphql.live.enabled=true",
                "shiguchi.graphql.live.min-interval=100ms",
                "shiguchi.cursus.enabled=true",
                "shiguchi.cursus.instance-id=" + instanceId,
                "shiguchi.cursus.broker=REDIS",
                "shiguchi.cursus.store=REDIS",
                "shiguchi.cursus.namespaces.user.auth=PRINCIPAL_MATCH",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=" + redisPort));
        return BffRefreshSupport.start(TestApp.class, properties);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gatewayB != null) gatewayB.close();
        if (gatewayA != null) gatewayA.close();
        if (redis != null) redis.stop();
        BACKEND.close();
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
        BACKEND.name.set("a");
    }

    private static void mutateOnA(String name) {
        clientA.post()
                .uri("/graphql")
                .headers(h -> h.setBearerAuth(STUB.accessToken("bob")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", MUTATION_PREFIX + name + "\"}) { name } }"))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN a live query held open on gateway B WHEN a mutation is served by gateway A SHOULD send B's client a second result numbered 2")
    void given_liveQueryOnB_when_mutationOnA_should_rerunOnB() {
        String token = STUB.accessToken("alice");
        try (Socket socket = new Socket(portB, h -> h.setBearerAuth(token))) {
            socket.subscribe("1", LIVE_QUERY);
            await(() -> socket.nexts("1", "a").size() == 1);

            // A Pub/Sub subscription takes a moment to register on Redis: mutate until B hears
            // (every mutation sets the same name, so repeats change nothing on B's side).
            await(() -> {
                mutateOnA("b");
                return !socket.nexts("1", "b").isEmpty();
            });

            String second = socket.nexts("1", "b").get(0);
            assertThat(second).contains("\"seq\":2");
            assertThat(socket.nextCount("1")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("GIVEN a live query held open on gateway B WHEN nothing is mutated SHOULD send no second result")
    void given_liveQueryOnB_when_noMutation_should_stayQuiet() throws Exception {
        String token = STUB.accessToken("alice");
        try (Socket socket = new Socket(portB, h -> h.setBearerAuth(token))) {
            socket.subscribe("1", LIVE_QUERY);
            await(() -> socket.nexts("1", "a").size() == 1);

            Thread.sleep(2000);

            assertThat(socket.nextCount("1")).isEqualTo(1);
        }
    }
}
