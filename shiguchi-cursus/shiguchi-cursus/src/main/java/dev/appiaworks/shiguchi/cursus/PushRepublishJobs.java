package dev.appiaworks.shiguchi.cursus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Bulk republish jobs: one job sends a list of archived events through the publisher, one at a
 * time at {@code admin.republish.rate-per-second}, counting what went out and what failed; an
 * operator reads its progress and may cancel it. Per instance, the last hundred kept.
 */
public final class PushRepublishJobs {

    private static final Logger log = LoggerFactory.getLogger(PushRepublishJobs.class);
    private static final int KEPT = 100;

    public enum State {
        RUNNING,
        DONE,
        CANCELLED,
        FAILED
    }

    /** One job's progress; the counters move while it runs. */
    public static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final Instant startedAt = Instant.now();
        private final String principal;
        private final String target;
        private final String reason;
        private final int matched;
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private volatile Instant finishedAt;
        private volatile String error;
        private volatile Disposable run;

        Job(String principal, String target, String reason, int matched) {
            this.principal = principal;
            this.target = target;
            this.reason = reason;
            this.matched = matched;
        }

        public String id() {
            return id;
        }

        public State state() {
            return state.get();
        }

        public long published() {
            return published.get();
        }

        public long failed() {
            return failed.get();
        }

        public int matched() {
            return matched;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("state", state.get().name());
            m.put("principal", principal);
            m.put("target", target);
            m.put("reason", reason);
            m.put("matched", matched);
            m.put("published", published.get());
            m.put("failed", failed.get());
            m.put("startedAt", startedAt.toString());
            m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            m.put("error", error);
            return m;
        }

        private void finish(State end, String problem) {
            if (state.compareAndSet(State.RUNNING, end)) {
                finishedAt = Instant.now();
                error = problem;
            }
        }
    }

    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();

    /**
     * Starts a job over {@code events}: each goes through {@code publish}, a failure is counted
     * and the job goes on. Returns at once; read the job for progress.
     */
    public Job start(
            String principal,
            String target,
            String reason,
            List<PushArchive.Entry> events,
            Function<PushArchive.Entry, Mono<PushEvent>> publish,
            int ratePerSecond) {
        Job job = new Job(principal, target, reason, events.size());
        Duration spacing = Duration.ofNanos(1_000_000_000L / Math.max(1, ratePerSecond));
        synchronized (jobs) {
            jobs.put(job.id, job);
            while (jobs.size() > KEPT) {
                String oldest = jobs.keySet().iterator().next();
                if (jobs.get(oldest).state() == State.RUNNING) break;
                jobs.remove(oldest);
            }
        }
        job.run = Flux.fromIterable(events)
                .concatMap(e -> publish.apply(e)
                        .doOnSuccess(x -> job.published.incrementAndGet())
                        .onErrorResume(err -> {
                            job.failed.incrementAndGet();
                            log.warn(
                                    "Push: republish of {} {} failed: {}",
                                    e.event().topic(),
                                    e.event().id(),
                                    err.toString());
                            return Mono.empty();
                        })
                        .then(Mono.delay(spacing, Schedulers.parallel()))
                        .then())
                .subscribe(
                        x -> {}, err -> job.finish(State.FAILED, err.toString()), () -> job.finish(State.DONE, null));
        if (events.isEmpty()) job.finish(State.DONE, null);
        return job;
    }

    public List<Job> list() {
        synchronized (jobs) {
            List<Job> out = new ArrayList<>(jobs.values());
            java.util.Collections.reverse(out);
            return out;
        }
    }

    public Job get(String id) {
        synchronized (jobs) {
            return jobs.get(id);
        }
    }

    /** Stops a running job; true when it was running. */
    public boolean cancel(String id) {
        Job job = get(id);
        if (job == null || job.state() != State.RUNNING) return false;
        job.finish(State.CANCELLED, null);
        Disposable run = job.run;
        if (run != null) run.dispose();
        return true;
    }
}
