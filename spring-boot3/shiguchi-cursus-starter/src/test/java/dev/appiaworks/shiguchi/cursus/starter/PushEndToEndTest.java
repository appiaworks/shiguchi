package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.time.Duration;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The push surface on the Boot 3 gateway over a real port: Server-Sent Events subscriptions fed by
 * HTTP publishes, several topics on one connection, resume from {@code Last-Event-ID} on an
 * {@code AT_LEAST_ONCE} namespace, the refusals (unknown namespace, bad topic, bad JSON, too
 * large, unauthenticated publisher), the client form of publishing, and the connection lifetime
 * ending the stream with a {@code close} event.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.namespaces.inbox.auth=AUTHENTICATED",
            "shiguchi.cursus.namespaces.chat.publish-auth=NONE",
            "shiguchi.cursus.namespaces.chat.client-publish=true",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=4s",
            "shiguchi.cursus.connection.lifetime-jitter=0",
            "shiguchi.cursus.connection.max-message-bytes=256"
        })
@DisplayName("Push over SSE end to end (Boot 3)")
class PushEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

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
    @DisplayName(
            "GIVEN an SSE subscriber on a topic WHEN three events are published SHOULD deliver them in order as id, event and data")
    void given_subscriber_when_published_should_deliverInOrder() {
        try (Subscriber sub = subscribe("ticker:btc")) {
            for (int i = 1; i <= 3; i++) {
                assertThat(publish("ticker:btc", "{\"price\":" + i + "}")
                                .statusCode()
                                .value())
                        .isEqualTo(200);
            }
            List<ServerSentEvent<String>> events = sub.awaitEvents(3);
            assertThat(events)
                    .extracting(ServerSentEvent::event)
                    .containsExactly("ticker:btc", "ticker:btc", "ticker:btc");
            assertThat(events)
                    .extracting(ServerSentEvent::data)
                    .containsExactly("{\"price\":1}", "{\"price\":2}", "{\"price\":3}");
            assertThat(events)
                    .extracting(ServerSentEvent::id)
                    .doesNotContainNull()
                    .isSorted();
        }
    }

    @Test
    @DisplayName(
            "GIVEN one connection over two topics WHEN each topic gets an event SHOULD deliver both under their topic names")
    void given_twoTopics_when_published_should_deliverBothWithTopicNames() {
        try (Subscriber sub = subscribe("ticker:btc", "ticker:eth")) {
            publish("ticker:eth", "\"e\"");
            publish("ticker:btc", "\"b\"");
            List<ServerSentEvent<String>> events = sub.awaitEvents(2);
            assertThat(events).extracting(ServerSentEvent::event).containsExactlyInAnyOrder("ticker:btc", "ticker:eth");
            assertThat(events).extracting(ServerSentEvent::data).containsExactlyInAnyOrder("\"b\"", "\"e\"");
        }
    }

    @Test
    @DisplayName(
            "GIVEN events kept on an AT_LEAST_ONCE topic WHEN a subscriber resumes with Last-Event-ID SHOULD replay what it missed then stream live events")
    void given_history_when_resumedWithLastEventId_should_replayThenLive() {
        String first = publishedId("orders:42", "{\"n\":1}");
        publish("orders:42", "{\"n\":2}");
        publish("orders:42", "{\"n\":3}");
        try (Subscriber sub = subscribe(Map.of("Last-Event-ID", first), "orders:42")) {
            List<ServerSentEvent<String>> replayed = sub.awaitEvents(2);
            assertThat(replayed).extracting(ServerSentEvent::data).containsExactly("{\"n\":2}", "{\"n\":3}");
            publish("orders:42", "{\"n\":4}");
            List<ServerSentEvent<String>> all = sub.awaitEvents(3);
            assertThat(all.get(2).data()).isEqualTo("{\"n\":4}");
            assertThat(all).extracting(ServerSentEvent::id).isSorted();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a namespace that lets clients publish WHEN a subscriber posts to the topic SHOULD deliver it to the other subscribers")
    void given_clientPublish_when_posted_should_deliver() {
        try (Subscriber sub = subscribe("chat:room1")) {
            ClientResponse r = client.post()
                    .uri("/push/topics/chat:room1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"text\":\"hi\"}")
                    .exchangeToMono(Mono::just)
                    .block(Duration.ofSeconds(30));
            assertThat(r.statusCode().value()).isEqualTo(200);
            assertThat(sub.awaitEvents(1).get(0).data()).isEqualTo("{\"text\":\"hi\"}");
        }
    }

    @Test
    @DisplayName("GIVEN the refusals WHEN a request is wrong SHOULD answer 404, 400, 413, 401 or 403 with a JSON error")
    void given_badRequests_when_sent_should_refuseWithStatusAndCode() {
        assertThat(status("/push/subscribe?topic=nope:x")).isEqualTo(404);
        assertThat(status("/push/subscribe?topic=orders")).isEqualTo(400);
        assertThat(status("/push/subscribe")).isEqualTo(400);
        assertThat(publish("nope:x", "1").statusCode().value()).isEqualTo(404);
        assertThat(publish("ticker:btc", "{not json").statusCode().value()).isEqualTo(400);
        assertThat(publish("ticker:btc", "\"" + "x".repeat(300) + "\"")
                        .statusCode()
                        .value())
                .isEqualTo(413);
        assertThat(publish("inbox:alice", "1").statusCode().value()).isEqualTo(401);
        assertThat(status("/push/subscribe?topic=inbox:alice")).isEqualTo(401);
        ClientResponse chat = client.post()
                .uri("/push/topics/ticker:btc")
                .bodyValue("1")
                .exchangeToMono(Mono::just)
                .block(Duration.ofSeconds(30));
        assertThat(chat.statusCode().value()).isEqualTo(403);
        String body = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"nope:x\",\"data\":1}")
                .exchangeToMono(res -> res.bodyToMono(String.class))
                .block(Duration.ofSeconds(30));
        assertThat(body).contains("\"error\":\"unknown_namespace\"");
    }

    @Test
    @DisplayName(
            "GIVEN the connection lifetime WHEN it elapses SHOULD end the stream with a close event and release the connection gauge")
    void given_lifetime_when_elapsed_should_closeAndReleaseGauge() {
        List<ServerSentEvent<String>> frames = client.get()
                .uri("/push/subscribe?topic=ticker:life")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null)
                .collectList()
                .block(Duration.ofSeconds(30));
        assertThat(frames).isNotEmpty();
        ServerSentEvent<String> last = frames.get(frames.size() - 1);
        assertThat(last.event()).isEqualTo("close");
        assertThat(last.data()).isEqualTo("{\"reason\":\"rebalance\"}");
        awaitConnections(0);
    }

    // ---- helpers ----

    private ClientResponse publish(String topic, String dataJson) {
        return client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .exchangeToMono(Mono::just)
                .block(Duration.ofSeconds(30));
    }

    private String publishedId(String topic, String dataJson) {
        String body = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        int at = body.indexOf("\"id\":\"") + 6;
        return body.substring(at, body.indexOf('"', at));
    }

    private int status(String path) {
        return client.get()
                .uri(path)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
    }

    private Subscriber subscribe(String... topics) {
        return subscribe(Map.of(), topics);
    }

    /** Opens the stream and waits until the gateway counts the connection, so a publish cannot race it. */
    private Subscriber subscribe(Map<String, String> headers, String... topics) {
        int before = metrics.connections();
        StringBuilder uri = new StringBuilder("/push/subscribe?");
        for (String t : topics) uri.append("topic=").append(t).append('&');
        Flux<ServerSentEvent<String>> stream = client.get()
                .uri(uri.toString())
                .headers(h -> headers.forEach(h::add))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE);
        Subscriber sub = new Subscriber(stream);
        awaitConnections(before + 1);
        return sub;
    }

    private void awaitConnections(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (metrics.connections() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "connections gauge stayed at " + metrics.connections() + ", wanted " + expected);
            }
            Thread.onSpinWait();
        }
    }

    private static final class Subscriber implements AutoCloseable {
        private final List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        private final Disposable connection;

        Subscriber(Flux<ServerSentEvent<String>> stream) {
            connection = stream.filter(e -> e.event() != null && !"close".equals(e.event()))
                    .subscribe(events::add);
        }

        List<ServerSentEvent<String>> awaitEvents(int count) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (events.size() < count) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("got " + events.size() + " events, wanted " + count + ": " + events);
                }
                Thread.onSpinWait();
            }
            return List.copyOf(events);
        }

        @Override
        public void close() {
            connection.dispose();
        }
    }
}
