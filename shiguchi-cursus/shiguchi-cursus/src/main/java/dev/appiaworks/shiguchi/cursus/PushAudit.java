package dev.appiaworks.shiguchi.cursus;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last writes an operator made through the {@code gateway-push} endpoint on this instance:
 * who, what, with which arguments, with what result, when. A bounded ring, newest first on read.
 */
public final class PushAudit {

    /** One admin write. */
    public record Entry(Instant time, String principal, String action, Map<String, Object> arguments, String result) {}

    private final int capacity;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public PushAudit() {
        this(1000);
    }

    public PushAudit(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be above 0");
        this.capacity = capacity;
    }

    public void record(String principal, String action, Map<String, Object> arguments, String result) {
        Entry e = new Entry(
                Instant.now(),
                principal == null ? "anonymous" : principal,
                action,
                arguments == null ? Map.of() : new LinkedHashMap<>(arguments),
                result);
        synchronized (entries) {
            entries.addLast(e);
            while (entries.size() > capacity) entries.pollFirst();
        }
    }

    /** Newest first, at most {@code limit}. */
    public List<Entry> list(int limit) {
        List<Entry> out = new ArrayList<>();
        synchronized (entries) {
            Iterator<Entry> newestFirst = entries.descendingIterator();
            while (newestFirst.hasNext() && out.size() < limit) out.add(newestFirst.next());
        }
        return out;
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
