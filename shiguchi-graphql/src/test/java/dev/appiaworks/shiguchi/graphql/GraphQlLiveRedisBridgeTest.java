package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.redis.RedisPushBroker;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

@DisplayName("Live invalidations across two instances over a real Redis")
class GraphQlLiveRedisBridgeTest {

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    @BeforeAll
    static void startRedis() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(port).build();
        redis.start();
        connections = new LettuceConnectionFactory("localhost", port);
        connections.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(connections);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (connections != null) connections.destroy();
        if (redis != null) redis.stop();
    }

    @Test
    @DisplayName("GIVEN two gateways on one Redis WHEN a mutation invalidates on A SHOULD signal B and A once each")
    void given_twoGatewaysOnRedis_when_mutationOnA_should_signalBoth() throws Exception {
        RedisPushBroker brokerA = new RedisPushBroker(template, "live:");
        RedisPushBroker brokerB = new RedisPushBroker(template, "live:");
        GraphQlLiveInvalidations a = new GraphQlLiveInvalidations();
        GraphQlLiveInvalidations b = new GraphQlLiveInvalidations();
        GraphQlLiveCursusBridge bridgeA = new GraphQlLiveCursusBridge(a, brokerA, "a");
        GraphQlLiveCursusBridge bridgeB = new GraphQlLiveCursusBridge(b, brokerB, "b");
        bridgeA.afterPropertiesSet();
        bridgeB.afterPropertiesSet();
        List<Set<String>> seenA = new CopyOnWriteArrayList<>();
        List<Set<String>> seenB = new CopyOnWriteArrayList<>();
        a.signals().subscribe(seenA::add);
        b.signals().subscribe(seenB::add);
        try {
            // a Pub/Sub subscription takes a moment to register on the server: publish until B hears
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            int calls = 0;
            while (seenB.isEmpty() && System.nanoTime() < deadline) {
                a.invalidate(Set.of("shop.Orders"));
                calls++;
                Thread.sleep(200);
            }

            assertThat(seenB).isNotEmpty().allMatch(s -> s.equals(Set.of("shop.Orders")));
            // A never hears its own relay back: exactly one local signal per invalidate call
            Thread.sleep(500);
            assertThat(seenA).hasSize(calls);
        } finally {
            bridgeA.destroy();
            bridgeB.destroy();
            brokerA.destroy();
            brokerB.destroy();
        }
    }
}
