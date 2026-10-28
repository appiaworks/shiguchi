package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * With {@code shiguchi.security.end-streams-at-token-expiry=false} a bearer REST stream and
 * GraphQL subscription outlive their token on the Boot 4 gateway. Mirrors {@code spring-boot3/shiguchi-bff-starter}.
 */
@DisplayName("Bearer streams with end-streams-at-token-expiry off end to end (Boot 4)")
class BffBearerStreamExpiryDisabledEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

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
    private static WebClient client;

    @BeforeAll
    static void start() {
        List<String> properties = new ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.removeIf(p -> p.startsWith("shiguchi.graphql.enabled="));
        properties.addAll(List.of(
                "shiguchi.security.end-streams-at-token-expiry=false",
                "shiguchi.graphql.enabled=true",
                "spring.graphql.websocket.path=/graphql/ws",
                "shiguchi.cursus.enabled=true",
                "shiguchi.cursus.namespaces.user.auth=PRINCIPAL_MATCH",
                "shiguchi.cursus.namespaces.user.publish-auth=NONE",
                "shiguchi.cursus.connection.keepalive=1s",
                "shiguchi.cursus.connection.max-lifetime=0"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        client = BffRefreshSupport.client(BffRefreshSupport.port(gateway));
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) gateway.close();
        BACKEND.shutdownNow();
        STUB.close();
        Files.deleteIfExists(PB_FILE);
    }

    @BeforeEach
    void resetIssuer() {
        STUB.reset();
    }

    @Test
    @DisplayName(
            "GIVEN the property off and a bearer NDJSON stream with a token of 3 seconds WHEN the token has expired SHOULD keep the stream open and delivering")
    void given_property_off_when_bearer_token_expires_should_keep_ndjson_stream_open() {
        STUB.accessTokenLifetime(Duration.ofSeconds(3));
        String token = STUB.accessToken("alice");
        StringBuffer text = new StringBuffer();
        AtomicBoolean completed = new AtomicBoolean();
        Disposable subscription = client.get()
                .uri("/v1/watch/x")
                .headers(h -> h.setBearerAuth(token))
                .header(HttpHeaders.ACCEPT, "application/x-ndjson")
                .exchangeToFlux(r -> r.body(BodyExtractors.toDataBuffers()))
                .map(buffer -> {
                    String chunk = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return chunk;
                })
                .subscribe(text::append, e -> completed.set(true), () -> completed.set(true));
        try {
            awaitTrue(() -> count(text) >= 1);

            // Past exp (the decoder's clock skew only matters at admission).
            long expired = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            awaitTrue(() -> System.nanoTime() > expired);
            int seen = count(text);
            awaitTrue(() -> count(text) > seen);

            assertThat(completed.get()).isFalse();
            assertThat(text.toString()).doesNotContain("token expired");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    @DisplayName(
            "GIVEN the property off and a bearer GraphQL subscription with a token of 3 seconds WHEN the token has expired SHOULD keep it open and let a new operation on the socket run")
    void given_property_off_when_bearer_token_expires_should_keep_graphql_subscription_open() throws Exception {
        STUB.accessTokenLifetime(Duration.ofSeconds(3));
        String token = STUB.accessToken("alice");
        List<String> received = new CopyOnWriteArrayList<>();
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        outbound.tryEmitNext("{\"type\":\"connection_init\"}");
        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public List<String> getSubProtocols() {
                return List.of("graphql-transport-ws");
            }

            @Override
            public Mono<Void> handle(WebSocketSession session) {
                return Mono.when(
                        session.send(outbound.asFlux().map(session::textMessage)),
                        session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(received::add)
                                .then());
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        Disposable connection = new ReactorNettyWebSocketClient()
                .execute(
                        URI.create("ws://localhost:" + BffRefreshSupport.port(gateway) + "/graphql/ws"),
                        headers,
                        handler)
                .subscribe(v -> {}, e -> {});
        try {
            awaitTrue(() -> received.stream().anyMatch(f -> f.contains("connection_ack")));
            outbound.tryEmitNext(subscribe("1", "subscription { userEvents(topic: \"alice\") { id payload } }"));

            // Past exp (the decoder's clock skew only matters at admission).
            Thread.sleep(4500);
            outbound.tryEmitNext(subscribe("2", "query { __typename }"));
            awaitTrue(() -> received.stream().anyMatch(f -> f.contains("\"id\":\"2\"") && f.contains("Query")));
            publish("user:alice", "{\"n\":7}");
            awaitTrue(() -> received.stream().anyMatch(f -> f.contains("\"n\":7")));

            assertThat(received).noneMatch(f -> f.contains("token expired"));
        } finally {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    private static String subscribe(String id, String query) {
        return "{\"id\":\"" + id + "\",\"type\":\"subscribe\",\"payload\":{\"query\":\"" + query.replace("\"", "\\\"")
                + "\"}}";
    }

    private static void publish(String topic, String dataJson) {
        client.post()
                .uri("/push/publish")
                .headers(h -> h.setBearerAuth(STUB.accessToken("publisher")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));
    }

    private static int count(StringBuffer text) {
        String all = text.toString();
        int count = 0;
        for (int i = all.indexOf("\"text\""); i >= 0; i = all.indexOf("\"text\"", i + 1)) count++;
        return count;
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
