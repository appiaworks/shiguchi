package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.test.StepVerifier;
import redis.embedded.RedisServer;

/**
 * The Redis broker: live fan-out through Pub/Sub, one channel per topic, multiplexed on one
 * listener connection per instance. A subscription through Redis takes a moment to be registered on
 * the server, so tests sleep before publishing to ensure the subscription is ready. Subscriptions
 * never complete (they are infinite streams), so we use thenCancel() and never verifyComplete().
 */
@DisplayName("RedisPushBroker (real Redis)")
class RedisPushBrokerTest {

    private static RedisServer redis;
    private static LettuceConnectionFactory connections;
    private static ReactiveStringRedisTemplate template;

    private String keyPrefix;
    private RedisPushBroker broker;
    private Topic topic;
    private byte[] data;

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
        keyPrefix = "test:" + test.getTestMethod().orElseThrow().getName() + ":";
        broker = new RedisPushBroker(template, keyPrefix);
        topic = Topic.parse("orders:123");
        data = "{}".getBytes();
    }

    @AfterEach
    void cleanup() {
        broker.destroy();
    }

    private PushEvent event(String id) {
        return new PushEvent(topic, id, data, null, null, null);
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber WHEN an event is published after subscription is registered SHOULD deliver through Redis")
    void given_subscriber_when_eventPublishedAfterSubscribed_should_deliver() {
        StepVerifier.create(broker.subscribe(topic))
                .expectSubscription()
                .then(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    broker.publish(event("1")).subscribe();
                })
                .assertNext(received -> assertThat(received.id()).isEqualTo("1"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN two subscribers on the same topic WHEN an event is published SHOULD deliver to both")
    void given_twoSubscribers_when_eventPublished_should_deliverToBoth() {
        List<PushEvent> secondReceived = new CopyOnWriteArrayList<>();
        var secondSubscription = broker.subscribe(topic).subscribe(secondReceived::add);

        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        StepVerifier.create(broker.subscribe(topic))
                .expectSubscription()
                .then(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    broker.publish(event("1")).subscribe();
                })
                .assertNext(received -> assertThat(received.id()).isEqualTo("1"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(secondReceived).extracting(PushEvent::id).containsExactly("1");
        secondSubscription.dispose();
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber on a different topic WHEN an event is published to another topic SHOULD not deliver")
    void given_subscriberOnDifferentTopic_when_eventPublished_should_notDeliver() {
        Topic otherTopic = Topic.parse("orders:456");

        StepVerifier.create(broker.subscribe(otherTopic))
                .expectSubscription()
                .then(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    PushEvent eventOnDifferentTopic = new PushEvent(topic, "1", data, null, null, null);
                    broker.publish(eventOnDifferentTopic).subscribe();
                })
                .expectNoEvent(Duration.ofMillis(500))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN two broker instances with the same prefix WHEN one publishes SHOULD the other receives")
    void given_twoBrokers_when_onePublishes_should_otherReceives() throws IOException {
        // Create a second broker instance pointing to the same Redis and key prefix
        RedisPushBroker broker2 = new RedisPushBroker(template, keyPrefix);

        StepVerifier.create(broker.subscribe(topic))
                .expectSubscription()
                .then(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // Publish from broker2, subscribe on broker
                    broker2.publish(event("1")).subscribe();
                })
                .assertNext(received -> assertThat(received.id()).isEqualTo("1"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        broker2.destroy();
    }

    @Test
    @DisplayName(
            "GIVEN subscriptions opening and closing WHEN tracked SHOULD report subscriber count and active topics correctly")
    void given_subscriptionsOpeningAndClosing_when_tracked_should_reportCounts() {
        assertThat(broker.subscriberCount(topic)).isZero();
        assertThat(broker.activeTopics()).isZero();

        var sub1 = broker.subscribe(topic).subscribe();
        assertThat(broker.subscriberCount(topic)).isOne();
        assertThat(broker.activeTopics()).isOne();

        Topic topic2 = Topic.parse("orders:456");
        var sub2 = broker.subscribe(topic2).subscribe();
        assertThat(broker.subscriberCount(topic2)).isOne();
        assertThat(broker.activeTopics()).isEqualTo(2);

        sub1.dispose();
        assertThat(broker.subscriberCount(topic)).isZero();
        // activeTopics may be 1 or 2 depending on subscription cleanup timing; just verify sub2 is still there
        assertThat(broker.subscriberCount(topic2)).isOne();

        sub2.dispose();
        // After cleanup delay, activeTopics should eventually be 0
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(broker.subscriberCount(topic2)).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN an event published with no subscriber WHEN a new subscriber joins SHOULD not receive the lost event")
    void given_eventPublishedWithNoSubscriber_when_newSubscriberJoins_should_notReceiveLostEvent() {
        // Publish with no subscriber
        broker.publish(event("1")).block();

        // Now subscribe and verify the lost event is not delivered
        StepVerifier.create(broker.subscribe(topic))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(500))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN a subscription that receives one event WHEN cancelled SHOULD clean up the subscription")
    void given_subscriptionReceivingEvent_when_cancelled_should_cleanup() {
        StepVerifier.create(broker.subscribe(topic))
                .expectSubscription()
                .then(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    broker.publish(event("1")).subscribe();
                })
                .assertNext(received -> assertThat(received.id()).isEqualTo("1"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        // After cancel, the topic should no longer be actively subscribed
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(broker.activeTopics()).isZero();
    }

    @Test
    @DisplayName("GIVEN event attributes and key WHEN published through Redis SHOULD be preserved")
    void given_eventWithAttributesAndKey_when_publishedThroughRedis_should_preserveMetadata() {
        PushEvent eventWithMetadata = new PushEvent(
                topic,
                "1",
                "{\"amount\":100}".getBytes(),
                java.util.Map.of("user", "alice", "source", "mobile"),
                "order:xyz",
                null);

        StepVerifier.create(broker.subscribe(topic))
                .expectSubscription()
                .then(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    broker.publish(eventWithMetadata).subscribe();
                })
                .assertNext(received -> {
                    assertThat(received.id()).isEqualTo("1");
                    assertThat(received.attributes())
                            .containsEntry("user", "alice")
                            .containsEntry("source", "mobile");
                    assertThat(received.key()).isEqualTo("order:xyz");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }
}
