package dev.appiaworks.shiguchi.graphql;

import java.util.ArrayDeque;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/** Lets at most {@code limit} live re-executions run at once on this instance; the rest wait in order. */
class GraphQlLiveGate {

    private final Object lock = new Object();
    private final ArrayDeque<MonoSink<Boolean>> waiting = new ArrayDeque<>();
    private int free;

    GraphQlLiveGate(int limit) {
        this.free = Math.max(1, limit);
    }

    /** Runs {@code work} once a place is free; the place is given back when it ends or is cancelled. */
    <T> Mono<T> limit(Mono<T> work) {
        return Mono.defer(() -> {
            Mono<Boolean> turn = Mono.<Boolean>create(sink -> {
                synchronized (lock) {
                    if (free > 0) {
                        free--;
                        sink.success(true);
                        return;
                    }
                    waiting.add(sink);
                }
                sink.onCancel(() -> {
                    synchronized (lock) {
                        waiting.remove(sink);
                    }
                });
            });
            return turn.flatMap(t -> work.doFinally(s -> release()));
        });
    }

    private void release() {
        MonoSink<Boolean> next;
        synchronized (lock) {
            next = waiting.poll();
            if (next == null) {
                free++;
                return;
            }
        }
        next.success(true);
    }

    int waiting() {
        synchronized (lock) {
            return waiting.size();
        }
    }
}
