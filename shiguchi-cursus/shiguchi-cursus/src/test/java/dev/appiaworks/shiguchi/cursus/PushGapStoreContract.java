package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * What every {@link PushStore} flavour that detects a history gap must answer from
 * {@link PushStore#trimmedThrough(Topic)}: empty when nothing was removed, the highest removed id
 * after a size trim or an expiry (also once the rows are physically deleted), nothing for a
 * redacted event. Each store test class calls the scenarios against its own engine.
 */
public final class PushGapStoreContract {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DEADLINE = Duration.ofSeconds(15);

    /**
     * How the store under test is reached: {@code sweep} runs its physical delete (null: none),
     * {@code redact} tombstones an event.
     */
    public record Engine(PushStore store, Runnable sweep, BiConsumer<Topic, String> redact) {}

    private PushGapStoreContract() {}

    /** Nothing removed from the history: no watermark. */
    public static void nothingTrimmed(Engine engine) {
        Topic topic = Topic.parse("gap:nothing");
        append(engine.store(), topic, 5, HOUR);
        append(engine.store(), topic, 5, HOUR);

        assertThat(engine.store().trimmedThrough(topic).block(DEADLINE)).isNull();
        assertThat(engine.store().trimmedThrough(Topic.parse("gap:unknown")).block(DEADLINE))
                .isNull();
    }

    /** Five events into a history of three: the two oldest went, the watermark is the second. */
    public static void trimmedBySize(Engine engine) {
        PushStore store = engine.store();
        Topic topic = Topic.parse("gap:size");
        PushEvent e1 = append(store, topic, 3, HOUR);
        PushEvent e2 = append(store, topic, 3, HOUR);
        PushEvent e3 = append(store, topic, 3, HOUR);
        append(store, topic, 3, HOUR);
        append(store, topic, 3, HOUR);

        await(() -> e2.id().equals(store.trimmedThrough(topic).block(DEADLINE)));

        String watermark = store.trimmedThrough(topic).block(DEADLINE);
        assertThat(watermark).isEqualTo(e2.id());
        assertThat(store.compareIds(e1.id(), watermark)).isNegative();
        assertThat(store.compareIds(e2.id(), watermark)).isZero();
        assertThat(store.compareIds(e3.id(), watermark)).isPositive();
    }

    /**
     * Two events past their ttl, then a newer one: the watermark is the second expired one. The
     * in-memory store evicts against the ttl of the event being appended, so the newest one carries
     * a ttl the two old ones are already older than.
     */
    public static void expiredByTtl(Engine engine) throws InterruptedException {
        PushStore store = engine.store();
        Topic topic = Topic.parse("gap:ttl");
        PushEvent e1 = append(store, topic, 10, Duration.ofSeconds(1));
        PushEvent e2 = append(store, topic, 10, Duration.ofSeconds(1));
        Thread.sleep(2500);
        PushEvent e3 = append(store, topic, 10, Duration.ofSeconds(2));

        String watermark = store.trimmedThrough(topic).block(DEADLINE);

        assertThat(watermark).isEqualTo(e2.id());
        assertThat(store.compareIds(e1.id(), watermark)).isNegative();
        assertThat(store.compareIds(e3.id(), watermark)).isPositive();
    }

    /** Expired rows the sweep then deleted still count: the watermark outlives the rows. */
    public static void sweptAfterExpiry(Engine engine) throws InterruptedException {
        PushStore store = engine.store();
        Topic topic = Topic.parse("gap:swept");
        append(store, topic, 10, Duration.ofSeconds(1));
        PushEvent e2 = append(store, topic, 10, Duration.ofSeconds(1));
        append(store, topic, 10, HOUR);
        Thread.sleep(1500);

        engine.sweep().run();

        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isEqualTo(e2.id());
        assertThat(store.range(topic, null, 10).count().block(DEADLINE)).isEqualTo(1L);
    }

    /** A redacted event is intentional, not a gap, before and after the sweep deleted it. */
    public static void redactedIsNotAGap(Engine engine) {
        PushStore store = engine.store();
        Topic topic = Topic.parse("gap:redacted");
        PushEvent e1 = append(store, topic, 10, HOUR);
        append(store, topic, 10, HOUR);

        engine.redact().accept(topic, e1.id());

        assertThat(store.trimmedThrough(topic).block(DEADLINE)).isNull();
        if (engine.sweep() != null) {
            engine.sweep().run();
            assertThat(store.trimmedThrough(topic).block(DEADLINE)).isNull();
        }
    }

    private static PushEvent append(PushStore store, Topic topic, int historySize, Duration ttl) {
        return store.append(new PushEvent(topic, null, "{}".getBytes(), null, null, null), historySize, ttl)
                .block(DEADLINE);
    }

    /** The trim of the SQL stores runs behind the append: wait for it. */
    private static void await(BooleanSupplier done) {
        long until = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < until) {
            if (done.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
