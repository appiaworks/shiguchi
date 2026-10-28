package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Push event publisher: upstream event ids")
class PushPublisherExplicitIdTest {

    private final Topic topic = Topic.parse("ticks:a");
    private final MemoryPushStore store = new MemoryPushStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PushPublisher publisher;

    PushPublisherExplicitIdTest() {
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setHistorySize(100);
        properties.getNamespaces().put("ticks", ns);
        publisher = new PushPublisher(properties, store, new MemoryPushBroker(), new PushMetrics(registry));
    }

    private double count(String outcome) {
        var counter = registry.find("shiguchi.cursus.upstream.ids")
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private PushEvent publish(long id) {
        return publisher
                .publish(topic, "{}".getBytes(), Map.of("upstream.id", Long.toString(id)), null, null, null, id)
                .block();
    }

    @Test
    @DisplayName(
            "GIVEN a store keeping caller ids WHEN published with an id SHOULD use it as the event id and count it kept")
    void given_explicitIdsStore_when_publishedWithId_should_useItAsEventId() {
        PushEvent stored = publish(12);

        assertThat(stored.id()).isEqualTo(MemoryPushStore.formatId(12));
        assertThat(stored.attributes()).containsEntry("upstream.id", "12").doesNotContainKey("upstream.id-rejected");
        assertThat(count("kept")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN the topic already holds a higher id WHEN published with a lower one SHOULD store it under an engine id, marked rejected, and count it")
    void given_higherIdHeld_when_publishedWithLowerId_should_fallBackToEngineIdWithMarker() {
        publish(20);

        PushEvent stored = publish(5);

        assertThat(stored.id()).isNotEqualTo(MemoryPushStore.formatId(5));
        assertThat(store.compareIds(stored.id(), MemoryPushStore.formatId(20))).isPositive();
        assertThat(stored.attributes()).containsEntry("upstream.id", "5").containsEntry("upstream.id-rejected", "true");
        assertThat(store.historyLength(topic)).isEqualTo(2);
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN no id WHEN published SHOULD assign an engine id and touch no upstream-id counter")
    void given_noId_when_published_should_assignEngineId() {
        PushEvent stored = publisher
                .publish(topic, "{}".getBytes(), null, null, null, null, null)
                .block();

        assertThat(stored.id()).isNotNull();
        assertThat(count("kept") + count("rejected") + count("attribute")).isZero();
    }
}
