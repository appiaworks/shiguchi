package dev.appiaworks.shiguchi.graphql;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.dataloader.DataLoader;
import org.dataloader.DelegatingDataLoader;

/**
 * A DataLoader whose {@code dispatch()} fires one window after it was first asked, not at once.
 * Every event of a subscription runs its own execution and asks for a dispatch of its own; behind
 * this wrapper the loads of events that arrive inside the window wait together and go out as one
 * batch. A dispatch asked while one is already pending joins it. graphql-java's data loader
 * chaining dispatches loaders directly, so a registry-level dispatch predicate cannot do this.
 */
final class WindowedDataLoader<K, V> extends DelegatingDataLoader<K, V> {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "shiguchi-graphql-batch-window");
        t.setDaemon(true);
        return t;
    });

    private final DataLoader<K, V> delegate;
    private final long windowNanos;
    private CompletableFuture<List<V>> pending;

    WindowedDataLoader(DataLoader<K, V> delegate, Duration window) {
        super(delegate);
        this.delegate = delegate;
        this.windowNanos = window.toNanos();
    }

    @Override
    public CompletableFuture<List<V>> dispatch() {
        synchronized (this) {
            if (pending != null) return pending;
            CompletableFuture<List<V>> result = new CompletableFuture<>();
            pending = result;
            TIMER.schedule(
                    () -> {
                        synchronized (this) {
                            pending = null;
                        }
                        try {
                            delegate.dispatch().whenComplete((list, failure) -> {
                                if (failure != null) result.completeExceptionally(failure);
                                else result.complete(list);
                            });
                        } catch (RuntimeException e) {
                            result.completeExceptionally(e);
                        }
                    },
                    windowNanos,
                    TimeUnit.NANOSECONDS);
            return result;
        }
    }
}
