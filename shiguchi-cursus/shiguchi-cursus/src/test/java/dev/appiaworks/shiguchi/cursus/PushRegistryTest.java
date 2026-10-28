package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("PushRegistry")
class PushRegistryTest {

    private static final Topic ROOM = Topic.parse("room:x");
    private static final Topic TICKER = Topic.parse("ticker:btc");

    private final PushProperties properties = properties();
    private final PushConnections connections = new PushConnections();
    private PushRegistry registry;

    private static PushProperties properties() {
        PushProperties p = new PushProperties();
        p.setInstanceId("node-a");
        PushProperties.Namespace room = new PushProperties.Namespace();
        room.setPresence(true);
        p.getNamespaces().put("room", room);
        p.getNamespaces().put("ticker", new PushProperties.Namespace());
        return p;
    }

    @AfterEach
    void close() {
        if (registry != null) registry.close();
    }

    /** The memory store behind a delegate whose registry writes take half a second. */
    private static class SlowStore implements PushStore {
        private final MemoryPushStore memory = new MemoryPushStore();

        @Override
        public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
            return memory.append(event, historySize, historyTtl);
        }

        @Override
        public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
            return memory.range(topic, afterId, limit);
        }

        @Override
        public int compareIds(String a, String b) {
            return memory.compareIds(a, b);
        }

        @Override
        public long historyLength(Topic topic) {
            return memory.historyLength(topic);
        }

        @Override
        public Capabilities capabilities() {
            return memory.capabilities();
        }

        @Override
        public boolean connectionRegistry() {
            return true;
        }

        @Override
        public Mono<Void> registerConnections(List<ConnectionRecord> records, Duration ttl) {
            return Mono.fromRunnable(() -> sleep(500)).then(memory.registerConnections(records, ttl));
        }
    }

    /** A store with no registry: every registry method keeps its default. */
    private static final class DisabledStore extends SlowStore {
        @Override
        public boolean connectionRegistry() {
            return false;
        }
    }

    private PushRegistry registryOver(PushStore store) {
        registry = new PushRegistry(properties, store, connections);
        return registry;
    }

    @Test
    @DisplayName("GIVEN a store that writes slowly WHEN a connection opens and subscribes SHOULD return at once")
    void given_slowStore_when_connectionOpens_should_returnAtOnce() {
        registryOver(new SlowStore());

        long start = System.nanoTime();
        PushConnections.Live live = connections.open("sse", null);
        live.topics().add(ROOM);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMs).isLessThan(100);
    }

    @Test
    @DisplayName(
            "GIVEN a registry WHEN a connection opens and subscribes SHOULD list its row with topics, device and instance")
    void given_openConnection_when_subscribed_should_storeRow() {
        MemoryPushStore store = new MemoryPushStore();
        registryOver(store);

        PushConnections.Live live = connections.open("sse", null);
        live.deviceId("phone-1");
        live.topics().add(ROOM);
        live.topics().add(TICKER);

        awaitTrue(() -> {
            var rows = store.connections(null, 10).collectList().block();
            return rows.size() == 1 && rows.get(0).topics().size() == 2;
        });
        PushStore.ConnectionRecord row = store.connections(null, 10).blockFirst();
        assertThat(row.id()).isEqualTo(live.id());
        assertThat(row.instance()).isEqualTo("node-a");
        assertThat(row.deviceId()).isEqualTo("phone-1");
        assertThat(row.topics()).containsExactly("room:x", "ticker:btc");
        assertThat(row.presenceTopics()).containsExactly("room:x");
    }

    @Test
    @DisplayName("GIVEN a registered connection WHEN it closes SHOULD remove its row")
    void given_registeredConnection_when_closed_should_removeRow() {
        MemoryPushStore store = new MemoryPushStore();
        registryOver(store);
        PushConnections.Live live = connections.open("sse", null);
        live.topics().add(ROOM);
        awaitTrue(() -> store.connections(null, 10).count().block() == 1);

        connections.close(live);

        awaitTrue(() -> store.connections(null, 10).count().block() == 0);
    }

    @Test
    @DisplayName(
            "GIVEN a presence namespace WHEN connections subscribe SHOULD answer count, members and hasSubscribers")
    void given_presenceNamespace_when_subscribed_should_indexTopic() {
        MemoryPushStore store = new MemoryPushStore();
        registryOver(store);
        PushConnections.Live a = connections.open("sse", null);
        PushConnections.Live b = connections.open("ws", null);
        a.topics().add(ROOM);
        b.topics().add(ROOM);

        awaitTrue(() -> registry.count(ROOM).block() == 2);

        assertThat(registry.hasSubscribers(ROOM).block()).isTrue();
        assertThat(registry.hasSubscribers(Topic.parse("room:y")).block()).isFalse();
        assertThat(registry.subscribers(ROOM, 0, 10).block())
                .extracting(PushConnections.Info::id)
                .containsExactlyInAnyOrder(a.id(), b.id());
    }

    @Test
    @DisplayName("GIVEN a namespace without presence WHEN counting SHOULD count only this instance's connections")
    void given_plainNamespace_when_counted_should_countLocalOnly() {
        MemoryPushStore store = new MemoryPushStore();
        registryOver(store);
        // A row of another instance listening to the same topic must not be counted.
        store.registerConnections(
                        List.of(new PushStore.ConnectionRecord(
                                "remote",
                                "node-b",
                                null,
                                "sse",
                                null,
                                java.time.Instant.now(),
                                List.of("ticker:btc"),
                                List.of("ticker:btc"))),
                        Duration.ofMinutes(1))
                .block();
        connections.open("sse", null).topics().add(TICKER);

        assertThat(registry.count(TICKER).block()).isEqualTo(1);
        assertThat(registry.subscribers(TICKER, 0, 10).block()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a store without a registry WHEN listing SHOULD return this instance's connections only")
    void given_storeWithoutRegistry_when_listed_should_returnLocalOnly() {
        PushStore plain = new DisabledStore();
        registryOver(plain);
        PushConnections.Live live = connections.open("sse", null);
        live.topics().add(ROOM);

        assertThat(registry.enabled()).isFalse();
        assertThat(registry.list(null, 10).block())
                .extracting(PushConnections.Info::id)
                .containsExactly(live.id());
        assertThat(registry.count(ROOM).block()).isEqualTo(1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 10s");
            sleep(10);
        }
    }
}
