package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Isolates the cost of Timestamp field descriptor lookup:
 *  - baseline: findFieldByName("seconds") / findFieldByName("nanos") on every call
 *  - optimized: static final FieldDescriptors cached at class load (the P1-B fix)
 *
 * Also measures toMap / serialize for a timestamp-bearing message.
 */
// Nanosecond granularity: FD lookup / OffsetDateTime conversion are sub-microsecond.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class TimestampBenchmark {

    // Static cached FDs — mirrors the P1-B fix in ProtoMapConverter
    private static final FieldDescriptor TS_SECONDS = Timestamp.getDescriptor().findFieldByNumber(1);
    private static final FieldDescriptor TS_NANOS = Timestamp.getDescriptor().findFieldByNumber(2);

    private DynamicMessage tsMsg;
    private BenchmarkFixtures f;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        f = new BenchmarkFixtures();
        f.setup();

        Descriptor tsDesc = Timestamp.getDescriptor();
        tsMsg = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByNumber(1), 1700000000L)
                .setField(tsDesc.findFieldByNumber(2), 0)
                .build();
    }

    // ── timestampToOffsetDateTime: findFieldByName (baseline) ────────────────────

    @Benchmark
    public OffsetDateTime timestamp_findFieldByName() {
        Descriptor desc = tsMsg.getDescriptorForType();
        FieldDescriptor secondsFd = desc.findFieldByName("seconds");
        FieldDescriptor nanosFd = desc.findFieldByName("nanos");
        long seconds = ((Number) tsMsg.getField(secondsFd)).longValue();
        int nanos = ((Number) tsMsg.getField(nanosFd)).intValue();
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneOffset.UTC);
    }

    // ── timestampToOffsetDateTime: static cached FDs (P1-B fix) ─────────────────

    @Benchmark
    public OffsetDateTime timestamp_staticCachedFd() {
        long seconds = ((Number) tsMsg.getField(TS_SECONDS)).longValue();
        int nanos = ((Number) tsMsg.getField(TS_NANOS)).intValue();
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos), ZoneOffset.UTC);
    }

    // ── Via ProtoMapConverter (uses P1-B fix after the change) ───────────────────

    @Benchmark
    public OffsetDateTime timestamp_viaProtoMapConverter() {
        return ProtoMapConverter.timestampToOffsetDateTime(tsMsg);
    }

    // ── toMap for a message with a Timestamp field ────────────────────────────────

    @Benchmark
    public Object tier2_toMap_withTimestamp() {
        return f.timestampSerializer.toMap(f.timestampMsg);
    }

    @Benchmark
    public Object tier3_toMap_withTimestamp() {
        return ProtoMapConverter.dynamicToMap(f.timestampMsg);
    }

    // ── fromMap: Timestamp ISO string parse ──────────────────────────────────────

    @Benchmark
    public DynamicMessage tier2_fromMap_timestampIso() {
        return f.timestampSerializer.fromMap(f.timestampMap, DynamicMessage.newBuilder(f.timestampDescriptor));
    }
}
