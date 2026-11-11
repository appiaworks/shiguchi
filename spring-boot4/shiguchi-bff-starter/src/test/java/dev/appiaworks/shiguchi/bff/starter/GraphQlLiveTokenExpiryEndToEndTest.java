package dev.appiaworks.shiguchi.bff.starter;

import static dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.LIVE_QUERY;
import static dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.MUTATION_PREFIX;
import static dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.Backend;
import dev.appiaworks.shiguchi.bff.starter.GraphQlLiveTestSupport.Socket;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import java.io.IOException;
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

/**
 * A {@code @live} query held open over a real WebSocket by a short-lived bearer JWT ends with the
 * token ({@code CookieSessionBindingInterceptor}, reached through the live interceptor), instead of
 * re-running on mutations with credentials that have expired.
 */
@DisplayName("GraphQL live query and bearer token expiry end to end (Boot 4)")
class GraphQlLiveTokenExpiryEndToEndTest {

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

    private static ConfigurableApplicationContext gateway;
    private static int port;
    private static WebClient client;

    @BeforeAll
    static void start() {
        List<String> properties =
                new ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND.server, BACKEND.descriptors));
        properties.removeIf(p -> p.startsWith("shiguchi.graphql.enabled=") || p.startsWith("shiguchi.services["));
        properties.addAll(BACKEND.gatewayProperties());
        properties.addAll(List.of(
                "shiguchi.graphql.enabled=true",
                "spring.graphql.websocket.path=/graphql/ws",
                "shiguchi.graphql.live.enabled=true",
                "shiguchi.graphql.live.min-interval=100ms"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        port = BffRefreshSupport.port(gateway);
        client = BffRefreshSupport.client(port);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) gateway.close();
        BACKEND.close();
        STUB.close();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
        BACKEND.name.set("a");
    }

    private static void mutate(String name, String token) {
        client.post()
                .uri("/graphql")
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", MUTATION_PREFIX + name + "\"}) { name } }"))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN a live query held by a bearer token of 3 seconds WHEN the token expires SHOULD end it with a token expired error and send no result for later mutations")
    void given_liveQuery_when_bearerTokenExpires_should_endStream() throws Exception {
        STUB.accessTokenLifetime(Duration.ofSeconds(3));
        String shortLived = STUB.accessToken("alice");
        STUB.accessTokenLifetime(Duration.ofMinutes(10));
        String longLived = STUB.accessToken("bob");
        try (Socket socket = new Socket(port, h -> h.setBearerAuth(shortLived))) {
            socket.subscribe("1", LIVE_QUERY);
            await(() -> socket.nextCount("1") == 1);

            // while the token is valid, a mutation re-runs the live query
            mutate("b", longLived);
            await(() -> socket.nexts("1", "b").size() == 1);

            await(() -> socket.frames.stream().anyMatch(f -> f.contains("token expired")));
            long delivered = socket.nextCount("1");

            mutate("c", longLived);
            Thread.sleep(1500);

            assertThat(socket.frames.stream()
                            .filter(f -> f.contains("token expired"))
                            .findFirst()
                            .orElseThrow())
                    .contains("\"id\":\"1\"")
                    .contains("UNAUTHORIZED");
            assertThat(socket.nexts("1", "c")).isEmpty();
            assertThat(socket.nextCount("1")).isEqualTo(delivered);
        }
    }
}
