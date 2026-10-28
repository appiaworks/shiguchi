package dev.appiaworks.shiguchi.cursus;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The wire bytes of an event for gRPC subscribers, computed once per event rather than once per
 * subscriber: the engine hands the same {@link PushEvent} instance to every local subscriber of a
 * topic, so a thousand streams share one JSON-to-{@code Value} conversion (or none, for a typed
 * event) and one proto encode, and each stream writes the same {@code byte[]}. Keyed by identity,
 * bounded by a ring of the most recent events: an event falls out once {@code capacity} newer ones
 * have been delivered, long after its fan-out is over.
 */
final class PushGrpcFrames {

    /** Identity, not record equality: the same event object is what the subscribers share. */
    private record Key(PushEvent event) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.event == event;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(event);
        }
    }

    private final ConcurrentHashMap<Key, byte[]> frames;
    private final AtomicReferenceArray<Key> recent;
    private final AtomicInteger cursor = new AtomicInteger();
    private final int mask;

    PushGrpcFrames() {
        this(4096);
    }

    PushGrpcFrames(int capacity) {
        if (Integer.bitCount(capacity) != 1) throw new IllegalArgumentException("capacity must be a power of two");
        this.frames = new ConcurrentHashMap<>(capacity * 2);
        this.recent = new AtomicReferenceArray<>(capacity);
        this.mask = capacity - 1;
    }

    /** The serialized {@code shiguchi.cursus.v1.PushEvent} for {@code event}. */
    byte[] frame(PushEvent event) {
        Key key = new Key(event);
        byte[] frame = frames.get(key);
        if (frame != null) return frame;
        frame = PushEventProtos.toProto(event).toByteArray();
        byte[] won = frames.putIfAbsent(key, frame);
        if (won != null) return won;
        // Remember it as recent; whatever this slot held before is old enough to forget.
        Key evicted = recent.getAndSet(cursor.getAndIncrement() & mask, key);
        if (evicted != null) frames.remove(evicted);
        return frame;
    }

    int size() {
        return frames.size();
    }
}
