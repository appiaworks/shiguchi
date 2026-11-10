package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.MemoryPushBroker;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Live invalidations across instances through the Cursus broker")
class GraphQlLiveCursusBridgeTest {

    @Test
    @DisplayName("GIVEN two instances on one broker WHEN one invalidates SHOULD reach the other once and itself once")
    void given_twoInstances_when_oneInvalidates_should_reachTheOtherOnce() throws Exception {
        MemoryPushBroker broker = new MemoryPushBroker();
        GraphQlLiveInvalidations a = new GraphQlLiveInvalidations();
        GraphQlLiveInvalidations b = new GraphQlLiveInvalidations();
        GraphQlLiveCursusBridge bridgeA = new GraphQlLiveCursusBridge(a, broker, "a");
        GraphQlLiveCursusBridge bridgeB = new GraphQlLiveCursusBridge(b, broker, "b");
        bridgeA.afterPropertiesSet();
        bridgeB.afterPropertiesSet();
        List<Set<String>> seenA = new CopyOnWriteArrayList<>();
        List<Set<String>> seenB = new CopyOnWriteArrayList<>();
        a.signals().subscribe(seenA::add);
        b.signals().subscribe(seenB::add);

        a.invalidate(Set.of("shop.Orders"));

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (seenB.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
        Thread.sleep(200);
        assertThat(seenA).containsExactly(Set.of("shop.Orders"));
        assertThat(seenB).containsExactly(Set.of("shop.Orders"));
        bridgeA.destroy();
        bridgeB.destroy();
    }
}
