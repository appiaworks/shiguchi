package dev.appiaworks.shiguchi.cursus.jdbc;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.cursus.MemoryPushBroker;
import dev.appiaworks.shiguchi.cursus.PushBroker;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fan-out by polling the table the {@link JdbcPushStore} appends to: every {@code poll-interval}
 * each instance reads the rows past the last id it saw ({@code id > last ORDER BY id}, at most
 * {@value #BATCH} per round, more rounds at once while a round comes back full) and hands them to
 * the local subscribers of their topic through an in-process {@link MemoryPushBroker}. Latency is
 * the poll interval; there is nothing to install on the database.
 *
 * <p>A publish does not notify: the row the store already wrote is the message, and the
 * publishing instance reads it back like every other, so all instances see one order (ids commit
 * in order, {@link JdbcPushStore}). An event with no row (a namespace with no history while the
 * archive is off, whose id is negative) is delivered to this instance's subscribers at once and
 * to no other instance. The poller starts at the head of the table, so history is never replayed
 * as live events; a subscriber resumes from the store. Delivery is at most once: a round that
 * fails is logged and retried from the same id, and rows tombstoned before a round reads them are
 * skipped.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class JdbcPushBroker implements PushBroker, InitializingBean, DisposableBean {

    static final int BATCH = 1000;
    static final Duration MIN_POLL = Duration.ofMillis(50);

    private static final Logger log = LoggerFactory.getLogger(JdbcPushBroker.class);

    private final JdbcTemplate jdbc;
    private final String table;
    private final JdbcDialect dialect;
    private final Duration pollInterval;
    private final MemoryPushBroker local = new MemoryPushBroker();
    private final AtomicLong last = new AtomicLong(-1);
    private final Counter pollsOk;
    private final Counter pollsFailed;
    private final Timer lag;
    private volatile Disposable poller;
    private volatile boolean stopped;

    public JdbcPushBroker(DataSource dataSource, PushProperties.Jdbc config, MeterRegistry metrics) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = JdbcPushSchema.table(config.getTable());
        this.dialect = JdbcDialect.resolve(dataSource, config);
        Duration every = config.getPollInterval();
        this.pollInterval = every == null || every.compareTo(MIN_POLL) < 0 ? MIN_POLL : every;
        MeterRegistry registry =
                metrics == null ? new io.micrometer.core.instrument.simple.SimpleMeterRegistry() : metrics;
        this.pollsOk = Counter.builder("shiguchi.cursus.jdbc.polls")
                .tag("outcome", "ok")
                .description("Polls of the push table by the JDBC broker")
                .register(registry);
        this.pollsFailed = Counter.builder("shiguchi.cursus.jdbc.polls")
                .tag("outcome", "error")
                .description("Polls of the push table by the JDBC broker")
                .register(registry);
        this.lag = Timer.builder("shiguchi.cursus.jdbc.poll_lag")
                .description("Time between an event's creation and its delivery to local subscribers by the poller")
                .register(registry);
    }

    /** Starts polling at the head of the table; a no-op when already started. */
    @Override
    public void afterPropertiesSet() {
        start();
    }

    /**
     * Reads the head of the table (the table must exist; if it cannot be read yet the first round
     * does it) and starts polling from it. Events appended afterwards are delivered.
     */
    public synchronized void start() {
        if (poller != null || stopped) return;
        try {
            last.set(head());
        } catch (RuntimeException e) {
            log.warn("Push: JDBC broker cannot read the head of {} yet, the first poll will: {}", table, e.toString());
        }
        poller = Mono.defer(this::round)
                .onErrorResume(e -> {
                    pollsFailed.increment();
                    log.warn("Push: JDBC poll of {} failed, retrying in {}: {}", table, pollInterval, e.toString());
                    return Mono.empty();
                })
                .then(Mono.delay(pollInterval))
                .repeat()
                .subscribe(v -> {}, e -> log.error("Push: JDBC poller on {} stopped: {}", table, e.toString()));
        log.info("Push: JDBC broker polling {} every {} from id {}", table, pollInterval, last.get());
    }

    private long head() {
        Long max = jdbc.queryForObject("SELECT max(id) FROM " + table, Long.class);
        return max == null ? 0 : max;
    }

    /** One poll: idle instances only move the head forward, busy ones read and dispatch up to a batch, again while full. */
    private Mono<Void> round() {
        return JdbcIo.mono(this::fetch).flatMap(batch -> {
            pollsOk.increment();
            if (batch == null) return Mono.<Void>empty();
            long now = System.currentTimeMillis();
            for (PushEvent event : batch.events()) {
                local.publish(event).subscribe();
                lag.record(Math.max(0, now - event.time().toEpochMilli()), TimeUnit.MILLISECONDS);
            }
            return batch.full() ? round() : Mono.<Void>empty();
        });
    }

    private Batch fetch() {
        long from = last.get();
        if (from < 0) {
            last.set(head());
            return null;
        }
        if (local.subscriptions().isEmpty()) {
            long head = head();
            if (head > from) last.set(head);
            return null;
        }
        long[] max = {from};
        List<PushEvent> events = jdbc.query(
                "SELECT " + JdbcPushStore.COLUMNS + " FROM " + table
                        + " WHERE id > ? AND tombstoned_at IS NULL ORDER BY id" + dialect.page(0, BATCH),
                (rs, i) -> JdbcPushStore.event(rs),
                from);
        // Tombstoned rows are filtered out, so the position moves by the ids that were read.
        for (PushEvent e : events) max[0] = Math.max(max[0], Long.parseLong(e.id()));
        last.set(max[0]);
        return new Batch(events, events.size() >= BATCH);
    }

    private record Batch(List<PushEvent> events, boolean full) {}

    @Override
    public Mono<Void> publish(PushEvent event) {
        if (!JdbcPushStore.isStored(event.id())) return local.publish(event);
        return Mono.empty();
    }

    @Override
    public Flux<PushEvent> subscribe(Topic topic) {
        return local.subscribe(topic);
    }

    @Override
    public long subscriberCount(Topic topic) {
        return local.subscriberCount(topic);
    }

    @Override
    public Map<Topic, Long> subscriptions() {
        return local.subscriptions();
    }

    /** The id the poller has read up to, or -1 before the first read; for tests and diagnostics. */
    public long position() {
        return last.get();
    }

    @Override
    public void destroy() {
        stopped = true;
        Disposable d = poller;
        poller = null;
        if (d != null) d.dispose();
    }
}
