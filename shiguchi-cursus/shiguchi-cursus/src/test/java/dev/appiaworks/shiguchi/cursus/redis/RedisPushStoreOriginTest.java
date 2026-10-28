package dev.appiaworks.shiguchi.cursus.redis;

import dev.appiaworks.shiguchi.cursus.PushOriginStoreContract;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

@DisplayName("RedisPushStore: origin attribute and session skip (real Redis)")
class RedisPushStoreOriginTest {

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
    @DisplayName(
            "GIVEN events stamped with origins WHEN replayed and a session declaring one starts SHOULD keep push.origin through range and move the cursor over the leading own-origin run")
    void given_stamped_events_when_replayed_and_session_starts_should_keep_origin_and_skip_leading_run() {
        PushOriginStoreContract.verify(new RedisPushStore(template, "test:origin:"));
    }
}
