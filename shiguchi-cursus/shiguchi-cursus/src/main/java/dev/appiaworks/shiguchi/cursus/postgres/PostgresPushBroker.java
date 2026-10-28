package dev.appiaworks.shiguchi.cursus.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushBroker;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushEventCodec;
import dev.appiaworks.shiguchi.cursus.PushException;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import io.r2dbc.postgresql.api.PostgresqlConnection;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

/**
 * Fan-out through Postgres {@code LISTEN/NOTIFY}: every instance listens on one channel over a
 * dedicated connection, opened with the first local subscriber and kept for the broker's life, and
 * dispatches each notification to the local subscribers of its topic. A publish is one {@code
 * pg_notify}; the publishing instance receives its own events back like any other, so every
 * instance sees the same order. Postgres caps a notification at 8000 bytes: an event that fits
 * travels inline, a larger one is announced by id and read from the {@link PostgresPushStore},
 * which needs the namespace to keep a history.
 *
 * <p>Notifications are at most once: an instance whose listener is reconnecting misses them. The
 * store is what a subscriber resumes from.
 */
public final class PostgresPushBroker implements PushBroker, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PostgresPushBroker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConnectionFactory connections;
    private final DatabaseClient db;
    private final String channel;
    private final int inlineMax;
    private final Supplier<PostgresPushStore> store;
    private final ConcurrentHashMap<Topic, Sinks.Many<PushEvent>> sinks = new ConcurrentHashMap<>();
    private final AtomicReference<Disposable> listener = new AtomicReference<>();
    private final AtomicReference<Connection> listening = new AtomicReference<>();
    private final Sinks.Empty<Void> ready = Sinks.empty();

    public PostgresPushBroker(
            ConnectionFactory connections,
            DatabaseClient db,
            PushProperties.Postgres config,
            Supplier<PostgresPushStore> store) {
        this.connections = connections;
        this.db = db;
        this.channel = PostgresPushSchema.channel(config.getChannel());
        this.inlineMax = Math.min(7990, Math.max(256, config.getNotifyMaxBytes()));
        this.store = store;
    }

    @Override
    public Mono<Void> publish(PushEvent event) {
        String payload = PushEventCodec.encode(event);
        if (payload.getBytes(StandardCharsets.UTF_8).length > inlineMax) {
            if (!PostgresPushStore.isStored(event.id()) || store.get() == null) {
                return Mono.error(PushException.tooLarge(payload.length(), inlineMax));
            }
            payload = "{\"ref\":\"" + event.id() + "\",\"topic\":\"" + event.topic() + "\"}";
        }
        return db.sql("SELECT pg_notify($1, $2)")
                .bind("$1", channel)
                .bind("$2", payload)
                .fetch()
                .rowsUpdated()
                .then();
    }

    @Override
    public Flux<PushEvent> subscribe(Topic topic) {
        return Flux.defer(() -> {
            ensureListening();
            Sinks.Many<PushEvent> sink =
                    sinks.computeIfAbsent(topic, t -> Sinks.many().multicast().directBestEffort());
            return sink.asFlux()
                    .doFinally(signal -> sinks.computeIfPresent(topic, (t, s) -> {
                        return s == sink && s.currentSubscriberCount() == 0 ? null : s;
                    }));
        });
    }

    /** Completes once the listener has issued {@code LISTEN}; what is published after that is seen. */
    public Mono<Void> listening() {
        ensureListening();
        return ready.asMono();
    }

    private void ensureListening() {
        if (listener.get() != null) return;
        Disposable d = Flux.defer(this::listenOnce)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10))
                        .doBeforeRetry(r -> log.warn(
                                "Push: Postgres listener on {} failed, reconnecting: {}",
                                channel,
                                r.failure().toString())))
                .subscribe(
                        this::dispatch,
                        e -> log.error("Push: Postgres listener on {} gave up: {}", channel, e.toString()));
        if (!listener.compareAndSet(null, d)) d.dispose();
    }

    /** One connection's worth of notifications; completes or errors when the connection does. */
    private Flux<PushEvent> listenOnce() {
        return Mono.from(connections.create()).flatMapMany(connection -> {
            Connection previous = listening.getAndSet(connection);
            if (previous != null) Mono.from(previous.close()).subscribe(v -> {}, e -> {});
            PostgresqlConnection pg = unwrap(connection);
            return Flux.from(pg.createStatement("LISTEN " + channel).execute())
                    .flatMap(r -> r.getRowsUpdated())
                    .then(Mono.fromRunnable(() -> {
                        ready.tryEmitEmpty();
                        log.info("Push: listening on Postgres channel {}", channel);
                    }))
                    .thenMany(pg.getNotifications())
                    .concatMap(n -> decode(n.getParameter()))
                    .doFinally(s -> {
                        if (listening.compareAndSet(connection, null)) {
                            Mono.from(connection.close()).subscribe(v -> {}, e -> {});
                        }
                    });
        });
    }

    private Mono<PushEvent> decode(String payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            if (node.hasNonNull("ref")) {
                PostgresPushStore s = store.get();
                if (s == null) return Mono.empty();
                return s.byId(node.get("ref").asText()).onErrorResume(e -> {
                    log.warn("Push: event {} announced but not readable: {}", node.get("ref"), e.toString());
                    return Mono.empty();
                });
            }
            return Mono.just(PushEventCodec.decode(payload));
        } catch (java.io.IOException | IllegalArgumentException e) {
            log.warn("Push: ignoring a notification on {} that is not an event: {}", channel, e.toString());
            return Mono.empty();
        }
    }

    private void dispatch(PushEvent event) {
        Sinks.Many<PushEvent> sink = sinks.get(event.topic());
        if (sink != null) sink.tryEmitNext(event);
    }

    private static PostgresqlConnection unwrap(Connection connection) {
        Object c = connection;
        while (!(c instanceof PostgresqlConnection) && c instanceof Wrapped<?> w) c = w.unwrap();
        if (c instanceof PostgresqlConnection pg) return pg;
        throw new IllegalStateException("shiguchi.cursus.broker POSTGRES needs the Postgres R2DBC driver, got "
                + connection.getClass().getName());
    }

    @Override
    public long subscriberCount(Topic topic) {
        Sinks.Many<PushEvent> sink = sinks.get(topic);
        return sink == null ? 0 : sink.currentSubscriberCount();
    }

    @Override
    public Map<Topic, Long> subscriptions() {
        Map<Topic, Long> out = new HashMap<>();
        sinks.forEach((t, s) -> {
            long n = s.currentSubscriberCount();
            if (n > 0) out.put(t, n);
        });
        return out;
    }

    @Override
    public void destroy() {
        Disposable d = listener.getAndSet(null);
        if (d != null) d.dispose();
        Connection c = listening.getAndSet(null);
        if (c != null) Mono.from(c.close()).subscribe(v -> {}, e -> {});
    }
}
