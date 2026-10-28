package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushException;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

@DisplayName("RedisPushStore: caller-chosen event ids (real Redis)")
class RedisPushStoreExplicitIdTest {

    private static final Duration HOUR = Duration.ofHours(1);

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private RedisPushStore store;
    private final Topic topic = Topic.parse("ticks:a");

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

    @BeforeEach
    void setup(TestInfo test) {
        store = new RedisPushStore(
                template, "test:" + test.getTestMethod().orElseThrow().getName() + ":");
    }

    private PushEvent event() {
        return new PushEvent(topic, null, "{}".getBytes(), null, null, null);
    }

    @Test
    @DisplayName("GIVEN an explicit id above the head WHEN appended SHOULD keep it and order after the existing events")
    void given_explicitIdAboveHead_when_appended_should_keepItAndOrderAfterExisting() {
        PushEvent first = store.appendWithId(event(), 5, 10, HOUR).block();
        PushEvent second = store.appendWithId(event(), 9, 10, HOUR).block();

        assertThat(store.explicitIds()).isTrue();
        assertThat(first.id()).isEqualTo("5-0");
        assertThat(second.id()).isEqualTo("9-0");
        assertThat(store.range(topic, null, 10).map(PushEvent::id).collectList().block())
                .containsExactly("5-0", "9-0");
        assertThat(store.range(topic, "5-0", 10)
                        .map(PushEvent::id)
                        .collectList()
                        .block())
                .containsExactly("9-0");
    }

    @Test
    @DisplayName("GIVEN an explicit id at or below the head WHEN appended SHOULD be rejected with a conflict")
    void given_explicitIdAtOrBelowHead_when_appended_should_rejectWithConflict() {
        store.appendWithId(event(), 7, 10, HOUR).block();

        for (long id : new long[] {7, 3}) {
            assertThatThrownBy(() -> store.appendWithId(event(), id, 10, HOUR).block())
                    .isInstanceOfSatisfying(
                            PushException.class,
                            e -> assertThat(e.status().value()).isEqualTo(409));
        }
        assertThat(store.historyLength(topic)).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a small explicit id WHEN an engine append follows SHOULD continue above it")
    void given_smallExplicitId_when_engineAppendFollows_should_continueAboveIt() {
        PushEvent explicit = store.appendWithId(event(), 5, 10, HOUR).block();

        PushEvent engine = store.append(event(), 10, HOUR).block();

        assertThat(store.compareIds(explicit.id(), engine.id())).isNegative();
    }

    @Test
    @DisplayName(
            "GIVEN an engine id on the stream WHEN a smaller explicit id arrives SHOULD reject it, the stream stays clock-scale")
    void given_engineIdOnStream_when_smallerExplicitIdArrives_should_reject() {
        store.append(event(), 10, HOUR).block();

        assertThatThrownBy(() -> store.appendWithId(event(), 5, 10, HOUR).block())
                .isInstanceOf(PushException.class);
    }

    @Test
    @DisplayName(
            "GIVEN a history size of two WHEN three explicit ids are appended SHOULD keep no more than the newest entries")
    void given_historySizeTwo_when_threeAppended_should_trim() {
        for (long id = 1; id <= 3; id++)
            store.appendWithId(event(), id, 2, HOUR).block();

        assertThat(store.historyLength(topic)).isLessThanOrEqualTo(3);
        assertThat(store.headEvent(topic).block().id()).isEqualTo("3-0");
    }

    @Test
    @DisplayName(
            "GIVEN kept events WHEN reading the head event SHOULD return the newest one; empty for an unknown topic")
    void given_keptEvents_when_headEventRead_should_returnNewest() {
        store.appendWithId(event(), 4, 10, HOUR).block();
        store.appendWithId(event(), 8, 10, HOUR).block();

        assertThat(store.headEvent(topic).block().id()).isEqualTo("8-0");
        assertThat(store.headEvent(Topic.parse("ticks:none")).block()).isNull();
    }

    @Test
    @DisplayName("GIVEN a topic without history WHEN an explicit id is appended SHOULD keep the id and store nothing")
    void given_noHistory_when_explicitIdAppended_should_keepIdAndStoreNothing() {
        PushEvent stored = store.appendWithId(event(), 5, 0, null).block();

        assertThat(stored.id()).isEqualTo("5-0");
        assertThat(store.historyLength(topic)).isZero();
    }
}
