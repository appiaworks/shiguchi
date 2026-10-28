package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import redis.embedded.RedisServer;

/**
 * Who is connected, across instances (Boot 4; mirrors {@code PushPresenceEndToEndTest} in
 * spring-boot3/shiguchi-cursus-starter): two gateway instances over one embedded Redis, each with an
 * instance id of its own. A subscriber on instance B shows up in instance A's connections view with
 * its instance and device, and in A's answers to {@code HasSubscribers} and {@code GetSubscribers}
 * on a {@code presence: true} namespace; when it goes away the count drops.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.instance-id=node-a",
            "shiguchi.cursus.broker=REDIS",
            "shiguchi.cursus.store=REDIS",
            "shiguchi.cursus.redis.key-prefix=e2e:presence:",
            "shiguchi.cursus.namespaces.room.publish-auth=NONE",
            "shiguchi.cursus.namespaces.room.presence=true",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push presence across two instances, end to end (Boot 4)")
class PushPresenceEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    /**
     * The extra instance gets event loops of its own: Boot's default {@code ReactorResourceFactory}
     * uses reactor-netty's global resources and disposes them when its context closes, which would
     * take the other instance's server down with it. Not a {@code @Configuration}: the gateway's
     * package scan would register it in every context, this test class's included.
     */
    static class OwnResources {
        @org.springframework.context.annotation.Bean
        org.springframework.http.client.ReactorResourceFactory reactorResourceFactory() {
            var factory = new org.springframework.http.client.ReactorResourceFactory();
            factory.setUseGlobalResources(false);
            return factory;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static RedisServer redis;
    private static int redisPort;
    private static ConfigurableApplicationContext second;
    private static WebClient secondClient;

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> redisPort);
    }

    @BeforeAll
    static void startRedisAndSecondInstance() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(redisPort).build();
        redis.start();
        second = instance();
        secondClient = WebClient.create(
                "http://localhost:" + second.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterAll
    static void stop() throws IOException {
        if (second != null) second.close();
        if (redis != null) redis.stop();
    }

    /** Instance B on the same Redis, booted the way an application would be. */
    private static ConfigurableApplicationContext instance() {
        return new SpringApplicationBuilder(TestApp.class, OwnResources.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=" + redisPort,
                        "shiguchi.ui.enabled=false",
                        "shiguchi.openapi.enabled=false",
                        "shiguchi.graphql.enabled=false",
                        "shiguchi.cursus.enabled=true",
                        "shiguchi.cursus.instance-id=node-b",
                        "shiguchi.cursus.broker=REDIS",
                        "shiguchi.cursus.store=REDIS",
                        "shiguchi.cursus.redis.key-prefix=e2e:presence:",
                        "shiguchi.cursus.namespaces.room.publish-auth=NONE",
                        "shiguchi.cursus.namespaces.room.presence=true",
                        "shiguchi.cursus.connection.keepalive=200ms",
                        "shiguchi.cursus.connection.max-lifetime=0")
                .run();
    }

    @Autowired
    private Environment environment;

    private WebClient client;

    @BeforeEach
    void client() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    @Test
    @DisplayName(
            "GIVEN an SSE subscriber with a device id on instance B WHEN instance A lists connections SHOULD show it with B's instance and the device")
    void given_subscriberOnB_when_aListsConnections_should_showInstanceAndDevice() {
        PushMetrics metricsB = second.getBean(PushMetrics.class);
        int before = metricsB.connections();
        Disposable sub = subscribeOnB("room:listed", "phone-7");
        try {
            awaitTrue(() -> metricsB.connections() == before + 1);

            awaitTrue(() -> connectionOf("room:listed") != null);
            JsonNode row = connectionOf("room:listed");
            assertThat(row.get("instance").asText()).isEqualTo("node-b");
            assertThat(row.get("deviceId").asText()).isEqualTo("phone-7");
            assertThat(row.get("transport").asText()).isEqualTo("sse");
        } finally {
            sub.dispose();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber on instance B WHEN instance A is asked who listens SHOULD answer true for its room only, list the member, and drop to zero when it cancels")
    void given_subscriberOnB_when_aAsked_should_answerPresenceThenDrop() {
        Disposable sub = subscribeOnB("room:x", "phone-9");
        try {
            awaitTrue(() -> hasSubscribers("room:x"));

            JsonNode has = post("/push/v1/has-subscribers", "{\"topics\":[\"room:x\",\"room:y\"]}");
            assertThat(has.get("subscribers").get("room:x").asBoolean()).isTrue();
            assertThat(has.get("subscribers").get("room:y").asBoolean()).isFalse();

            JsonNode members = get("/push/v1/subscribers?topic=room:x");
            assertThat(members.get("count").asLong()).isEqualTo(1);
            assertThat(members.get("subscribers")).hasSize(1);
            assertThat(members.get("subscribers").get(0).get("deviceId").asText())
                    .isEqualTo("phone-9");
            assertThat(members.get("subscribers").get(0).get("instance").asText())
                    .isEqualTo("node-b");
        } finally {
            sub.dispose();
        }

        awaitTrue(() -> !hasSubscribers("room:x"));
        assertThat(get("/push/v1/subscribers?topic=room:x").path("count").asLong())
                .isZero();
    }

    // ---- helpers ----

    private Disposable subscribeOnB(String topic, String device) {
        return secondClient
                .get()
                .uri("/push/subscribe?topic=" + topic)
                .header("X-Push-Device", device)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(x -> {}, e -> {});
    }

    /** The connection of instance A's admin view (cluster-wide) that listens to {@code topic}, or null. */
    private JsonNode connectionOf(String topic) {
        for (JsonNode c : get("/actuator/gateway-push/connections").get("connections")) {
            for (JsonNode t : c.get("topics")) if (t.asText().equals(topic)) return c;
        }
        return null;
    }

    private boolean hasSubscribers(String topic) {
        return post("/push/v1/has-subscribers", "{\"topics\":[\"" + topic + "\"]}")
                .get("subscribers")
                .path(topic)
                .asBoolean();
    }

    private JsonNode get(String path) {
        return json(client.get().uri(path).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30)));
    }

    private JsonNode post(String path, String body) {
        return json(client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .switchIfEmpty(Mono.just("{}"))
                .block(Duration.ofSeconds(30)));
    }

    private static JsonNode json(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new AssertionError(body, e);
        }
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
