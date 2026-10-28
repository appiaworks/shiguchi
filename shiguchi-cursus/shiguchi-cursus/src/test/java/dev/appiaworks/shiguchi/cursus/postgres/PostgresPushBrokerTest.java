package dev.appiaworks.shiguchi.cursus.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushException;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.Disposable;

@DisplayName("PostgresPushBroker (real Postgres LISTEN/NOTIFY)")
class PostgresPushBrokerTest {

    private static final String TABLE = "push_event_broker_test";
    private static final String CHANNEL = "push_broker_test";

    private static DatabaseClient client;
    private static PostgresPushStore store;

    private PostgresPushBroker brokerA;
    private PostgresPushBroker brokerB;
    private final Topic topic = Topic.parse("orders:123");

    @BeforeAll
    static void start() {
        client = EmbeddedPg.client();
        PushProperties.Postgres config = new PushProperties.Postgres();
        config.setTable(TABLE);
        config.setSweepInterval(Duration.ZERO);
        store = new PostgresPushStore(client, config);
        store.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        if (store != null) store.destroy();
    }

    @BeforeEach
    void setup() {
        client.sql("DELETE FROM " + TABLE).fetch().rowsUpdated().block();
        brokerA = broker(7900);
        brokerB = broker(512);
    }

    @AfterEach
    void cleanup() {
        brokerA.destroy();
        brokerB.destroy();
    }

    private PostgresPushBroker broker(int notifyMaxBytes) {
        PushProperties.Postgres config = new PushProperties.Postgres();
        config.setTable(TABLE);
        config.setChannel(CHANNEL);
        config.setNotifyMaxBytes(notifyMaxBytes);
        return new PostgresPushBroker(EmbeddedPg.connectionFactory(), EmbeddedPg.client(), config, () -> store);
    }

    private PushEvent event(Topic t, String id, String json) {
        return new PushEvent(t, id, json.getBytes(), Map.of("k", "v"), null, Instant.now());
    }

    private static void awaitSize(List<?> list, int size) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (list.size() < size && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    private List<PushEvent> listen(PostgresPushBroker broker, Topic t, List<Disposable> subs) {
        List<PushEvent> received = new CopyOnWriteArrayList<>();
        subs.add(broker.subscribe(t).subscribe(received::add));
        broker.listening().block(Duration.ofSeconds(10));
        return received;
    }

    @Test
    @DisplayName("GIVEN a subscriber on broker A WHEN broker B publishes SHOULD deliver the event to A")
    void given_subscriberOnA_when_bPublishes_should_deliver() throws InterruptedException {
        List<Disposable> subs = new CopyOnWriteArrayList<>();
        List<PushEvent> received = listen(brokerA, topic, subs);

        brokerB.publish(event(topic, "1", "{\"n\":1}")).block(Duration.ofSeconds(10));
        awaitSize(received, 1);

        assertThat(received).hasSize(1);
        PushEvent e = received.get(0);
        assertThat(e.topic()).isEqualTo(topic);
        assertThat(new String(e.data())).isEqualTo("{\"n\":1}");
        assertThat(e.attributes()).isEqualTo(Map.of("k", "v"));
        assertThat(e.id()).isEqualTo("1");
        subs.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName("GIVEN two subscribers and one on another topic WHEN publishing SHOULD reach only the matching two")
    void given_subscribersOnTwoTopics_when_publishing_should_reachMatchingOnly() throws InterruptedException {
        List<Disposable> subs = new CopyOnWriteArrayList<>();
        List<PushEvent> first = listen(brokerA, topic, subs);
        List<PushEvent> second = listen(brokerA, topic, subs);
        List<PushEvent> other = listen(brokerA, Topic.parse("orders:456"), subs);

        brokerB.publish(event(topic, "1", "{}")).block(Duration.ofSeconds(10));
        awaitSize(first, 1);
        awaitSize(second, 1);
        Thread.sleep(300);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(other).isEmpty();
        subs.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName("GIVEN two subscribers WHEN they cancel SHOULD report counts and subscriptions accordingly")
    void given_twoSubscribers_when_cancelling_should_reportCounts() {
        List<Disposable> subs = new CopyOnWriteArrayList<>();
        listen(brokerA, topic, subs);
        listen(brokerA, topic, subs);

        assertThat(brokerA.subscriberCount(topic)).isEqualTo(2);
        assertThat(brokerA.subscriptions()).containsEntry(topic, 2L);

        subs.forEach(Disposable::dispose);

        assertThat(brokerA.subscriberCount(topic)).isZero();
        assertThat(brokerA.subscriptions()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a payload over the inline limit and a stored id WHEN published SHOULD deliver the full data")
    void given_largePayloadWithStoredId_when_published_should_deliverFullData() throws InterruptedException {
        List<Disposable> subs = new CopyOnWriteArrayList<>();
        List<PushEvent> received = listen(brokerA, topic, subs);
        String json = "\"" + "x".repeat(1998) + "\"";
        PushEvent stored = store.append(event(topic, null, json), 100, null).block();

        brokerB.publish(stored).block(Duration.ofSeconds(10));
        awaitSize(received, 1);

        assertThat(received).hasSize(1);
        assertThat(new String(received.get(0).data())).isEqualTo(json);
        assertThat(received.get(0).id()).isEqualTo(stored.id());
        subs.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName("GIVEN a payload over the inline limit and no id WHEN published SHOULD fail with PAYLOAD_TOO_LARGE")
    void given_largePayloadWithoutId_when_published_should_failTooLarge() {
        String json = "\"" + "x".repeat(1998) + "\"";

        Throwable error = null;
        try {
            brokerB.publish(event(topic, null, json)).block(Duration.ofSeconds(10));
        } catch (Throwable t) {
            error = t;
        }

        assertThat(error).isInstanceOf(PushException.class);
        assertThat(((PushException) error).status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("GIVEN a subscriber on the publishing broker WHEN it publishes SHOULD receive its own event")
    void given_subscriberOnPublisher_when_publishing_should_receiveOwnEvent() throws InterruptedException {
        List<Disposable> subs = new CopyOnWriteArrayList<>();
        List<PushEvent> received = listen(brokerB, topic, subs);

        brokerB.publish(event(topic, "1", "{}")).block(Duration.ofSeconds(10));
        awaitSize(received, 1);

        assertThat(received).hasSize(1);
        subs.forEach(Disposable::dispose);
    }

    @Test
    @DisplayName("GIVEN a destroyed broker WHEN another publishes SHOULD not deliver, and a second destroy is harmless")
    void given_destroyedBroker_when_otherPublishes_should_notDeliver() throws InterruptedException {
        List<Disposable> subs = new CopyOnWriteArrayList<>();
        List<PushEvent> received = listen(brokerA, topic, subs);

        brokerA.destroy();
        brokerB.publish(event(topic, "1", "{}")).block(Duration.ofSeconds(10));
        Thread.sleep(300);

        assertThat(received).isEmpty();
        assertThatCode(brokerA::destroy).doesNotThrowAnyException();
        subs.forEach(Disposable::dispose);
    }
}
