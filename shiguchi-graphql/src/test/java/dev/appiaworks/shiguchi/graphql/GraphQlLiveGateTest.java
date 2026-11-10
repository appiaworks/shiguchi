package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@DisplayName("Live re-execution gate and meters")
class GraphQlLiveGateTest {

    @Test
    @DisplayName("GIVEN a gate of one WHEN two runs start SHOULD run the second only after the first ends")
    void given_gateOfOne_when_twoRunsStart_should_runSecondAfterFirst() {
        GraphQlLiveGate gate = new GraphQlLiveGate(1);
        Sinks.One<String> first = Sinks.one();
        java.util.List<String> done = new java.util.concurrent.CopyOnWriteArrayList<>();

        gate.limit(first.asMono()).subscribe(done::add);
        gate.limit(Mono.just("second")).subscribe(done::add);

        assertThat(done).isEmpty();
        assertThat(gate.waiting()).isEqualTo(1);

        first.tryEmitValue("first");

        assertThat(done).containsExactly("first", "second");
        assertThat(gate.waiting()).isZero();
    }

    @Test
    @DisplayName("GIVEN a waiting run WHEN it is cancelled SHOULD leave the queue and free no place early")
    void given_waitingRun_when_cancelled_should_leaveQueue() {
        GraphQlLiveGate gate = new GraphQlLiveGate(1);
        Sinks.One<String> first = Sinks.one();
        gate.limit(first.asMono()).subscribe();
        var waiting = gate.limit(Mono.just("x")).subscribe();

        waiting.dispose();

        assertThat(gate.waiting()).isZero();
        first.tryEmitValue("done");
        assertThat(gate.limit(Mono.just("y")).block(Duration.ofSeconds(1))).isEqualTo("y");
    }

    @Test
    @DisplayName(
            "GIVEN a registry WHEN invalidations arrive SHOULD count signals by source and expose the stream gauge")
    void given_registry_when_invalidationsArrive_should_countBySource() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GraphQlLiveStreams streams = new GraphQlLiveStreams(5, 10);
        GraphQlLiveInvalidations invalidations = new GraphQlLiveInvalidations();
        invalidations.metrics(new GraphQlLiveMetrics(meters, streams::active, () -> 0));
        streams.acquire("ann");

        invalidations.invalidate(Set.of("a.B"));
        invalidations.receive(Set.of("a.B"), "upstream");
        invalidations.receive(Set.of("a.B"));

        assertThat(meters.get("shiguchi.graphql.live.signals")
                        .tag("source", "mutation")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("shiguchi.graphql.live.signals")
                        .tag("source", "upstream")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("shiguchi.graphql.live.signals")
                        .tag("source", "remote")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("shiguchi.graphql.live.streams").gauge().value()).isEqualTo(1);
    }
}
