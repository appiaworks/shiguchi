package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The principal claim on the Boot 4 gateway over a real port (mirrors
 * {@code PushPrincipalClaimEndToEndTest} in spring-boot3/shiguchi-cursus-starter): with Spring Boot's
 * {@code principal-claim-name} set to {@code preferred_username}, the HTTP chain (SSE) and a
 * WebSocket {@code connect} frame name the caller by that claim, so {@code PRINCIPAL_MATCH} on
 * {@code user} accepts {@code user:<preferred_username>} and refuses {@code user:<sub>}; a token
 * without the claim is refused with 401 on both. The {@code acct} namespace matches on its own
 * {@code principal-claim} ({@code account.id}) instead.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "spring.security.oauth2.resourceserver.jwt.principal-claim-name=preferred_username",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.user.auth=PRINCIPAL_MATCH",
            "shiguchi.cursus.namespaces.user.publish-auth=NONE",
            "shiguchi.cursus.namespaces.acct.auth=PRINCIPAL_MATCH",
            "shiguchi.cursus.namespaces.acct.principal-claim=account.id",
            "shiguchi.cursus.namespaces.acct.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push principal claim end to end (Boot 4)")
class PushPrincipalClaimEndToEndTest {

    static final String ALICE_TOKEN = "alice-token";
    static final String NO_USERNAME_TOKEN = "no-username-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> switch (token) {
                case ALICE_TOKEN ->
                    Mono.just(jwt(Map.of("preferred_username", "alice", "account", Map.of("id", "acct-7"))));
                case NO_USERNAME_TOKEN -> Mono.just(jwt(Map.of()));
                default -> Mono.error(new BadJwtException("unknown token"));
            };
        }

        private static Jwt jwt(Map<String, Object> claims) {
            Jwt.Builder jwt = Jwt.withTokenValue("t")
                    .header("alg", "none")
                    .subject("f81d4fae")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600));
            claims.forEach(jwt::claim);
            return jwt.build();
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
    private URI wsUri;

    @BeforeEach
    void client() {
        String port = environment.getRequiredProperty("local.server.port");
        client = WebClient.create("http://localhost:" + port);
        wsUri = URI.create("ws://localhost:" + port + "/push/ws");
    }

    @Test
    @DisplayName(
            "GIVEN principal-claim-name preferred_username WHEN a caller subscribes over SSE SHOULD receive user:<preferred_username> and be refused user:<sub>")
    void given_principalClaimName_when_subscribingOverSse_should_matchPreferredUsername() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable sub = subscribe("user:alice", ALICE_TOKEN, frames);
        try {
            publish("user:alice", "{\"n\":1}");
            awaitTrue(() -> !frames.isEmpty());
            assertThat(frames.get(0).data()).contains("\"n\":1");
        } finally {
            sub.dispose();
        }

        assertThat(status("/push/subscribe?topic=user:f81d4fae", ALICE_TOKEN)).isEqualTo(403);
    }

    @Test
    @DisplayName(
            "GIVEN principal-claim-name preferred_username WHEN a caller connects over WebSocket SHOULD subscribe to user:<preferred_username> and be refused user:<sub>")
    void given_principalClaimName_when_subscribingOverWebSocket_should_matchPreferredUsername() {
        try (Socket socket = new Socket(wsUri)) {
            socket.await("connected"); // sent on open, before any token is checked
            socket.send("{\"type\":\"connect\",\"token\":\"" + ALICE_TOKEN + "\"}");
            assertThat(socket.await("connected", 2).path("authenticated").asBoolean())
                    .isTrue();

            socket.send("{\"type\":\"sub\",\"topics\":[\"user:alice\"]}");
            assertThat(socket.await("subscribed").path("topic").asText()).isEqualTo("user:alice");

            socket.send("{\"type\":\"sub\",\"topics\":[\"user:f81d4fae\"]}");
            assertThat(socket.await("error").path("code").asText()).isEqualTo("forbidden");
        }
    }

    @Test
    @DisplayName(
            "GIVEN principal-claim-name preferred_username WHEN the token lacks that claim SHOULD refuse it with 401")
    void given_principalClaimName_when_tokenLacksClaim_should_refuse() {
        assertThat(status("/push/subscribe?topic=user:f81d4fae", NO_USERNAME_TOKEN))
                .isEqualTo(401);

        try (Socket socket = new Socket(wsUri)) {
            socket.send("{\"type\":\"connect\",\"token\":\"" + NO_USERNAME_TOKEN + "\"}");
            assertThat(socket.await("error").path("code").asText()).isEqualTo("unauthenticated");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a namespace principal-claim account.id WHEN a caller subscribes SHOULD match that claim, not the principal name")
    void given_namespacePrincipalClaim_when_subscribing_should_matchThatClaim() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        subscribe("acct:acct-7", ALICE_TOKEN, frames).dispose();

        assertThat(status("/push/subscribe?topic=acct:alice", ALICE_TOKEN)).isEqualTo(403);
    }

    private void publish(String topic, String dataJson) {
        String[] response =
                exchange("POST", "/push/publish", "{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}", null);
        assertThat(response[0]).as("status: %s", response[1]).isEqualTo("200");
    }

    private Disposable subscribe(String topic, String token, List<ServerSentEvent<String>> frames) {
        int before = metrics.connections("sse");
        Disposable d = client.get()
                .uri("/push/subscribe?topic=" + topic)
                .headers(h -> h.setBearerAuth(token))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null && !"close".equals(e.event()))
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections("sse") == before + 1);
        return d;
    }

    private int status(String path, String token) {
        return Integer.parseInt(exchange("GET", path, null, token)[0]);
    }

    private String[] exchange(String method, String path, String json, String token) {
        WebClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method))
                .uri(path)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                });
        return (json == null
                        ? spec
                        : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new String[] {String.valueOf(r.statusCode().value()), b}))
                .block(Duration.ofSeconds(30));
    }

    private static final class Socket implements AutoCloseable {
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        Socket(URI uri) {
            connection = new ReactorNettyWebSocketClient()
                    .execute(uri, new HttpHeaders(), session -> {
                        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(t -> frames.add(parse(t)))
                                .then();
                        return Mono.zip(send, receive).then();
                    })
                    .subscribe();
        }

        void send(String frame) {
            outbound.tryEmitNext(frame);
        }

        JsonNode await(String type) {
            return await(type, 1);
        }

        JsonNode await(String type, int nth) {
            awaitTrue(() -> frames.stream()
                            .filter(f -> type.equals(f.path("type").asText()))
                            .count()
                    >= nth);
            return frames.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .skip(nth - 1)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new AssertionError(json, e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }
}
