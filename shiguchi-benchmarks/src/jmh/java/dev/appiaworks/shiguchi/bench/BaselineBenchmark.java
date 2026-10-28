package dev.appiaworks.shiguchi.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Harness-floor reference. Run this alongside any other benchmark to learn how much of a
 * reported figure is JMH per-call overhead rather than the code under test.
 *
 * <ul>
 *   <li>{@link #baseline_noop()} — empty method; measures the cost of an inlined empty
 *       {@code @Benchmark} (effectively the JMH loop floor).</li>
 *   <li>{@link #baseline_consumeCpu()} — {@code Blackhole.consumeCPU(8)} tokens; a known,
 *       non-zero unit of work to sanity-check that the harness is actually measuring.</li>
 * </ul>
 *
 * Nanosecond output: these are the smallest possible operations.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class BaselineBenchmark {

    /** Empty body — JMH still measures the surrounding loop, giving the per-call floor. */
    @Benchmark
    public void baseline_noop() {
        // intentionally empty
    }

    /** A fixed, known amount of busy-work so the floor is visibly non-zero. */
    @Benchmark
    public void baseline_consumeCpu(Blackhole bh) {
        Blackhole.consumeCPU(8);
        bh.consume(0);
    }
}
