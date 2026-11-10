package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Live query limits and dependency matching")
class GraphQlLiveStreamsTest {

    @Test
    @DisplayName("GIVEN a principal at its limit WHEN it asks for another SHOULD be refused until one is released")
    void given_principalAtLimit_when_asksForAnother_should_beRefusedUntilReleased() {
        GraphQlLiveStreams streams = new GraphQlLiveStreams(2, 10);
        var first = streams.acquire("ann");
        streams.acquire("ann");

        assertThat(streams.acquire("ann")).isNull();
        assertThat(streams.acquire("bob")).isNotNull();

        first.release();
        first.release();
        assertThat(streams.acquire("ann")).isNotNull();
        assertThat(streams.acquire("ann")).isNull();
    }

    @Test
    @DisplayName("GIVEN the instance at its total WHEN another principal asks SHOULD be refused")
    void given_totalReached_when_anotherPrincipalAsks_should_beRefused() {
        GraphQlLiveStreams streams = new GraphQlLiveStreams(5, 2);
        streams.acquire("ann");
        streams.acquire("bob");

        assertThat(streams.acquire("cy")).isNull();
        assertThat(streams.active()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN service sets WHEN matched SHOULD intersect on a shared service or on the ALL marker")
    void given_serviceSets_when_matched_should_intersectOnSharedOrAll() {
        assertThat(GraphQlLiveDependencies.intersects(Set.of("a.S"), Set.of("a.S", "b.T")))
                .isTrue();
        assertThat(GraphQlLiveDependencies.intersects(Set.of("a.S"), Set.of("b.T")))
                .isFalse();
        assertThat(GraphQlLiveDependencies.intersects(Set.of("a.S"), Set.of(GraphQlLiveDependencies.ALL)))
                .isTrue();
        assertThat(GraphQlLiveDependencies.intersects(Set.of(GraphQlLiveDependencies.ALL), Set.of("b.T")))
                .isTrue();
    }
}
