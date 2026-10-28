package dev.appiaworks.shiguchi.cursus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The archive of the {@code MEMORY} engine: one ring per instance, bounded by
 * {@code archive.memory-max-events} and {@code archive.retention}. Text search is a
 * case-insensitive substring match over the JSON payload. Per instance like the rest of the
 * in-memory engine: fine for one instance and for tests.
 */
public final class MemoryPushArchive implements PushArchive {

    private static final byte[] NULL = "null".getBytes(StandardCharsets.UTF_8);

    private final int maxEvents;
    private final Duration retention;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public MemoryPushArchive(int maxEvents, Duration retention) {
        if (maxEvents <= 0) throw new IllegalArgumentException("archive.memory-max-events must be above 0");
        this.maxEvents = maxEvents;
        this.retention = retention == null ? Duration.ZERO : retention;
    }

    @Override
    public Mono<Void> append(PushEvent event) {
        if (event.id() == null) return Mono.error(new IllegalArgumentException("an archived event needs its id"));
        synchronized (entries) {
            entries.addLast(new Entry(event, null, null));
            while (entries.size() > maxEvents) entries.pollFirst();
            evictExpired();
        }
        return Mono.empty();
    }

    @Override
    public Flux<Entry> search(Query query) {
        TopicPattern pattern = query.topicPattern();
        List<Entry> out = new ArrayList<>();
        synchronized (entries) {
            evictExpired();
            Iterator<Entry> newestFirst = entries.descendingIterator();
            while (newestFirst.hasNext() && out.size() < query.limit()) {
                Entry entry = newestFirst.next();
                if (matches(entry, query, pattern)) out.add(entry);
            }
        }
        return Flux.fromIterable(out);
    }

    @Override
    public Mono<Entry> get(Topic topic, String id) {
        synchronized (entries) {
            for (Entry e : entries) {
                if (e.event().id().equals(id) && e.event().topic().equals(topic)) return Mono.just(e);
            }
        }
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> tombstone(Topic topic, String id, String by) {
        synchronized (entries) {
            List<Entry> copy = new ArrayList<>(entries);
            for (int i = 0; i < copy.size(); i++) {
                Entry e = copy.get(i);
                if (!e.event().id().equals(id) || !e.event().topic().equals(topic)) continue;
                if (e.tombstoned()) return Mono.just(false);
                PushEvent redacted = new PushEvent(
                        e.event().topic(),
                        e.event().id(),
                        NULL,
                        e.event().attributes(),
                        e.event().key(),
                        e.event().time());
                copy.set(i, new Entry(redacted, Instant.now(), by));
                entries.clear();
                entries.addAll(copy);
                return Mono.just(true);
            }
        }
        return Mono.just(false);
    }

    @Override
    public SearchCapabilities searchCapabilities() {
        return new SearchCapabilities(true, true, true, retention);
    }

    /** How many events are kept right now. */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private static boolean matches(Entry entry, Query q, TopicPattern pattern) {
        PushEvent e = entry.event();
        if (pattern != null && !pattern.matches(e.topic())) return false;
        if (q.from() != null && e.time().isBefore(q.from())) return false;
        if (q.to() != null && !e.time().isBefore(q.to())) return false;
        if (q.afterId() != null && e.id().compareTo(q.afterId()) <= 0) return false;
        if (q.beforeId() != null && e.id().compareTo(q.beforeId()) >= 0) return false;
        for (Map.Entry<String, String> a : q.attributes().entrySet()) {
            if (!a.getValue().equals(e.attributes().get(a.getKey()))) return false;
        }
        if (q.typeUrl() != null) {
            if ("json".equals(q.typeUrl()) ? e.typed() : !q.typeUrl().equals(e.typeUrl())) return false;
        }
        if (q.text() != null) {
            String data = new String(e.data(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (!data.contains(q.text().toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private void evictExpired() {
        if (retention.isZero() || retention.isNegative()) return;
        Instant cutoff = Instant.now().minus(retention);
        while (!entries.isEmpty() && entries.peekFirst().event().time().isBefore(cutoff)) entries.pollFirst();
    }
}
