package dev.appiaworks.shiguchi.bench;

import com.fasterxml.jackson.core.JsonFactory;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks serde hot paths:
 *  - Tier 2 (generated) vs Tier 3 (ProtoMapConverter / JsonFormat) for toMap/fromMap/serialize
 *  - ObjectMapper vs JsonFactory for JSON generation
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SerdeBenchmark {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /**
     * Repeated-field payload size. Drives the {@code *_repeated} benchmarks so Tier-2 vs
     * Tier-3 scaling is visible and the JIT cannot over-specialize on a single shape.
     * Non-repeated benchmarks run once per size (results identical — ignore extra rows).
     */
    @Param({"10", "100", "1000"})
    public int repeatedSize;

    private BenchmarkFixtures f;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        f = new BenchmarkFixtures();
        f.setup(repeatedSize);
    }

    // ── toMap: Tier 2 vs Tier 3 ──────────────────────────────────────────────────

    @Benchmark
    public Map tier2_toMap_flat() {
        return f.flatSerializer.toMap(f.flatMsg);
    }

    @Benchmark
    public Map tier3_toMap_flat() {
        return ProtoMapConverter.dynamicToMap(f.flatMsg);
    }

    @Benchmark
    public Map tier2_toMap_nested() {
        return f.nestedSerializer.toMap(f.nestedMsg);
    }

    @Benchmark
    public Map tier3_toMap_nested() {
        return ProtoMapConverter.dynamicToMap(f.nestedMsg);
    }

    @Benchmark
    public Map tier2_toMap_repeated() {
        return f.repeatedSerializer.toMap(f.repeatedMsg);
    }

    @Benchmark
    public Map tier3_toMap_repeated() {
        return ProtoMapConverter.dynamicToMap(f.repeatedMsg);
    }

    // ── fromMap: Tier 2 vs Tier 3 ────────────────────────────────────────────────

    @Benchmark
    public DynamicMessage tier2_fromMap_flat() {
        return f.flatSerializer.fromMap(f.flatMap, DynamicMessage.newBuilder(f.flatDescriptor));
    }

    @Benchmark
    public DynamicMessage tier3_fromMap_flat() {
        return ProtoMapConverter.dynamicFromMap(f.flatMap, f.flatDescriptor);
    }

    // ── fromMap repeated: scales with @Param repeatedSize ────────────────────────

    @Benchmark
    public DynamicMessage tier2_fromMap_repeated() {
        return f.repeatedSerializer.fromMap(f.repeatedMap, DynamicMessage.newBuilder(f.repeatedDescriptor));
    }

    @Benchmark
    public DynamicMessage tier3_fromMap_repeated() {
        return ProtoMapConverter.dynamicFromMap(f.repeatedMap, f.repeatedDescriptor);
    }

    // ── serialize (JSON output): Tier 2 JsonFactory vs Tier 3 JsonFormat ─────────

    @Benchmark
    public String tier2_serialize_flat_jsonFactory() throws Exception {
        var sw = new StringWriter();
        try (var gen = JSON_FACTORY.createGenerator(sw)) {
            f.flatSerializer.serialize(f.flatMsg, gen);
        }
        return sw.toString();
    }

    @Benchmark
    public String tier3_serialize_flat_jsonFormat() throws Exception {
        return JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(f.flatMsg);
    }

    // ── ObjectMapper allocation: baseline comparison ─────────────────────────────
    // Shows the cost of new ObjectMapper() per call vs reusing JsonFactory.

    @Benchmark
    public String tier2_serialize_flat_newObjectMapper() throws Exception {
        var sw = new StringWriter();
        try (var gen = new com.fasterxml.jackson.databind.ObjectMapper().createGenerator(sw)) {
            f.flatSerializer.serialize(f.flatMsg, gen);
        }
        return sw.toString();
    }
}
