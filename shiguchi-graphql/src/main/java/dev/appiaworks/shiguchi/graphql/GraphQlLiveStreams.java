package dev.appiaworks.shiguchi.graphql;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts the live queries running on this instance, in total and per principal. */
class GraphQlLiveStreams {

    /** A place taken by a live query; give it back with {@link #release()}. */
    final class Lease {
        private final String principal;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(String principal) {
            this.principal = principal;
        }

        void release() {
            if (!released.compareAndSet(false, true)) return;
            total.decrementAndGet();
            perPrincipal.computeIfPresent(principal, (k, n) -> n <= 1 ? null : n - 1);
        }
    }

    private final int maxPerPrincipal;
    private final int maxTotal;
    private final AtomicInteger total = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> perPrincipal = new ConcurrentHashMap<>();

    GraphQlLiveStreams(int maxPerPrincipal, int maxTotal) {
        this.maxPerPrincipal = maxPerPrincipal;
        this.maxTotal = maxTotal;
    }

    /** A lease, or null when the principal or the instance is at its limit. */
    Lease acquire(String principal) {
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet();
            return null;
        }
        boolean[] taken = {false};
        perPrincipal.compute(principal, (k, n) -> {
            int now = n == null ? 0 : n;
            if (now >= maxPerPrincipal) return n;
            taken[0] = true;
            return now + 1;
        });
        if (!taken[0]) {
            total.decrementAndGet();
            return null;
        }
        return new Lease(principal);
    }

    int active() {
        return total.get();
    }
}
