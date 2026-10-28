package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dead-letter queue: records a source could not map (no topic, bad payload) and events the
 * gateway could not publish (unknown namespace, too large). A bounded ring per instance
 * ({@code dlq.max-entries}); operators list, retry (publish the payload again, to the recorded
 * topic or another) and delete entries through the endpoint and the console.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class PushDlq {

    /**
     * One dead-lettered record.
     *
     * @param topic the topic it was meant for, or null when mapping it failed
     * @param data the payload: the event's JSON, or the raw record when it could not be mapped
     * @param typeUrl and {@code proto}: the typed payload, when there was one
     */
    public record Entry(
            String id,
            Instant time,
            String source,
            String reason,
            String topic,
            String key,
            byte[] data,
            Map<String, String> attributes,
            String typeUrl,
            byte[] proto) {}

    private final int max;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long added;

    public PushDlq(int max) {
        this.max = Math.max(1, max);
    }

    public int max() {
        return max;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** How many entries were ever added on this instance. */
    public synchronized long added() {
        return added;
    }

    /** Records one entry; the oldest leaves when the ring is full. */
    public synchronized Entry add(
            String source,
            String reason,
            String topic,
            String key,
            byte[] data,
            Map<String, String> attributes,
            String typeUrl,
            byte[] proto) {
        Entry e = new Entry(
                UUID.randomUUID().toString(),
                Instant.now(),
                source,
                reason,
                topic,
                key,
                data == null ? new byte[0] : data,
                attributes == null ? Map.of() : Map.copyOf(attributes),
                typeUrl,
                proto);
        entries.addFirst(e);
        added++;
        while (entries.size() > max) entries.removeLast();
        return e;
    }

    /** The entry of a mapped event that could not be published. */
    public Entry add(String source, String reason, SourcedEvent event) {
        return add(
                source,
                reason,
                event.topic().toString(),
                event.key(),
                event.data(),
                event.attributes(),
                event.typeUrl(),
                event.proto());
    }

    /** Newest first, at most {@code limit}, from {@code source} only when set. */
    public synchronized List<Entry> list(String source, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (source != null && !source.isBlank() && !source.equals(e.source())) continue;
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public synchronized Entry get(String id) {
        for (Entry e : entries) if (e.id().equals(id)) return e;
        return null;
    }

    public synchronized boolean remove(String id) {
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().id().equals(id)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** An entry as the admin views show it; the payload only under {@code FULL} visibility. */
    public static Map<String, Object> render(Entry e, PushProperties.PayloadVisibility visibility) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("time", e.time().toString());
        m.put("source", e.source());
        m.put("reason", e.reason());
        m.put("topic", e.topic());
        m.put("bytes", e.data().length);
        m.put("typeUrl", e.typeUrl());
        if (visibility != PushProperties.PayloadVisibility.NONE) {
            m.put("key", e.key());
            m.put("attributes", e.attributes());
        }
        if (visibility == PushProperties.PayloadVisibility.FULL) {
            m.put("data", text(e.data()));
            if (e.proto() != null) m.put("proto", Base64.getEncoder().encodeToString(e.proto()));
        }
        return m;
    }

    /** The bytes as text when they are valid UTF-8 without control characters, else base64. */
    static String text(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.indexOf('�') >= 0) return "base64:" + Base64.getEncoder().encodeToString(data);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                return "base64:" + Base64.getEncoder().encodeToString(data);
            }
        }
        return s;
    }
}
