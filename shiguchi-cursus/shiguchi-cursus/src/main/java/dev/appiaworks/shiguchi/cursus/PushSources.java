package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs the {@link PushSource}s: the ones built from {@code shiguchi.cursus.sources} and the
 * beans. A shared source is consumed by one gateway instance at a time, elected through a lease
 * in the store ({@code source:<name>}, {@code upstream.lease-ttl}); the others wait and take over
 * when the owner goes. Each event is published into its topic through the {@link PushPublisher};
 * one the gateway refuses goes to the {@link PushDlq}. A stream that ends or fails is reopened
 * after a doubling backoff ({@code upstream.retry-backoff} to {@code max-retry-backoff}). The
 * namespaces of the built-in sources' templates are declared when they are not configured, with
 * the source's {@code payload-type} when it has one.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class PushSources implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PushSources.class);
    static final String LEASE_PREFIX = "source:";

    /** One source as it runs. */
    final class Run {
        final PushSource source;
        final PushUpstreams.Backoff backoff = new PushUpstreams.Backoff(
                properties.getUpstream().getRetryBackoff(),
                properties.getUpstream().getMaxRetryBackoff());
        final AtomicLong published = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        volatile boolean owner;
        volatile String lastError;
        volatile Instant lastEventAt;
        volatile Disposable loop;

        Run(PushSource source) {
            this.source = source;
        }
    }

    private final PushProperties properties;
    private final PushStore store;
    private final PushPublisher publisher;
    private final PushMetrics metrics;
    private final PushDlq dlq;
    private final String holder;
    private final List<Run> runs = new ArrayList<>();
    private volatile boolean running;

    public PushSources(
            PushProperties properties,
            PushStore store,
            PushPublisher publisher,
            PushMetrics metrics,
            PushDlq dlq,
            List<PushSource> sources,
            String holder) {
        this.properties = properties;
        this.store = store;
        this.publisher = publisher;
        this.metrics = metrics;
        this.dlq = dlq;
        this.holder = holder;
        Set<String> names = new HashSet<>();
        for (PushSource s : sources) {
            if (s.name() == null || s.name().isBlank()) {
                throw new IllegalStateException(
                        "shiguchi.cursus: a source has no name (" + s.getClass().getName() + ")");
            }
            if (!names.add(s.name())) {
                throw new IllegalStateException("shiguchi.cursus: two sources are named '" + s.name() + "'");
            }
            runs.add(new Run(s));
        }
    }

    /** The sources, in registration order. */
    public List<PushSource> sources() {
        return runs.stream().map(r -> r.source).toList();
    }

    /** The sources as the overview shows them. */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Run r : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.source.name());
            m.put("kind", r.source.describe());
            m.put("shared", r.source.shared());
            m.put("state", !running ? "stopped" : r.owner ? "owner" : r.source.shared() ? "waiting" : "running");
            m.put("published", r.published.get());
            m.put("failed", r.failed.get());
            m.put("lastEventAt", r.lastEventAt == null ? null : r.lastEventAt.toString());
            m.put("lastError", r.lastError);
            out.add(m);
        }
        return out;
    }

    /** Sources this instance currently consumes. */
    public List<String> owned() {
        List<String> out = new ArrayList<>();
        for (Run r : runs) if (r.owner) out.add(r.source.name());
        return out;
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        for (Run r : runs) {
            r.loop = loop(r).subscribe(
                            v -> {}, e -> log.warn("Push: source {} loop ended: {}", r.source.name(), e.toString()));
        }
        if (!runs.isEmpty()) {
            log.info(
                    "Push: {} source(s): {}",
                    runs.size(),
                    runs.stream()
                            .map(r -> r.source.name() + " (" + r.source.describe() + ")")
                            .toList());
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Run r : runs) {
            boolean owned = r.owner;
            if (r.loop != null) r.loop.dispose();
            if (owned) {
                if (r.owner) {
                    r.owner = false;
                    metrics.sourceClosed();
                }
                if (r.source.shared()) {
                    store.release(LEASE_PREFIX + r.source.name(), holder)
                            .subscribe(
                                    v -> {},
                                    e -> log.debug(
                                            "Push: lease of source {} not released: {}",
                                            r.source.name(),
                                            e.toString()));
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Lease when shared, consume while owner, otherwise wait; forever, with a backoff after a failure or an ended stream. */
    private Mono<Void> loop(Run run) {
        Duration ttl = properties.getUpstream().getLeaseTtl();
        String key = LEASE_PREFIX + run.source.name();
        Mono<Boolean> lease = Mono.defer(() -> run.source.shared() ? store.lease(key, holder, ttl) : Mono.just(true));
        return Flux.defer(() -> lease.flatMap(acquired -> acquired
                                ? consume(run, key, ttl).then(Mono.delay(run.backoff.next(), Schedulers.parallel()))
                                : Mono.delay(ttl, Schedulers.parallel()))
                        .then())
                .onErrorResume(e -> {
                    Duration wait = run.backoff.next();
                    run.lastError = e.toString();
                    log.warn("Push: source {} failed, retrying in {}: {}", run.source.name(), wait, e.toString());
                    return Mono.delay(wait, Schedulers.parallel()).then();
                })
                .repeat(() -> running)
                .then();
    }

    private Flux<PushEvent> consume(Run run, String key, Duration ttl) {
        Mono<Void> leaseLost;
        if (run.source.shared()) {
            Duration renewEvery = Duration.ofMillis(Math.max(100, ttl.toMillis() / 3));
            leaseLost = Flux.interval(renewEvery, renewEvery, Schedulers.parallel())
                    .concatMap(t -> store.lease(key, holder, ttl).onErrorReturn(false))
                    .filter(ok -> !ok)
                    .next()
                    .doOnNext(lost -> log.warn("Push: lease of source {} lost; closing it", run.source.name()))
                    .then();
        } else {
            leaseLost = Mono.never();
        }
        return Flux.defer(() -> {
                    run.owner = true;
                    metrics.sourceOpened();
                    log.info("Push: {} consumes source {}", holder, run.source.name());
                    return run.source.events();
                })
                .concatMap(event -> publish(run, event))
                .takeUntilOther(leaseLost)
                .doFinally(signal -> {
                    if (run.owner) {
                        run.owner = false;
                        metrics.sourceClosed();
                    }
                });
    }

    private Mono<PushEvent> publish(Run run, SourcedEvent event) {
        Map<String, String> attributes = new LinkedHashMap<>(event.attributes());
        attributes.putIfAbsent("from", "source");
        attributes.putIfAbsent("source", run.source.name());
        return publisher
                .publish(event.topic(), event.data(), attributes, event.key(), event.typeUrl(), event.proto())
                .doOnNext(e -> {
                    run.published.incrementAndGet();
                    run.lastEventAt = e.time();
                    run.backoff.reset();
                    metrics.sourced(run.source.name(), "published");
                })
                .onErrorResume(e -> {
                    run.failed.incrementAndGet();
                    run.lastError = e.toString();
                    metrics.sourced(run.source.name(), "dead_lettered");
                    String reason = e instanceof PushException pe ? pe.code() + ": " + pe.getMessage() : e.toString();
                    dlq.add(run.source.name(), reason, event);
                    log.warn(
                            "Push: event from source {} on {} dead-lettered: {}",
                            run.source.name(),
                            event.topic(),
                            reason);
                    return Mono.empty();
                });
    }
}
