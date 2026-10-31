package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.Disposable;

@DisplayName("JdbcPushBroker (polling the table of the JDBC store, H2)")
class JdbcPushBrokerTest {

    private static final String TABLE = "push_event_broker_test";
    private static final Duration POLL = Duration.ofMillis(50);

    private static DataSource dataSource;
    private static JdbcPushStore store;

    private final Topic topic = Topic.parse("orders:123");
    private final AtomicBoolean failing = new AtomicBoolean();
    private final List<JdbcPushBroker> brokers = new CopyOnWriteArrayList<>();
    private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        dataSource = JdbcTestDatabase.H2.dataSource("JdbcPushBrokerTest");
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(TABLE);
        config.setSweepInterval(Duration.ZERO);
        store = new JdbcPushStore(dataSource, config);
        store.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        if (store != null) store.destroy();
    }

    @BeforeEach
    void clear() {
        new JdbcTemplate(dataSource).update("DELETE FROM " + TABLE);
    }

    @AfterEach
    void cleanup() {
        subscriptions.forEach(Disposable::dispose);
        brokers.forEach(JdbcPushBroker::destroy);
    }

    private JdbcPushBroker broker(DataSource ds, SimpleMeterRegistry metrics) {
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(TABLE);
        config.setPollInterval(POLL);
        JdbcPushBroker broker = new JdbcPushBroker(ds, config, metrics);
        broker.start();
        brokers.add(broker);
        return broker;
    }

    private List<PushEvent> listen(JdbcPushBroker broker) {
        List<PushEvent> seen = new CopyOnWriteArrayList<>();
        subscriptions.add(broker.subscribe(topic).subscribe(seen::add));
        return seen;
    }

    /** What a publish does: the store writes the row, then the broker is told. */
    private PushEvent publish(JdbcPushBroker via, String json) {
        PushEvent event = new PushEvent(topic, null, json.getBytes(), null, null, Instant.now());
        PushEvent stored = store.append(event, 100, Duration.ZERO).block(Duration.ofSeconds(10));
        via.publish(stored).block(Duration.ofSeconds(10));
        return stored;
    }

    private static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        return condition.getAsBoolean();
    }

    /** A DataSource that refuses connections while {@code failing} is set. */
    private DataSource flaky() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (failing.get() && method.getName().equals("getConnection")) {
                        throw new SQLException("database unavailable");
                    }
                    try {
                        return method.invoke(dataSource, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** A DataSource that runs the armed hook once, on the next connection the broker opens. */
    private DataSource hooked(AtomicReference<Runnable> onConnect) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        Runnable hook = onConnect.getAndSet(null);
                        if (hook != null) hook.run();
                    }
                    try {
                        return method.invoke(dataSource, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    @DisplayName(
            "GIVEN an idle broker WHEN the first subscriber joins and an event is appended during a poll THEN the event is delivered")
    void given_an_idle_broker_when_first_subscriber_joins_during_a_poll_should_deliver_the_event() throws Exception {
        AtomicReference<Runnable> onConnect = new AtomicReference<>();
        JdbcPushBroker a = broker(hooked(onConnect), null);
        List<PushEvent> seen = new CopyOnWriteArrayList<>();
        AtomicReference<PushEvent> stored = new AtomicReference<>();

        // The next poll opens its connection after an idle instance's subscription check would have run.
        onConnect.set(() -> {
            subscriptions.add(a.subscribe(topic).subscribe(seen::add));
            PushEvent event = new PushEvent(topic, null, "{\"n\":1}".getBytes(), null, null, Instant.now());
            stored.set(store.append(event, 100, Duration.ZERO).block(Duration.ofSeconds(10)));
        });

        assertThat(await(() -> !seen.isEmpty(), 5000)).isTrue();
        assertThat(seen.get(0).id()).isEqualTo(stored.get().id());
    }

    @Test
    @DisplayName(
            "GIVEN two brokers on one database WHEN one instance publishes THEN both see it within the poll interval")
    void given_two_brokers_when_one_publishes_should_both_see_it() throws Exception {
        JdbcPushBroker a = broker(dataSource, null);
        JdbcPushBroker b = broker(dataSource, null);
        List<PushEvent> onA = listen(a);
        List<PushEvent> onB = listen(b);

        PushEvent stored = publish(a, "{\"n\":1}");

        assertThat(await(() -> !onA.isEmpty() && !onB.isEmpty(), 5000)).isTrue();
        assertThat(onA).hasSize(1);
        assertThat(onB).hasSize(1);
        assertThat(onB.get(0).id()).isEqualTo(stored.id());
        assertThat(new String(onB.get(0).data())).isEqualTo("{\"n\":1}");
    }

    @Test
    @DisplayName("GIVEN several events WHEN published THEN a subscriber sees them once each in id order")
    void given_several_events_when_published_should_arrive_once_each_in_order() throws Exception {
        JdbcPushBroker a = broker(dataSource, null);
        JdbcPushBroker b = broker(dataSource, null);
        List<PushEvent> onB = listen(b);

        for (int i = 0; i < 5; i++) publish(a, "{\"n\":" + i + "}");

        assertThat(await(() -> onB.size() >= 5, 5000)).isTrue();
        Thread.sleep(3 * POLL.toMillis());
        assertThat(onB)
                .extracting(e -> new String(e.data()))
                .containsExactly("{\"n\":0}", "{\"n\":1}", "{\"n\":2}", "{\"n\":3}", "{\"n\":4}");
    }

    @Test
    @DisplayName(
            "GIVEN events already in the table WHEN a broker starts THEN it starts at the head and does not replay them")
    void given_existing_events_when_broker_starts_should_start_at_head() throws Exception {
        publish(broker(dataSource, null), "{\"old\":true}");

        JdbcPushBroker late = broker(dataSource, null);
        List<PushEvent> seen = listen(late);
        Thread.sleep(4 * POLL.toMillis());
        assertThat(seen).isEmpty();

        publish(late, "{\"new\":true}");

        assertThat(await(() -> !seen.isEmpty(), 5000)).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(new String(seen.get(0).data())).isEqualTo("{\"new\":true}");
    }

    @Test
    @DisplayName("GIVEN an event with no row (negative local id) WHEN published THEN local subscribers get it at once")
    void given_a_local_event_when_published_should_reach_local_subscribers_at_once() {
        JdbcPushBroker a = broker(dataSource, null);
        List<PushEvent> seen = listen(a);

        a.publish(new PushEvent(topic, "-5", "{}".getBytes(), null, null, Instant.now()))
                .block(Duration.ofSeconds(5));

        assertThat(seen).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a broker with a subscriber WHEN a poll runs THEN the polls counter and the lag timer move")
    void given_a_subscriber_when_polling_should_record_polls_and_lag() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        JdbcPushBroker a = broker(dataSource, metrics);
        List<PushEvent> seen = listen(a);

        publish(a, "{}");

        assertThat(await(() -> !seen.isEmpty(), 5000)).isTrue();
        assertThat(metrics.get("shiguchi.cursus.jdbc.polls")
                        .tag("outcome", "ok")
                        .counter()
                        .count())
                .isGreaterThan(0);
        assertThat(metrics.get("shiguchi.cursus.jdbc.poll_lag").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a destroyed broker WHEN events are appended THEN it stops polling and delivers nothing")
    void given_a_destroyed_broker_when_events_appended_should_stop_polling() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        JdbcPushBroker a = broker(dataSource, metrics);
        List<PushEvent> seen = listen(a);
        assertThat(await(
                        () -> metrics.counter("shiguchi.cursus.jdbc.polls", "outcome", "ok")
                                        .count()
                                > 2,
                        5000))
                .isTrue();

        a.destroy();
        Thread.sleep(2 * POLL.toMillis());
        double polls =
                metrics.counter("shiguchi.cursus.jdbc.polls", "outcome", "ok").count();
        publish(a, "{}");
        Thread.sleep(6 * POLL.toMillis());

        assertThat(seen).isEmpty();
        assertThat(metrics.counter("shiguchi.cursus.jdbc.polls", "outcome", "ok")
                        .count())
                .isEqualTo(polls);
    }

    @Test
    @DisplayName(
            "GIVEN a poll that fails WHEN the database comes back THEN the error is counted and the next poll delivers")
    void given_a_failing_poll_when_database_returns_should_retry_and_deliver() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        JdbcPushBroker a = broker(flaky(), metrics);
        List<PushEvent> seen = listen(a);
        PushEvent stored = publishWhileDown(a);

        assertThat(await(
                        () -> metrics.counter("shiguchi.cursus.jdbc.polls", "outcome", "error")
                                        .count()
                                > 0,
                        5000))
                .isTrue();
        assertThat(seen).isEmpty();

        failing.set(false);

        assertThat(await(() -> !seen.isEmpty(), 5000)).isTrue();
        assertThat(seen.get(0).id()).isEqualTo(stored.id());
    }

    /** The event is appended through the healthy store while the broker's own connections fail. */
    private PushEvent publishWhileDown(JdbcPushBroker a) {
        failing.set(true);
        return publish(a, "{\"during\":\"outage\"}");
    }
}
