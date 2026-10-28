package dev.appiaworks.shiguchi.cursus.jdbc;

import java.util.List;
import java.util.concurrent.Callable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The one place blocking JDBC meets Reactor: every store call runs on {@code boundedElastic}, so
 * nothing blocks an event-loop or {@code parallel} thread. A callable that returns null is an
 * empty {@code Mono}.
 */
final class JdbcIo {

    private JdbcIo() {}

    static <T> Mono<T> mono(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    static Mono<Void> run(Runnable work) {
        return Mono.<Void>fromRunnable(work).subscribeOn(Schedulers.boundedElastic());
    }

    static <T> Flux<T> flux(Callable<? extends List<T>> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic()).flatMapIterable(l -> l);
    }
}
