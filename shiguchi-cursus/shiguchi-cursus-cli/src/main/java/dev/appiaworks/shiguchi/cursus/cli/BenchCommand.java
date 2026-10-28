package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code cursus bench}: a quick load probe. Publishers post timestamped events at a fixed rate,
 * subscribers read them back, and the publish-to-receive latency comes out as one JSON object.
 * The timestamp is the client's own clock, so there is no skew between publisher and subscriber;
 * it is a smoke test for a deployment, not a replacement for the repository's PushBenchmark.
 */
@Command(
        name = "bench",
        mixinStandardHelpOptions = true,
        description = "Probe a gateway: publish at a rate, subscribe, print the latency percentiles as JSON.",
        footer = {
            "",
            "Example:",
            "  cursus bench --topic bench:1 --publishers 2 --subscribers 10 --rate 200 --duration 10",
            "",
            "--rate is events per second in total. Every subscriber sees every event, so the",
            "received count is about rate x duration x subscribers. Not a replacement for PushBenchmark."
        })
final class BenchCommand implements Callable<Integer> {

    @ParentCommand
    Cursus root;

    @Option(
            names = {"-t", "--topic"},
            defaultValue = "bench:cursus",
            description = "The topic to publish to and read from (default ${DEFAULT-VALUE}).")
    String topic;

    @Option(names = "--publishers", defaultValue = "1", description = "Concurrent publishers (default 1).")
    int publishers;

    @Option(names = "--subscribers", defaultValue = "1", description = "Concurrent subscribers (default 1).")
    int subscribers;

    @Option(names = "--rate", defaultValue = "100", description = "Total events per second (default 100).")
    int rate;

    @Option(names = "--duration", defaultValue = "10", description = "Seconds to publish (default 10).")
    int duration;

    @Option(names = "--drain-ms", hidden = true, defaultValue = "1000", description = "Wait for stragglers.")
    long drainMs;

    @Override
    public Integer call() throws Exception {
        if (publishers < 1 || subscribers < 0 || rate < 1 || duration < 1)
            throw new CliException(
                    CliException.USAGE,
                    "--publishers, --rate and --duration must be at least 1; --subscribers 0 or more");
        Http http = root.http();
        Out out = root.out();
        Lifecycle outer = root.lifecycle();
        Lifecycle readers = new Lifecycle();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicLong received = new AtomicLong();
        AtomicLong sent = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong readerErrors = new AtomicLong();

        Transport.EventSink sink = new Transport.EventSink() {
            @Override
            public boolean event(ObjectNode event) {
                received.incrementAndGet();
                JsonNode t = event.path("data").path("t");
                if (t.canConvertToLong()) latencies.add(Math.max(0, System.nanoTime() / 1_000 - t.asLong()));
                return true;
            }

            @Override
            public void control(String kind, JsonNode body) {
                readerErrors.incrementAndGet();
            }
        };

        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < subscribers; i++) {
                pool.submit(() -> {
                    try {
                        new SseTransport(http)
                                .stream(
                                        new Transport.SubRequest(
                                                List.of(topic), null, null, null, null, null, null, false),
                                        sink,
                                        readers);
                    } catch (Exception e) {
                        if (!readers.isStopped()) readerErrors.incrementAndGet();
                    }
                });
            }
            if (!outer.sleep(500)) return 0; // let the streams attach before the first event

            long end = System.nanoTime() + duration * 1_000_000_000L;
            long gap = 1_000_000_000L * publishers / rate;
            List<java.util.concurrent.Future<?>> writers = new ArrayList<>();
            for (int p = 0; p < publishers; p++) {
                writers.add(pool.submit(() -> {
                    long next = System.nanoTime();
                    while (next < end && !outer.isStopped()) {
                        long wait = next - System.nanoTime();
                        if (wait > 0 && !outer.sleep(Math.max(1, wait / 1_000_000))) break;
                        ObjectNode body = Http.JSON.createObjectNode();
                        body.put("topic", topic);
                        body.putObject("data").put("t", System.nanoTime() / 1_000);
                        try {
                            http.postPush("/publish", body);
                            sent.incrementAndGet();
                        } catch (RuntimeException e) {
                            failed.incrementAndGet();
                        }
                        next += gap;
                    }
                }));
            }
            for (var w : writers) w.get();
            outer.sleep(drainMs);
            readers.stop(false);
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        long[] sorted = latencies.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        ObjectNode result = Http.JSON.createObjectNode();
        result.put("topic", topic);
        result.put("publishers", publishers);
        result.put("subscribers", subscribers);
        result.put("targetRate", rate);
        result.put("durationSeconds", Math.round(seconds * 10) / 10.0);
        result.put("sent", sent.get());
        result.put("publishFailures", failed.get());
        result.put("received", received.get());
        result.put("expected", sent.get() * subscribers);
        result.put("streamErrors", readerErrors.get());
        ObjectNode latency = result.putObject("latencyMs");
        latency.put("samples", sorted.length);
        if (sorted.length > 0) {
            latency.put("p50", ms(sorted, 0.50));
            latency.put("p95", ms(sorted, 0.95));
            latency.put("p99", ms(sorted, 0.99));
            latency.put("max", sorted[sorted.length - 1] / 1000.0);
        }
        out.json(result);
        return failed.get() > 0 && sent.get() == 0 ? CliException.TRANSPORT : 0;
    }

    private static double ms(long[] sortedMicros, double quantile) {
        int index = (int) Math.min(sortedMicros.length - 1, Math.ceil(quantile * sortedMicros.length) - 1);
        return Math.round(sortedMicros[Math.max(0, index)] / 10.0) / 100.0;
    }
}
