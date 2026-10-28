package dev.appiaworks.shiguchi.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestConfig;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * The gateway's response cache and request coalescing for unary REST {@code GET}s
 * ({@code rest.response-cache}, {@code rest.coalesce-requests}).
 *
 * <p>Two jobs share one mechanism, the in-flight table: a call being made for a key is joined by
 * every caller who asks for the same key before it answers, so a burst of identical requests —
 * the moment a popular entry expires, most obviously — costs the backend one call. With the cache
 * on, the answer is then kept for the route's TTL, and for its {@code stale-while-revalidate}
 * window past that, during which it is still served while one background call refreshes it.
 *
 * <p>The key is built by the caller from everything that can change the answer: route, path and
 * query arguments, response format, and — unless the route's policy is {@code PUBLIC} — the
 * caller's forwarded identity. Lookups happen after authorization, so an entry is never read by a
 * caller the authorizer would have refused.
 */
@Component
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.7.0")
public class ResponseCache implements SchemaReloadListener {

    /** What a key is made of; {@code service} and {@code path} are kept apart for eviction. */
    record Key(String service, String path, String rest) {}

    /** Where an answer came from, for {@code Cache-Status} and the counters. */
    enum Outcome {
        /** Served fresh from the cache. */
        HIT,
        /** Served from the cache past its TTL while a background call refreshes it. */
        STALE,
        /** This caller's own backend call. */
        MISS,
        /** Another caller's in-flight backend call, joined. */
        COALESCED
    }

    /** An answer and how it was obtained; {@code ageSeconds} is how long ago it was fetched. */
    record Served(RenderedResponse response, Outcome outcome, long ageSeconds, boolean stored) {}

    private record Entry(RenderedResponse response, long storedAt, long freshUntil, long staleUntil) {}

    private final boolean enabled;
    private final boolean coalesce;
    private final long maxEntrySize;
    private final LongSupplier clock;
    private final Cache<Key, Entry> entries;
    private final Map<Key, CompletableFuture<RenderedResponse>> inFlight = new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final Map<Outcome, Counter> counters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    @Autowired
    public ResponseCache(GatewayProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        this(properties.getRest(), meterRegistry.getIfAvailable(), System::nanoTime);
    }

    /** Test seam: an injectable clock in nanoseconds. */
    ResponseCache(RestConfig rest, MeterRegistry meterRegistry, LongSupplier clock) {
        RestConfig.ResponseCacheConfig config =
                rest.getResponseCache() == null ? new RestConfig.ResponseCacheConfig() : rest.getResponseCache();
        this.enabled = config.isEnabled();
        this.coalesce = rest.isCoalesceRequests();
        this.maxEntrySize = config.getMaxEntrySize() == null
                ? Long.MAX_VALUE
                : config.getMaxEntrySize().toBytes();
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.entries = Caffeine.newBuilder()
                .maximumWeight(
                        config.getMaxSize() == null
                                ? 64L << 20
                                : config.getMaxSize().toBytes())
                // The body dominates an entry; the constant covers the key and headers.
                .weigher((Key k, Entry e) ->
                        (int) Math.min(Integer.MAX_VALUE, e.response().body().length + 256L))
                .expireAfter(new Expiry<Key, Entry>() {
                    @Override
                    public long expireAfterCreate(Key key, Entry entry, long currentTime) {
                        return Math.max(0, entry.staleUntil() - clock.getAsLong());
                    }

                    @Override
                    public long expireAfterUpdate(Key key, Entry entry, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, entry, currentTime);
                    }

                    @Override
                    public long expireAfterRead(Key key, Entry entry, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .ticker(clock::getAsLong)
                .build();
    }

    /** {@code rest.response-cache.enabled}. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Whether a request with this policy goes through here at all: to be cached, or to be coalesced. */
    boolean applies(CachePolicy policy) {
        return coalesce || (policy != null && policy.serverCache());
    }

    /**
     * The answer for {@code key}: from the cache when {@code policy} keeps it and a live entry
     * exists, else from {@code loader} — this caller's call, or one already in flight for the key.
     *
     * <p>The loader runs with the caller's Reactor context but is not cancelled when the caller
     * goes away: other callers may be waiting on it, and its answer is worth keeping regardless.
     */
    Mono<Served> get(Key key, CachePolicy policy, Mono<RenderedResponse> loader) {
        boolean store = policy != null && policy.serverCache();
        return Mono.deferContextual(view -> {
            Mono<RenderedResponse> contextual = loader.contextWrite(Context.of(view));
            if (store) {
                Entry entry = entries.getIfPresent(key);
                long now = clock.getAsLong();
                if (entry != null && now < entry.freshUntil()) {
                    return Mono.just(served(entry, Outcome.HIT, now));
                }
                if (entry != null && now < entry.staleUntil()) {
                    // Refresh once in the background; callers keep getting the stale entry meanwhile.
                    join(key, policy, true, contextual);
                    return Mono.just(served(entry, Outcome.STALE, now));
                }
            }
            return Mono.defer(() -> {
                Joined joined = join(key, policy, store, contextual);
                Outcome outcome = joined.owner() ? Outcome.MISS : Outcome.COALESCED;
                count(outcome);
                return Mono.fromFuture(joined.future(), true).map(r -> new Served(r, outcome, 0, store && storable(r)));
            });
        });
    }

    private record Joined(CompletableFuture<RenderedResponse> future, boolean owner) {}

    /** Starts the call for {@code key}, or joins the one in flight. */
    private Joined join(Key key, CachePolicy policy, boolean store, Mono<RenderedResponse> loader) {
        CompletableFuture<RenderedResponse> mine = new CompletableFuture<>();
        CompletableFuture<RenderedResponse> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) return new Joined(existing, false);
        loader.subscribe(
                response -> {
                    if (store && storable(response)) put(key, policy, response);
                    inFlight.remove(key, mine);
                    mine.complete(response);
                },
                error -> {
                    inFlight.remove(key, mine);
                    mine.completeExceptionally(error);
                },
                () -> {
                    // An empty loader is a writer that answered by itself; nothing to share.
                    inFlight.remove(key, mine);
                    if (!mine.isDone()) mine.completeExceptionally(new IllegalStateException("no response"));
                });
        return new Joined(mine, true);
    }

    private void put(Key key, CachePolicy policy, RenderedResponse response) {
        long now = clock.getAsLong();
        long fresh = now + policy.ttl().toNanos();
        long staleUntil = fresh + policy.staleWhileRevalidate().toNanos();
        entries.put(key, new Entry(response, now, fresh, staleUntil));
    }

    /**
     * Kept only when small enough and not setting a cookie, which is one caller's alone. Only
     * {@code getFirst} is used on the headers: this class runs on Spring 6 and 7 alike.
     */
    private boolean storable(RenderedResponse response) {
        return response.body().length <= maxEntrySize && response.headers().getFirst(HttpHeaders.SET_COOKIE) == null;
    }

    private Served served(Entry entry, Outcome outcome, long now) {
        count(outcome);
        return new Served(entry.response(), outcome, Math.max(0, (now - entry.storedAt()) / 1_000_000_000L), true);
    }

    private void count(Outcome outcome) {
        switch (outcome) {
            case HIT -> hits.incrementAndGet();
            case STALE -> stale.incrementAndGet();
            case MISS -> misses.incrementAndGet();
            case COALESCED -> coalesced.incrementAndGet();
        }
        if (meterRegistry != null) {
            counters.computeIfAbsent(
                            outcome,
                            o -> Counter.builder("shiguchi.rest.response.cache")
                                    .description("REST responses by where they came from")
                                    .tag("result", o.name().toLowerCase(java.util.Locale.ROOT))
                                    .register(meterRegistry))
                    .increment();
        }
    }

    /** Drops every entry for {@code path}: a write to a resource makes what was read of it stale. */
    void invalidatePath(String path) {
        // Not gated on rest.response-cache.enabled: a cache rule's server-cache may store regardless.
        if (entries.estimatedSize() == 0) return;
        entries.asMap().keySet().removeIf(k -> k.path().equals(path));
    }

    /** Drops every entry, returning how many there were. */
    public long clear() {
        long size = entries.estimatedSize();
        entries.invalidateAll();
        return size;
    }

    /** Drops every entry of one configured service, returning how many were dropped. */
    public long evictService(String serviceName) {
        long before = entries.estimatedSize();
        entries.asMap().keySet().removeIf(k -> k.service().equals(serviceName));
        return before - entries.estimatedSize();
    }

    /** Counters and sizes, for the {@code gateway-cache} actuator endpoint. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("coalesceRequests", coalesce);
        entries.cleanUp();
        out.put("entries", entries.estimatedSize());
        out.put(
                "weightBytes",
                entries.policy().eviction().map(e -> e.weightedSize().orElse(0)).orElse(0L));
        out.put("hits", hits.get());
        out.put("stale", stale.get());
        out.put("misses", misses.get());
        out.put("coalesced", coalesced.get());
        out.put("inFlight", inFlight.size());
        return out;
    }

    /** A response is derived from the schema, so a reload may change every one of them. */
    @Override
    public void onSchemaReloaded() {
        entries.invalidateAll();
    }
}
