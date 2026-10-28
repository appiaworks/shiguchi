package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushStore.ConnectionRecord;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import redis.embedded.RedisServer;

@DisplayName("RedisPushStore connection registry (real Redis)")
class RedisPushStoreRegistryTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private RedisPushStore store;
    private final Topic room = Topic.parse("chat:room1");
    private final Topic other = Topic.parse("chat:room2");

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

    private static ConnectionRecord rec(String id, String principal, int minute, List<String> presence) {
        return new ConnectionRecord(
                id, "i1", principal, "ws", "dev-" + id, T0.plusSeconds(minute * 60L), presence, presence);
    }

    @Test
    @DisplayName("GIVEN the Redis store WHEN asked SHOULD keep a registry")
    void given_redisStore_when_asked_should_keepRegistry() {
        assertThat(store.connectionRegistry()).isTrue();
    }

    @Test
    @DisplayName("GIVEN connections of two principals WHEN listed by principal SHOULD return only theirs, oldest first")
    void given_twoPrincipals_when_listedByPrincipal_should_returnTheirsOldestFirst() {
        store.registerConnections(
                        List.of(
                                rec("c2", "ann", 2, List.of()),
                                rec("c1", "ann", 1, List.of()),
                                rec("c3", "bob", 3, List.of())),
                        TTL)
                .block();

        List<ConnectionRecord> ann = store.connections("ann", 10).collectList().block();

        assertThat(ann).extracting(ConnectionRecord::id).containsExactly("c1", "c2");
        assertThat(ann.get(0))
                .isEqualTo(new ConnectionRecord(
                        "c1", "i1", "ann", "ws", "dev-c1", T0.plusSeconds(60), List.of(), List.of()));
    }

    @Test
    @DisplayName("GIVEN several connections WHEN listing all with a limit SHOULD return the oldest up to the limit")
    void given_severalConnections_when_listingAllWithLimit_should_returnOldestUpToLimit() {
        store.registerConnections(
                        List.of(
                                rec("c3", "bob", 3, List.of()),
                                rec("c1", "ann", 1, List.of()),
                                rec("c2", "ann", 2, List.of())),
                        TTL)
                .block();

        assertThat(store.connections(null, 2).collectList().block())
                .extracting(ConnectionRecord::id)
                .containsExactly("c1", "c2");
    }

    @Test
    @DisplayName("GIVEN connections on a presence topic WHEN paged and counted SHOULD page in order and count all")
    void given_presenceTopic_when_pagedAndCounted_should_pageInOrderAndCountAll() {
        store.registerConnections(
                        List.of(
                                rec("c1", "ann", 1, List.of(room.toString())),
                                rec("c2", "bob", 2, List.of(room.toString())),
                                rec("c3", "cat", 3, List.of(room.toString())),
                                rec("c4", "dan", 4, List.of(other.toString()))),
                        TTL)
                .block();

        assertThat(store.connectionsOn(room, 1, 1).collectList().block())
                .extracting(ConnectionRecord::id)
                .containsExactly("c2");
        assertThat(store.countConnectionsOn(room).block()).isEqualTo(3L);
        assertThat(store.countConnectionsOn(Topic.parse("chat:none")).block()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a registered connection WHEN registered again with other topics SHOULD replace its topics and index")
    void given_registeredConnection_when_registeredAgain_should_replaceTopicsAndIndex() {
        store.registerConnections(List.of(rec("c1", "ann", 1, List.of(room.toString()))), TTL)
                .block();
        store.registerConnections(List.of(rec("c1", "ann", 1, List.of(other.toString()))), TTL)
                .block();

        assertThat(store.countConnectionsOn(room).block()).isZero();
        assertThat(store.countConnectionsOn(other).block()).isEqualTo(1L);
        assertThat(store.connections("ann", 10).collectList().block())
                .singleElement()
                .satisfies(r -> assertThat(r.topics()).containsExactly(other.toString()));
    }

    @Test
    @DisplayName("GIVEN a registered connection WHEN unregistered SHOULD vanish from every listing")
    void given_registeredConnection_when_unregistered_should_vanishFromListings() {
        store.registerConnections(List.of(rec("c1", "ann", 1, List.of(room.toString()))), TTL)
                .block();

        store.unregisterConnection("c1").block();

        assertThat(store.connections("ann", 10).collectList().block()).isEmpty();
        assertThat(store.connections(null, 10).collectList().block()).isEmpty();
        assertThat(store.countConnectionsOn(room).block()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a short ttl WHEN it elapses without a rewrite SHOULD lapse the row, and a rewrite SHOULD keep it")
    void given_shortTtl_when_elapsed_should_lapseUnlessRewritten() throws InterruptedException {
        store.registerConnections(
                        List.of(
                                rec("gone", "ann", 1, List.of(room.toString())),
                                rec("kept", "ann", 2, List.of(room.toString()))),
                        Duration.ofMillis(400))
                .block();
        store.registerConnections(List.of(rec("kept", "ann", 2, List.of(room.toString()))), TTL)
                .block();

        Thread.sleep(700);

        assertThat(store.connections("ann", 10).collectList().block())
                .extracting(ConnectionRecord::id)
                .containsExactly("kept");
        assertThat(store.countConnectionsOn(room).block()).isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "GIVEN a row with a peer address WHEN read by id SHOULD return it with the address, and nothing for an unknown id")
    void given_peerAddress_when_readById_should_returnTheRow() {
        store.registerConnections(
                        List.of(new ConnectionRecord(
                                "cp", "i1", "ann", "ws", null, T0, List.of(), List.of(), "10.0.0.7:9090")),
                        TTL)
                .block();

        ConnectionRecord found = store.connection("cp").block();
        assertThat(found).isNotNull();
        assertThat(found.peerAddress()).isEqualTo("10.0.0.7:9090");
        assertThat(found.instance()).isEqualTo("i1");
        assertThat(store.connection("missing").block()).isNull();
        assertThat(store.connections("ann", 10).collectList().block())
                .extracting(ConnectionRecord::peerAddress)
                .containsExactly("10.0.0.7:9090");
    }
}
