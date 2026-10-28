package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * The in-process broker: delivery only to current subscribers with demand, one sink per topic
 * that lives exactly as long as its subscribers. A publish before the subscriber has requested
 * is dropped by design, so every test publishes from a {@code then} step, after the request.
 */
@DisplayName("MemoryPushBroker")
class MemoryPushBrokerTest {

    private final Topic topic = Topic.parse("orders:123");
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final byte[] data = "{}".getBytes();

    private PushEvent event(String id) {
        return new PushEvent(topic, id, data, null, null, null);
    }

    @Test
    @DisplayName("GIVEN a subscriber WHEN an event is published after it subscribed SHOULD deliver it")
    void given_subscriber_when_eventPublished_should_deliver() {
        StepVerifier.create(broker.subscribe(topic))
                .then(() -> broker.publish(event("1")).subscribe())
                .assertNext(received -> assertThat(received.id()).isEqualTo("1"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN no subscriber WHEN an event is published SHOULD complete without error and deliver nothing later")
    void given_noSubscriber_when_eventPublished_should_completeAndNotDeliverLater() {
        StepVerifier.create(broker.publish(event("1"))).verifyComplete();

        StepVerifier.create(broker.subscribe(topic))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN two subscribers on one topic WHEN an event is published SHOULD deliver to both")
    void given_twoSubscribers_when_eventPublished_should_deliverToBoth() {
        List<PushEvent> second = new CopyOnWriteArrayList<>();
        var other = broker.subscribe(topic).subscribe(second::add);

        StepVerifier.create(broker.subscribe(topic))
                .then(() -> broker.publish(event("1")).subscribe())
                .assertNext(received -> assertThat(received.id()).isEqualTo("1"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(second).extracting(PushEvent::id).containsExactly("1");
        other.dispose();
    }

    @Test
    @DisplayName("GIVEN a subscriber on another topic WHEN an event is published SHOULD not deliver it")
    void given_subscriberOnOtherTopic_when_eventPublished_should_notDeliver() {
        StepVerifier.create(broker.subscribe(Topic.parse("orders:456")))
                .expectSubscription()
                .then(() -> broker.publish(event("1")).subscribe())
                .expectNoEvent(Duration.ofMillis(100))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN subscriptions WHEN they are opened and cancelled SHOULD track subscriber count and active topics")
    void given_subscriptions_when_openedAndCancelled_should_trackCounts() {
        Topic topic2 = Topic.parse("orders:456");
        assertThat(broker.subscriberCount(topic)).isZero();
        assertThat(broker.activeTopics()).isZero();

        var sub1 = broker.subscribe(topic).subscribe();
        var sub2 = broker.subscribe(topic2).subscribe();
        assertThat(broker.subscriberCount(topic)).isOne();
        assertThat(broker.activeTopics()).isEqualTo(2);

        sub1.dispose();
        assertThat(broker.subscriberCount(topic)).isZero();
        assertThat(broker.activeTopics()).isOne();

        sub2.dispose();
        assertThat(broker.activeTopics()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a topic whose last subscriber left WHEN a new subscriber arrives SHOULD get a fresh sink that delivers")
    void given_lastSubscriberLeft_when_resubscribed_should_deliverAgain() {
        var sub1 = broker.subscribe(topic).subscribe();
        sub1.dispose();
        assertThat(broker.activeTopics()).isZero();

        StepVerifier.create(broker.subscribe(topic))
                .then(() -> broker.publish(event("2")).subscribe())
                .assertNext(received -> assertThat(received.id()).isEqualTo("2"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
        assertThat(broker.activeTopics()).isZero();
    }
}
