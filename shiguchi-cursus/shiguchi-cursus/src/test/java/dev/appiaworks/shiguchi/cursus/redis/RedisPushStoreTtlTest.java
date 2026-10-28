package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

/**
 * Per-event history-ttl on a busy topic. The age trim needs {@code XTRIM MINID} (Redis 6.2+): the
 * embedded Redis is 5.0 on Windows and 6.2 on Linux/macOS, so the MINID cases skip on the former and
 * run on CI.
 */
@DisplayName("RedisPushStore: history-ttl expires single events on a busy topic (real Redis)")
class RedisPushStoreTtlTest {

    private static final Duration DEADLINE = Duration.ofSeconds(15);
    private static final Duration TTL = Duration.ofSeconds(2);

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

    private static PushEvent append(RedisPushStore store, Topic topic, int historySize, Duration ttl) {
        return store.append(new PushEvent(topic, null, "{}".getBytes(), null, null, null), historySize, ttl)
                .block(DEADLINE);
    }

    private static List<String> ids(RedisPushStore store, Topic topic) {
        return store.range(topic, null, 100).map(PushEvent::id).collectList().block(DEADLINE);
    }

    @Test
    @DisplayName(
            "GIVEN a busy topic on Redis 6.2+ WHEN entries outlive the ttl SHOULD drop them and report the last one")
    void given_busyTopic_when_entriesOutliveTtl_should_dropThemAndReportWatermark() throws InterruptedException {
        RedisPushStore store = new RedisPushStore(template, "test:busy:");
        Assumptions.assumeTrue(store.ageTrimEnabled(), "needs Redis 6.2+ (XTRIM MINID); runs on CI");
        Topic topic = Topic.parse("ttl:busy");
        PushEvent e1 = append(store, topic, 100, TTL);
        PushEvent e2 = append(store, topic, 100, TTL);
        // keep the topic busy past the ttl: the whole-key expiry alone never fires
        PushEvent last = null;
        for (int i = 0; i < 6; i++) {
            Thread.sleep(500);
            last = append(store, topic, 100, TTL);
        }

        List<String> kept = ids(store, topic);

        assertThat(kept).doesNotContain(e1.id(), e2.id()).contains(last.id());
        String watermark = store.trimmedThrough(topic).block(DEADLINE);
        assertThat(watermark).isNotNull();
        assertThat(store.compareIds(watermark, e2.id())).isGreaterThanOrEqualTo(0);
        assertThat(kept)
                .allSatisfy(id -> assertThat(store.compareIds(id, watermark)).isPositive());
    }

    @Test
    @DisplayName("GIVEN age and size limits on Redis 6.2+ WHEN both apply SHOULD keep the size trim working")
    void given_ageAndSizeLimits_when_appending_should_stillTrimBySize() {
        RedisPushStore store = new RedisPushStore(template, "test:both:");
        Assumptions.assumeTrue(store.ageTrimEnabled(), "needs Redis 6.2+ (XTRIM MINID); runs on CI");
        Topic topic = Topic.parse("ttl:both");
        PushEvent[] events = new PushEvent[5];
        for (int i = 0; i < 5; i++) events[i] = append(store, topic, 3, TTL);

        assertThat(store.historyLength(topic)).isEqualTo(3);
        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isEqualTo(events[1].id());
    }

    @Test
    @DisplayName(
            "GIVEN Redis without MINID WHEN a topic stays busy SHOULD keep the old entries (whole-key expiry only)")
    void given_noMinIdSupport_when_topicStaysBusy_should_keepOldEntries() throws InterruptedException {
        RedisPushStore store = new RedisPushStore(template, "test:legacy:", false, Boolean.FALSE);
        Topic topic = Topic.parse("ttl:legacy");
        PushEvent first = append(store, topic, 100, TTL);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(500);
            append(store, topic, 100, TTL);
        }

        assertThat(store.ageTrimEnabled()).isFalse();
        assertThat(ids(store, topic)).contains(first.id());
        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isNull();
    }

    @Test
    @DisplayName("GIVEN version strings WHEN parsing SHOULD accept 6.2 and later only")
    void given_versionStrings_when_checkingMinIdSupport_should_acceptSixTwoAndLater() {
        assertThat(RedisPushStore.versionAtLeast62("5.0.14")).isFalse();
        assertThat(RedisPushStore.versionAtLeast62("6.0.20")).isFalse();
        assertThat(RedisPushStore.versionAtLeast62("6.2.0")).isTrue();
        assertThat(RedisPushStore.versionAtLeast62("7.4.1")).isTrue();
        assertThat(RedisPushStore.versionAtLeast62("255.255.255")).isTrue();
        assertThat(RedisPushStore.versionAtLeast62("garbage")).isFalse();
        assertThat(RedisPushStore.versionAtLeast62(null)).isFalse();
    }
}
