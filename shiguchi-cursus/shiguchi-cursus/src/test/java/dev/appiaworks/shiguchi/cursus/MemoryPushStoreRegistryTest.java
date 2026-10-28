package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushStore.ConnectionRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MemoryPushStore connection registry")
class MemoryPushStoreRegistryTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    private final MemoryPushStore store = new MemoryPushStore();
    private final Topic room = Topic.parse("chat:room1");
    private final Topic other = Topic.parse("chat:room2");

    private static ConnectionRecord rec(String id, String principal, int minute, List<String> presence) {
        return new ConnectionRecord(
                id, "i1", principal, "ws", "dev-" + id, T0.plusSeconds(minute * 60L), presence, presence);
    }

    @Test
    @DisplayName("GIVEN the memory store WHEN asked SHOULD keep a registry")
    void given_memoryStore_when_asked_should_keepRegistry() {
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

        assertThat(store.connections("ann", 10).collectList().block())
                .extracting(ConnectionRecord::id)
                .containsExactly("c1", "c2");
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
        assertThat(store.countConnectionsOn(room).block()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a short ttl WHEN it elapses without a rewrite SHOULD lapse the row, and a rewrite SHOULD keep it")
    void given_shortTtl_when_elapsed_should_lapseUnlessRewritten() throws InterruptedException {
        store.registerConnections(
                        List.of(rec("gone", "ann", 1, List.of()), rec("kept", "ann", 2, List.of())),
                        Duration.ofMillis(300))
                .block();
        store.registerConnections(List.of(rec("kept", "ann", 2, List.of())), TTL)
                .block();

        Thread.sleep(500);

        assertThat(store.connections("ann", 10).collectList().block())
                .extracting(ConnectionRecord::id)
                .containsExactly("kept");
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
