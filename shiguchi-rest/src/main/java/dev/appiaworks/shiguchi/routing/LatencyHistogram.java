package dev.appiaworks.shiguchi.routing;

import java.util.concurrent.atomic.LongAdder;

/**
 * Call latencies in exponential buckets (each 25 % wider than the last, from 0.25 ms to about
 * 25 minutes), cheap enough to record on every call. Percentiles come out as a bucket's upper
 * bound, so within 25 % of the true value — plenty to tell a healthy version from a slow one.
 *
 * <p>Windows are taken by subtracting {@link #snapshot()}s, so one histogram serves every step of a
 * rollout without being reset under concurrent writers.
 */
final class LatencyHistogram {

    private static final double FIRST_BOUND_MILLIS = 0.25;
    private static final double GROWTH = 1.25;
    private static final int BUCKETS = 72;
    private static final double[] UPPER_MILLIS = new double[BUCKETS];

    static {
        double bound = FIRST_BOUND_MILLIS;
        for (int i = 0; i < BUCKETS; i++) {
            UPPER_MILLIS[i] = bound;
            bound *= GROWTH;
        }
    }

    private final LongAdder[] counts = new LongAdder[BUCKETS];

    LatencyHistogram() {
        for (int i = 0; i < BUCKETS; i++) counts[i] = new LongAdder();
    }

    void record(long nanos) {
        counts[bucket(nanos / 1_000_000.0)].increment();
    }

    static int bucket(double millis) {
        if (millis <= FIRST_BOUND_MILLIS) return 0;
        int i = (int) Math.ceil(Math.log(millis / FIRST_BOUND_MILLIS) / Math.log(GROWTH));
        // Rounding can put a value exactly on a bound one bucket high or low: settle it.
        while (i > 0 && millis <= UPPER_MILLIS[i - 1]) i--;
        while (i < BUCKETS - 1 && millis > UPPER_MILLIS[i]) i++;
        return Math.min(i, BUCKETS - 1);
    }

    /** The count of every bucket now. */
    long[] snapshot() {
        long[] out = new long[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) out[i] = counts[i].sum();
        return out;
    }

    /** {@code now - before}, bucket by bucket: the calls recorded between two snapshots. */
    static long[] since(long[] now, long[] before) {
        long[] out = new long[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) out[i] = now[i] - (before == null ? 0 : before[i]);
        return out;
    }

    static long total(long[] counts) {
        long n = 0;
        for (long c : counts) n += c;
        return n;
    }

    /** The {@code q} quantile (0.99 for p99) in milliseconds, or -1 when nothing was recorded. */
    static double quantileMillis(long[] counts, double q) {
        long total = total(counts);
        if (total == 0) return -1;
        long rank = (long) Math.ceil(q * total);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) return UPPER_MILLIS[i];
        }
        return UPPER_MILLIS[BUCKETS - 1];
    }
}
