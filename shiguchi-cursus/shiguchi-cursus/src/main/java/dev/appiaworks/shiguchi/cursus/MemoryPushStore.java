package dev.appiaworks.shiguchi.cursus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Ids from one process-wide counter, history in a bounded deque per topic. Ids are 19-digit
 * zero-padded decimals, so they compare as strings as well as numbers; the counter starts from the
 * clock so a restarted instance's ids stay above the previous run's for clients that resume.
 */
public final class MemoryPushStore implements PushStore {

    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() * 1_000_000L);
    private final ConcurrentHashMap<Topic, History> histories = new ConcurrentHashMap<>();

    @Override
    public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
        PushEvent stored = event.withId(formatId(sequence.incrementAndGet()));
        if (historySize > 0) {
            histories.computeIfAbsent(event.topic(), t -> new History()).add(stored, historySize, historyTtl);
        }
        return Mono.just(stored);
    }

    @Override
    public boolean explicitIds() {
        return true;
    }

    @Override
    public Mono<PushEvent> appendWithId(PushEvent event, long id, int historySize, Duration historyTtl) {
        PushEvent stored = event.withId(formatId(id));
        if (historySize <= 0) return Mono.just(stored);
        boolean added = histories
                .computeIfAbsent(event.topic(), t -> new History())
                .addIfAfter(stored, id, historySize, historyTtl);
        return added
                ? Mono.just(stored)
                : Mono.error(PushException.conflict(
                        "event id " + id + " is not above the newest id kept on " + event.topic()));
    }

    @Override
    public Mono<PushEvent> headEvent(Topic topic) {
        History history = histories.get(topic);
        PushEvent head = history == null ? null : history.lastEvent();
        return head == null ? Mono.empty() : Mono.just(head);
    }

    @Override
    public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
        History history = histories.get(topic);
        if (history == null) return Flux.empty();
        long after = afterId == null ? Long.MIN_VALUE : parseId(afterId);
        return Flux.fromIterable(history.after(after, limit));
    }

    @Override
    public Mono<String> trimmedThrough(Topic topic) {
        History history = histories.get(topic);
        String id = history == null ? null : history.trimmedThrough();
        return id == null ? Mono.empty() : Mono.just(id);
    }

    @Override
    public int compareIds(String a, String b) {
        return Long.compare(parseId(a), parseId(b));
    }

    @Override
    public long historyLength(Topic topic) {
        History history = histories.get(topic);
        return history == null ? 0 : history.size();
    }

    private record Cursor(String id, long expiresAtNanos) {}

    private final ConcurrentHashMap<String, ConcurrentHashMap<Topic, Cursor>> sessions = new ConcurrentHashMap<>();

    @Override
    public boolean persistentSessions() {
        return true;
    }

    @Override
    public Mono<String> headId(Topic topic) {
        History history = histories.get(topic);
        String head = history == null ? null : history.last();
        return head == null ? Mono.empty() : Mono.just(head);
    }

    @Override
    public Mono<String> cursor(String session, Topic topic) {
        ConcurrentHashMap<Topic, Cursor> topics = sessions.get(session);
        if (topics == null) return Mono.empty();
        Cursor c = topics.get(topic);
        if (c == null) return Mono.empty();
        if (c.expiresAtNanos() < System.nanoTime()) {
            topics.remove(topic, c);
            return Mono.empty();
        }
        return Mono.just(c.id());
    }

    @Override
    public Mono<Void> ack(String session, Topic topic, String id, Duration expiry) {
        long until = System.nanoTime() + (expiry == null ? Long.MAX_VALUE / 4 : expiry.toNanos());
        sessions.computeIfAbsent(session, s -> new ConcurrentHashMap<>()).compute(topic, (t, current) -> {
            if (current != null && current.expiresAtNanos() >= System.nanoTime() && compareIds(current.id(), id) >= 0) {
                return new Cursor(current.id(), until);
            }
            return new Cursor(id, until);
        });
        return Mono.empty();
    }

    @Override
    public Flux<SessionCursor> sessions(String prefix, int limit) {
        List<SessionCursor> out = new ArrayList<>();
        long now = System.nanoTime();
        List<String> names = new ArrayList<>(sessions.keySet());
        java.util.Collections.sort(names);
        for (String name : names) {
            if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) continue;
            ConcurrentHashMap<Topic, Cursor> topics = sessions.get(name);
            if (topics == null) continue;
            for (Map.Entry<Topic, Cursor> e : topics.entrySet()) {
                if (e.getValue().expiresAtNanos() < now) continue;
                out.add(new SessionCursor(
                        name,
                        e.getKey(),
                        e.getValue().id(),
                        Instant.now().plusNanos(e.getValue().expiresAtNanos() - now)));
                if (out.size() >= limit) return Flux.fromIterable(out);
            }
        }
        return Flux.fromIterable(out);
    }

    @Override
    public Mono<Long> dropSession(String session) {
        ConcurrentHashMap<Topic, Cursor> topics = sessions.remove(session);
        return Mono.just(topics == null ? 0L : (long) topics.size());
    }

    @Override
    public Mono<Long> sweepSessions() {
        long now = System.nanoTime();
        long[] gone = {0};
        sessions.forEach((name, topics) -> {
            topics.entrySet().removeIf(e -> {
                boolean expired = e.getValue().expiresAtNanos() < now;
                if (expired) gone[0]++;
                return expired;
            });
            if (topics.isEmpty()) sessions.remove(name, topics);
        });
        return Mono.just(gone[0]);
    }

    private record Registered(ConnectionRecord record, long expiresAtNanos) {}

    private final ConcurrentHashMap<String, Registered> connectionRows = new ConcurrentHashMap<>();

    @Override
    public boolean connectionRegistry() {
        return true;
    }

    @Override
    public Mono<Void> registerConnections(List<ConnectionRecord> records, Duration ttl) {
        long until = System.nanoTime() + ttl.toNanos();
        for (ConnectionRecord r : records) connectionRows.put(r.id(), new Registered(r, until));
        return Mono.empty();
    }

    @Override
    public Mono<Void> unregisterConnection(String id) {
        connectionRows.remove(id);
        return Mono.empty();
    }

    /** The live rows, oldest first (ties by id); a lapsed row is dropped as it is met. */
    private List<ConnectionRecord> liveConnections() {
        long now = System.nanoTime();
        List<ConnectionRecord> out = new ArrayList<>();
        connectionRows.forEach((id, row) -> {
            if (row.expiresAtNanos() < now) connectionRows.remove(id, row);
            else out.add(row.record());
        });
        out.sort(java.util.Comparator.comparing(ConnectionRecord::since).thenComparing(ConnectionRecord::id));
        return out;
    }

    @Override
    public Flux<ConnectionRecord> connections(String principal, int limit) {
        return Flux.fromStream(liveConnections().stream()
                .filter(r -> principal == null || principal.equals(r.principal()))
                .limit(Math.max(0, limit)));
    }

    @Override
    public Mono<ConnectionRecord> connection(String id) {
        return Mono.justOrEmpty(
                liveConnections().stream().filter(r -> r.id().equals(id)).findFirst());
    }

    @Override
    public Flux<ConnectionRecord> connectionsOn(Topic topic, int offset, int limit) {
        String name = topic.toString();
        return Flux.fromStream(liveConnections().stream()
                .filter(r -> r.presenceTopics().contains(name))
                .skip(Math.max(0, offset))
                .limit(Math.max(0, limit)));
    }

    @Override
    public Mono<Long> countConnectionsOn(Topic topic) {
        String name = topic.toString();
        return Mono.just(liveConnections().stream()
                .filter(r -> r.presenceTopics().contains(name))
                .count());
    }

    private static final class Pending {
        final PushEvent event;
        String member;
        long claimedAtNanos;
        int deliveries;

        Pending(PushEvent event, String member, long claimedAtNanos) {
            this.event = event;
            this.member = member;
            this.claimedAtNanos = claimedAtNanos;
            this.deliveries = 1;
        }
    }

    private static final class Group {
        String cursor;
        final java.util.LinkedHashMap<String, Pending> pending = new java.util.LinkedHashMap<>();
    }

    private final ConcurrentHashMap<String, Group> groups = new ConcurrentHashMap<>();

    @Override
    public boolean consumerGroups() {
        return true;
    }

    @Override
    public Flux<Claimed> claim(
            String group, Topic topic, String member, int max, Duration ackDeadline, Duration expiry) {
        if (max <= 0) return Flux.empty();
        Group g = groups.computeIfAbsent(group + "|" + topic, k -> new Group());
        List<Claimed> out = new ArrayList<>();
        long now = System.nanoTime();
        long idle = ackDeadline == null ? Long.MAX_VALUE / 4 : ackDeadline.toNanos();
        synchronized (g) {
            for (Pending p : g.pending.values()) {
                if (out.size() >= max) break;
                if (now - p.claimedAtNanos < idle) continue;
                p.member = member;
                p.claimedAtNanos = now;
                p.deliveries++;
                out.add(new Claimed(p.event, p.deliveries));
            }
            History history = histories.get(topic);
            if (g.cursor == null) {
                String head = history == null ? null : history.last();
                g.cursor = head == null ? firstId() : head;
            }
            int room = max - out.size();
            if (room > 0 && history != null) {
                for (PushEvent e : history.after(parseId(g.cursor), room)) {
                    g.pending.put(e.id(), new Pending(e, member, now));
                    out.add(new Claimed(e, 1));
                    g.cursor = e.id();
                }
            }
        }
        return Flux.fromIterable(out);
    }

    @Override
    public boolean keyOrdering() {
        return true;
    }

    @Override
    public Flux<Claimed> claim(
            String group,
            Topic topic,
            String member,
            int max,
            Duration ackDeadline,
            Duration expiry,
            PushProperties.Ordering ordering) {
        if (ordering != PushProperties.Ordering.KEY) return claim(group, topic, member, max, ackDeadline, expiry);
        if (max <= 0) return Flux.empty();
        Group g = groups.computeIfAbsent(group + "|" + topic, k -> new Group());
        List<Claimed> out = new ArrayList<>();
        long now = System.nanoTime();
        long idle = ackDeadline == null ? Long.MAX_VALUE / 4 : ackDeadline.toNanos();
        synchronized (g) {
            History history = histories.get(topic);
            if (g.cursor == null) {
                String head = history == null ? null : history.last();
                g.cursor = head == null ? firstId() : head;
            }
            if (history != null) {
                for (PushEvent e : history.after(parseId(g.cursor), Integer.MAX_VALUE)) {
                    Pending p = new Pending(e, null, 0);
                    p.deliveries = 0;
                    g.pending.put(e.id(), p);
                    g.cursor = e.id();
                }
            }
            // Walk in id order: a key another member holds live, or whose earlier event this call could
            // not take, blocks the rest of that key, so ids of one key go out in order to one member.
            java.util.Set<String> blocked = new java.util.HashSet<>();
            java.util.Map<String, String> liveHolder = new java.util.HashMap<>();
            for (Pending q : g.pending.values()) {
                String k = q.event.key();
                if (k != null && !k.isEmpty() && q.member != null && now - q.claimedAtNanos < idle) {
                    liveHolder.merge(k, q.member, (a, b) -> a.equals(b) ? a : "*");
                }
            }
            for (Pending p : g.pending.values()) {
                String key = p.event.key();
                boolean keyed = key != null && !key.isEmpty();
                if (keyed && blocked.contains(key)) continue;
                boolean live = p.member != null && now - p.claimedAtNanos < idle;
                if (live) {
                    if (keyed && !p.member.equals(member)) blocked.add(key);
                    continue;
                }
                if (out.size() >= max) {
                    if (keyed) blocked.add(key);
                    continue;
                }
                String holder = keyed ? liveHolder.get(key) : null;
                if (holder != null && !holder.equals(member)) {
                    blocked.add(key);
                    continue;
                }
                p.member = member;
                p.claimedAtNanos = now;
                p.deliveries++;
                out.add(new Claimed(p.event, p.deliveries));
            }
        }
        return Flux.fromIterable(out);
    }

    @Override
    public Mono<Boolean> ackGroup(String group, Topic topic, String member, String id) {
        Group g = groups.get(group + "|" + topic);
        if (g == null) return Mono.just(false);
        synchronized (g) {
            Pending p = g.pending.get(id);
            if (p == null || p.member == null || !p.member.equals(member)) return Mono.just(false);
            g.pending.remove(id);
            return Mono.just(true);
        }
    }

    @Override
    public Mono<Long> releaseClaims(String group, Topic topic, String member) {
        Group g = groups.get(group + "|" + topic);
        if (g == null) return Mono.just(0L);
        long n = 0;
        synchronized (g) {
            for (Pending p : g.pending.values()) {
                if (member.equals(p.member)) {
                    p.claimedAtNanos = Long.MIN_VALUE / 2;
                    n++;
                }
            }
        }
        return Mono.just(n);
    }

    @Override
    public Flux<GroupStatus> groups(String prefix, int limit) {
        List<GroupStatus> out = new ArrayList<>();
        List<String> keys = new ArrayList<>(groups.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            int bar = key.indexOf('|');
            String name = key.substring(0, bar);
            if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) continue;
            Group g = groups.get(key);
            if (g == null) continue;
            synchronized (g) {
                long members = g.pending.values().stream()
                        .map(p -> p.member)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .count();
                out.add(new GroupStatus(
                        name, Topic.parse(key.substring(bar + 1)), g.pending.size(), members, g.cursor));
            }
            if (out.size() >= limit) break;
        }
        return Flux.fromIterable(out);
    }

    @Override
    public Mono<Long> dropGroup(String group) {
        long n = 0;
        for (String key : new ArrayList<>(groups.keySet())) {
            if (key.startsWith(group + "|") && groups.remove(key) != null) n++;
        }
        return Mono.just(n);
    }

    @Override
    public Mono<Boolean> seekGroup(String group, Topic topic, String id) {
        Group g = groups.get(group + "|" + topic);
        if (g == null) return Mono.just(false);
        synchronized (g) {
            g.pending.clear();
            g.cursor = id;
        }
        return Mono.just(true);
    }

    @Override
    public Mono<Boolean> seekSession(String session, Topic topic, String id) {
        ConcurrentHashMap<Topic, Cursor> topics = sessions.get(session);
        if (topics == null) return Mono.just(false);
        boolean[] moved = {false};
        topics.computeIfPresent(topic, (t, current) -> {
            if (current.expiresAtNanos() < System.nanoTime()) return current;
            moved[0] = true;
            return new Cursor(id, current.expiresAtNanos());
        });
        return Mono.just(moved[0]);
    }

    private record Lease(String holder, long expiresAtNanos) {}

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    @Override
    public Mono<Boolean> lease(String key, String holder, Duration ttl) {
        long now = System.nanoTime();
        Lease next = new Lease(holder, now + ttl.toNanos());
        Lease result = leases.compute(
                key,
                (k, current) ->
                        current == null || current.holder().equals(holder) || current.expiresAtNanos() - now <= 0
                                ? next
                                : current);
        return Mono.just(result == next);
    }

    @Override
    public Mono<Void> release(String key, String holder) {
        leases.computeIfPresent(key, (k, current) -> current.holder().equals(holder) ? null : current);
        return Mono.empty();
    }

    private final Map<String, List<PushDelivery>> deliveries =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<PushDelivery>> eldest) {
                    return size() > 10_000;
                }
            });

    @Override
    public Mono<Void> recordDeliveries(List<PushDelivery> batch) {
        for (PushDelivery d : batch) {
            deliveries
                    .computeIfAbsent(
                            d.topic() + "|" + d.eventId(),
                            k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                    .add(d);
        }
        return Mono.empty();
    }

    @Override
    public Flux<PushDelivery> deliveries(Topic topic, String eventId, int limit) {
        List<PushDelivery> all = deliveries.get(topic + "|" + eventId);
        if (all == null) return Flux.empty();
        synchronized (all) {
            return Flux.fromIterable(new ArrayList<>(all.subList(0, Math.min(limit, all.size()))));
        }
    }

    @Override
    public Mono<Map<String, Long>> deliveryCounts(Topic topic, String eventId) {
        List<PushDelivery> all = deliveries.get(topic + "|" + eventId);
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        if (all != null) {
            synchronized (all) {
                for (PushDelivery d : all) counts.merge(d.outcome(), 1L, Long::sum);
            }
        }
        return Mono.just(counts);
    }

    @Override
    public boolean deliveryLog() {
        return true;
    }

    @Override
    public Mono<Boolean> redact(Topic topic, String id) {
        History history = histories.get(topic);
        return Mono.just(history != null && history.remove(id));
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false);
    }

    static String formatId(long sequence) {
        return String.format("%019d", sequence);
    }

    static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an event id: '" + id + "'");
        }
    }

    private static final class History {
        private final ArrayDeque<PushEvent> events = new ArrayDeque<>();
        private long trimmedThrough = -1;

        synchronized void add(PushEvent event, int size, Duration ttl) {
            events.addLast(event);
            while (events.size() > size) trimmed(events.pollFirst());
            evictExpired(ttl);
        }

        /** The highest id the size trim or the expiry took out (a redaction does not count); null when none. */
        synchronized String trimmedThrough() {
            return trimmedThrough < 0 ? null : formatId(trimmedThrough);
        }

        private void trimmed(PushEvent removed) {
            trimmedThrough = Math.max(trimmedThrough, parseId(removed.id()));
        }

        synchronized boolean addIfAfter(PushEvent event, long id, int size, Duration ttl) {
            PushEvent last = events.peekLast();
            if (last != null && parseId(last.id()) >= id) return false;
            add(event, size, ttl);
            return true;
        }

        synchronized PushEvent lastEvent() {
            return events.peekLast();
        }

        synchronized List<PushEvent> after(long afterId, int limit) {
            List<PushEvent> out = new ArrayList<>();
            for (PushEvent e : events) {
                if (parseId(e.id()) > afterId) {
                    out.add(e);
                    if (out.size() >= limit) break;
                }
            }
            return out;
        }

        synchronized int size() {
            return events.size();
        }

        synchronized String last() {
            PushEvent e = events.peekLast();
            return e == null ? null : e.id();
        }

        synchronized boolean remove(String id) {
            return events.removeIf(e -> e.id().equals(id));
        }

        private void evictExpired(Duration ttl) {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) return;
            Instant cutoff = Instant.now().minus(ttl);
            while (!events.isEmpty() && events.peekFirst().time().isBefore(cutoff)) trimmed(events.pollFirst());
        }
    }
}
