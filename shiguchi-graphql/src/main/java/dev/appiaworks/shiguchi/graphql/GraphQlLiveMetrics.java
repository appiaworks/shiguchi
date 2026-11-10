package dev.appiaworks.shiguchi.graphql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;

/** Meters of the live queries; every method is a no-op without a registry. */
class GraphQlLiveMetrics {

    private final MeterRegistry meters;

    GraphQlLiveMetrics(MeterRegistry meters, Supplier<Number> streams, Supplier<Number> waiting) {
        this.meters = meters;
        if (meters != null) {
            Gauge.builder("shiguchi.graphql.live.streams", streams)
                    .description("Live queries running on this instance")
                    .register(meters);
            Gauge.builder("shiguchi.graphql.live.waiting", waiting)
                    .description("Live re-executions waiting for a free place")
                    .register(meters);
        }
    }

    /** A signal that asks live queries to run again; {@code source} is mutation, upstream, remote or refresh. */
    void signal(String source) {
        count("shiguchi.graphql.live.signals", "source", source);
    }

    void executed() {
        count("shiguchi.graphql.live.executions", null, null);
    }

    /** A re-execution whose result equalled the last one and was not sent. */
    void skipped() {
        count("shiguchi.graphql.live.skipped", null, null);
    }

    private void count(String name, String tag, String value) {
        if (meters == null) return;
        Counter.Builder b = Counter.builder(name);
        if (tag != null) b.tag(tag, value);
        b.register(meters).increment();
    }
}
