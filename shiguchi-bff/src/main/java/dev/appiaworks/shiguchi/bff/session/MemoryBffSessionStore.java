package dev.appiaworks.shiguchi.bff.session;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sessions and logins in this JVM's memory. For one instance, development and tests: sessions do
 * not survive a restart and are not shared between instances. Expired entries are invisible to
 * reads at once and are dropped by a periodic sweep.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class MemoryBffSessionStore implements BffSessionStore, AutoCloseable {

    private static final long SWEEP_EVERY_MILLIS = Duration.ofSeconds(30).toMillis();

    private record Entry<T>(T value, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry<BffSession>> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry<String>> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry<BffLogin>> logins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> jtis = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ScheduledExecutorService sweeper;

    public MemoryBffSessionStore() {
        this(Clock.systemUTC());
    }

    /** @param clock the time source, for tests */
    public MemoryBffSessionStore(Clock clock) {
        this.clock = clock;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bff-session-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_EVERY_MILLIS, SWEEP_EVERY_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public Mono<Void> save(BffSession session, Duration ttl) {
        return Mono.fromRunnable(() ->
                sessions.put(session.key(), new Entry<>(session, clock.instant().plus(ttl))));
    }

    @Override
    public Mono<BffSession> find(String key) {
        return Mono.defer(() -> {
            Entry<BffSession> e = sessions.get(key);
            return e != null && e.expiresAt().isAfter(clock.instant()) ? Mono.just(e.value()) : Mono.empty();
        });
    }

    @Override
    public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
        return Mono.fromRunnable(() -> sessions.computeIfPresent(key, (k, e) -> {
            Instant now = clock.instant();
            if (!e.expiresAt().isAfter(now)) return null;
            return new Entry<>(e.value().withLastSeen(lastSeen), now.plus(ttl));
        }));
    }

    @Override
    public Mono<Void> delete(String key) {
        return Mono.fromRunnable(() -> sessions.remove(key));
    }

    @Override
    public Mono<Void> saveLogin(BffLogin login, Duration ttl) {
        return Mono.fromRunnable(() ->
                logins.put(login.state(), new Entry<>(login, clock.instant().plus(ttl))));
    }

    @Override
    public Mono<BffLogin> takeLogin(String state) {
        return Mono.defer(() -> {
            Entry<BffLogin> e = logins.remove(state);
            return e != null && e.expiresAt().isAfter(clock.instant()) ? Mono.just(e.value()) : Mono.empty();
        });
    }

    @Override
    public Mono<Boolean> tryLease(String key, String owner, Duration ttl) {
        return Mono.fromSupplier(() -> {
            Instant now = clock.instant();
            boolean[] taken = {false};
            leases.compute(key, (k, held) -> {
                if (held != null && held.expiresAt().isAfter(now)) return held;
                taken[0] = true;
                return new Entry<>(owner, now.plus(ttl));
            });
            return taken[0];
        });
    }

    @Override
    public Mono<Void> releaseLease(String key, String owner) {
        return Mono.fromRunnable(
                () -> leases.computeIfPresent(key, (k, held) -> held.value().equals(owner) ? null : held));
    }

    @Override
    public Mono<Boolean> replaceIfVersion(BffSession next, long expectedVersion) {
        return Mono.fromSupplier(() -> {
            Instant now = clock.instant();
            boolean[] replaced = {false};
            sessions.computeIfPresent(next.key(), (k, e) -> {
                if (!e.expiresAt().isAfter(now) || e.value().version() != expectedVersion) return e;
                replaced[0] = true;
                return new Entry<>(next, e.expiresAt());
            });
            return replaced[0];
        });
    }

    @Override
    public Flux<BffSession> findBySubject(String provider, String subject) {
        return Flux.defer(() -> Flux.fromIterable(live(provider, s -> subject.equals(s.subject()))));
    }

    @Override
    public Mono<List<String>> deleteBySid(String provider, String sid) {
        return Mono.fromSupplier(() -> deleteWhere(provider, s -> sid.equals(s.sid())));
    }

    @Override
    public Mono<List<String>> deleteBySubject(String provider, String subject) {
        return Mono.fromSupplier(() -> deleteWhere(provider, s -> subject.equals(s.subject())));
    }

    /** One pass over the map: this store is for one instance, so the index is a scan, not a second structure. */
    private List<BffSession> live(String provider, java.util.function.Predicate<BffSession> match) {
        Instant now = clock.instant();
        List<BffSession> found = new ArrayList<>();
        sessions.forEach((k, e) -> {
            BffSession s = e.value();
            if (e.expiresAt().isAfter(now) && Objects.equals(provider, s.provider()) && match.test(s)) found.add(s);
        });
        return found;
    }

    private List<String> deleteWhere(String provider, java.util.function.Predicate<BffSession> match) {
        List<String> deleted = new ArrayList<>();
        for (BffSession s : live(provider, match)) {
            if (sessions.remove(s.key()) != null) deleted.add(s.key());
        }
        return deleted;
    }

    @Override
    public Mono<Boolean> markJtiSeen(String provider, String jti, Duration ttl) {
        return Mono.fromSupplier(() -> {
            Instant now = clock.instant();
            boolean[] first = {false};
            jtis.compute(provider + ":" + jti, (k, until) -> {
                if (until != null && until.isAfter(now)) return until;
                first[0] = true;
                return now.plus(ttl);
            });
            return first[0];
        });
    }

    @Override
    public Mono<Void> forgetJti(String provider, String jti) {
        return Mono.fromRunnable(() -> jtis.remove(provider + ":" + jti));
    }

    /** Drops every expired entry. Runs periodically; public so tests need not wait. */
    public void sweep() {
        Instant now = clock.instant();
        sessions.values().removeIf(e -> !e.expiresAt().isAfter(now));
        logins.values().removeIf(e -> !e.expiresAt().isAfter(now));
        leases.values().removeIf(e -> !e.expiresAt().isAfter(now));
        jtis.values().removeIf(until -> !until.isAfter(now));
    }

    /** Stops the sweeper. */
    @Override
    public void close() {
        sweeper.shutdownNow();
    }
}
