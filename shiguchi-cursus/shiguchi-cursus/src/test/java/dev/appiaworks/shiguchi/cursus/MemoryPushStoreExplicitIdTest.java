package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory push store: caller-chosen event ids")
class MemoryPushStoreExplicitIdTest {

    private static final Duration HOUR = Duration.ofHours(1);

    private final Topic topic = Topic.parse("ticks:a");
    private final MemoryPushStore store = new MemoryPushStore();

    private PushEvent event() {
        return new PushEvent(topic, null, "{}".getBytes(), null, null, null);
    }

    @Test
    @DisplayName("GIVEN an explicit id above the head WHEN appended SHOULD keep it and order after the existing events")
    void given_explicitIdAboveHead_when_appended_should_keepItAndOrderAfterExisting() {
        PushEvent first = store.appendWithId(event(), 5, 10, HOUR).block();
        PushEvent second = store.appendWithId(event(), 9, 10, HOUR).block();

        assertThat(store.explicitIds()).isTrue();
        assertThat(first.id()).isEqualTo(MemoryPushStore.formatId(5));
        assertThat(second.id()).isEqualTo(MemoryPushStore.formatId(9));
        assertThat(store.compareIds(first.id(), second.id())).isNegative();
        List<PushEvent> kept = store.range(topic, null, 10).collectList().block();
        assertThat(kept).extracting(PushEvent::id).containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName("GIVEN an explicit id at or below the head WHEN appended SHOULD be rejected with a conflict")
    void given_explicitIdAtOrBelowHead_when_appended_should_rejectWithConflict() {
        store.appendWithId(event(), 7, 10, HOUR).block();

        for (long id : new long[] {7, 3}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> store.appendWithId(event(), id, 10, HOUR).block())
                    .isInstanceOfSatisfying(
                            PushException.class,
                            e -> assertThat(e.status().value()).isEqualTo(409));
        }
        assertThat(store.historyLength(topic)).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN an explicit id WHEN an engine append follows SHOULD continue above it")
    void given_explicitId_when_engineAppendFollows_should_continueAboveIt() {
        PushEvent explicit = store.appendWithId(event(), 5, 10, HOUR).block();

        PushEvent engine = store.append(event(), 10, HOUR).block();

        assertThat(store.compareIds(explicit.id(), engine.id())).isNegative();
    }

    @Test
    @DisplayName("GIVEN a history size of two WHEN three explicit ids are appended SHOULD keep the newest two")
    void given_historySizeTwo_when_threeAppended_should_trimToNewestTwo() {
        for (long id : new long[] {1, 2, 3})
            store.appendWithId(event(), id, 2, HOUR).block();

        assertThat(store.range(topic, null, 10).map(PushEvent::id).collectList().block())
                .containsExactly(MemoryPushStore.formatId(2), MemoryPushStore.formatId(3));
    }

    @Test
    @DisplayName(
            "GIVEN kept events WHEN reading the head event SHOULD return the newest one; empty for an unknown topic")
    void given_keptEvents_when_headEventRead_should_returnNewest() {
        store.appendWithId(event(), 4, 10, HOUR).block();
        store.appendWithId(event(), 8, 10, HOUR).block();

        assertThat(store.headEvent(topic).block().id()).isEqualTo(MemoryPushStore.formatId(8));
        assertThat(store.headEvent(Topic.parse("ticks:none")).block()).isNull();
    }

    @Test
    @DisplayName("GIVEN a topic without history WHEN an explicit id is appended SHOULD keep the id and store nothing")
    void given_noHistory_when_explicitIdAppended_should_keepIdAndStoreNothing() {
        PushEvent stored = store.appendWithId(event(), 5, 0, null).block();

        assertThat(stored.id()).isEqualTo(MemoryPushStore.formatId(5));
        assertThat(store.historyLength(topic)).isZero();
    }
}
