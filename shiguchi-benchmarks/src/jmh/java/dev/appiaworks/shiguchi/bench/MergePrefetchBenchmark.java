package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import dev.appiaworks.shiguchi.merge.HeapMerge;
import dev.appiaworks.shiguchi.merge.SortComparator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import reactor.core.publisher.Flux;

/**
 * Isolates why the streaming merge scales worse than its heap should.
 *
 * <p>{@code MergeBenchmark} showed per-row cost running 88 ns at 2 sources to 1256 ns at 16 — far
 * past the O(log sources) the heap predicts — with allocation per row growing in lockstep (39 B to
 * 581 B). Allocation tracking time that closely points at buffering rather than comparisons.
 *
 * <p>This holds the source count fixed and sweeps prefetch instead. If the buffers are the cause,
 * cost tracks prefetch here while the comparison count stays constant. If cost is flat across
 * prefetch, the hypothesis is wrong and the merge itself is the problem.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class MergePrefetchBenchmark {

    private static final int SOURCES = 8;
    private static final int ROWS_PER_SOURCE = 200;

    @Param({"1", "8", "32", "128", "256"})
    public int prefetch;

    private Comparator<Message> comparator;
    private List<Flux<Message>> streams;

    @Setup
    public void setUp() throws Exception {
        Descriptor type = buildRecordDescriptor();
        comparator = SortComparator.build(type, List.of(new SortComparator.SortKey("rank", true)), "id");

        Random random = new Random(20260908L);
        streams = new ArrayList<>(SOURCES);
        for (int s = 0; s < SOURCES; s++) {
            List<Message> rows = new ArrayList<>(ROWS_PER_SOURCE);
            var rankFd = type.findFieldByName("rank");
            var idFd = type.findFieldByName("id");
            for (int i = 0; i < ROWS_PER_SOURCE; i++) {
                rows.add(DynamicMessage.newBuilder(type)
                        .setField(rankFd, (long) random.nextInt(ROWS_PER_SOURCE / 4 + 1))
                        .setField(idFd, (long) random.nextInt(1_000_000))
                        .build());
            }
            rows.sort(comparator);
            streams.add(Flux.fromIterable(rows));
        }
    }

    /** Full drain at the swept prefetch. */
    @Benchmark
    public int drainAll() {
        return HeapMerge.merge(streams, comparator, prefetch)
                .collectList()
                .block()
                .size();
    }

    /** One page at the swept prefetch — the path a merge route actually takes. */
    @Benchmark
    public int firstPage() {
        return HeapMerge.merge(streams, comparator, prefetch)
                .take(25)
                .collectList()
                .block()
                .size();
    }

    private static Descriptor buildRecordDescriptor() throws Exception {
        DescriptorProto record = DescriptorProto.newBuilder()
                .setName("Record")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("rank", 2, FieldDescriptorProto.Type.TYPE_INT64))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("merge_prefetch_bench.proto")
                .setPackage("bench.merge.prefetch")
                .setSyntax("proto3")
                .addMessageType(record)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Record");
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
