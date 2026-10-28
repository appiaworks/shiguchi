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
 * Measures the merge hot path.
 *
 * <p>Three things worth knowing before tuning a merge:
 *
 * <ul>
 *   <li><b>Comparator cost.</b> A merge sorts, so the comparator runs O(n log n) times per page.
 *       It resolves field paths against each record's own descriptor — necessary, because two
 *       backends serving the same .proto hand back distinct descriptor instances — and that
 *       resolution is cached per descriptor. {@code sameDescriptor} vs {@code crossDescriptor}
 *       shows what the cache is worth: without it the second would pay a name lookup per
 *       comparison.
 *   <li><b>Source-count scaling.</b> A heap merge is O(log sources) per record; {@code sources}
 *       sweeps 2→16 to check that and to price a wide fan-out. Every source is sorted first, since
 *       that is the merge's precondition — feeding it unsorted rows measures a case the engine
 *       never sees and badly overstates the streaming cost.
 *   <li><b>Streaming vs materialised.</b> The two modes a merge route picks per source, over the
 *       same data, so the cost of a backend that will not sort is a number rather than a guess.
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class MergeBenchmark {

    /** Rows per source. A page is typically far smaller; this measures the merge, not the page. */
    private static final int ROWS_PER_SOURCE = 200;

    @Param({"2", "4", "8", "16"})
    public int sources;

    private Descriptor recordType;
    private Comparator<Message> comparator;
    private List<List<Message>> perSourceSameDescriptor;
    private List<List<Message>> perSourceCrossDescriptor;
    private List<Flux<Message>> streams;

    @Setup
    public void setUp() throws Exception {
        recordType = buildRecordDescriptor("bench.merge");
        comparator = SortComparator.build(recordType, List.of(new SortComparator.SortKey("rank", true)), "id");

        Random random = new Random(20260908L);

        // Every source built from one descriptor — the single-backend shape.
        perSourceSameDescriptor = new ArrayList<>(sources);
        for (int s = 0; s < sources; s++) {
            perSourceSameDescriptor.add(sortedRows(recordType, random, ROWS_PER_SOURCE, comparator));
        }

        // Each source built from its OWN descriptor instance, which is what a real merge sees:
        // distinct backends serving the same schema. This is the case the path cache exists for.
        perSourceCrossDescriptor = new ArrayList<>(sources);
        for (int s = 0; s < sources; s++) {
            Descriptor own = buildRecordDescriptor("bench.merge.src" + s);
            perSourceCrossDescriptor.add(sortedRows(own, random, ROWS_PER_SOURCE, comparator));
        }

        streams = new ArrayList<>(sources);
        for (List<Message> rows : perSourceSameDescriptor) {
            streams.add(Flux.fromIterable(rows));
        }
    }

    /** Materialised merge, all records from one descriptor. */
    @Benchmark
    public int mergeListsSameDescriptor() {
        return HeapMerge.mergeLists(perSourceSameDescriptor, comparator).size();
    }

    /** Materialised merge across distinct descriptor instances — the real cross-backend shape. */
    @Benchmark
    public int mergeListsCrossDescriptor() {
        return HeapMerge.mergeLists(perSourceCrossDescriptor, comparator).size();
    }

    /** Streaming merge, draining every source. */
    @Benchmark
    public int mergeStreamingFull() {
        return HeapMerge.merge(streams, comparator).collectList().block().size();
    }

    /**
     * Streaming merge stopping at a page. The point of streaming: a page should cost far less than
     * a full drain, since the merge stops as soon as it is satisfied.
     */
    @Benchmark
    public int mergeStreamingFirstPage() {
        return HeapMerge.merge(streams, comparator)
                .take(25)
                .collectList()
                .block()
                .size();
    }

    /** Comparator alone, isolating field resolution from the merge around it. */
    @Benchmark
    public int compareCrossDescriptor() {
        List<Message> a = perSourceCrossDescriptor.get(0);
        List<Message> b = perSourceCrossDescriptor.get(1 % sources);
        int acc = 0;
        for (int i = 0; i < 100; i++) {
            acc += comparator.compare(a.get(i % a.size()), b.get(i % b.size()));
        }
        return acc;
    }

    // -------------------------------------------------------------------------

    /**
     * Rows in merge-key order.
     *
     * <p>The sort is the point: a k-way merge assumes each input is already ordered, so feeding it
     * unsorted rows measures a case the engine never sees in production and makes the streaming
     * path look far worse than it is. An earlier version of this method skipped the sort despite
     * its name, which is what produced the "streaming scales worse than the heap" reading.
     */
    private static List<Message> sortedRows(Descriptor type, Random random, int count, Comparator<Message> order) {
        var rankFd = type.findFieldByName("rank");
        var idFd = type.findFieldByName("id");
        var nameFd = type.findFieldByName("name");
        List<Message> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(DynamicMessage.newBuilder(type)
                    .setField(rankFd, (long) random.nextInt(count / 4 + 1)) // ties are common
                    .setField(idFd, (long) random.nextInt(1_000_000))
                    .setField(nameFd, "row-" + i)
                    .build());
        }
        rows.sort(order);
        return rows;
    }

    /** A Record descriptor in its own file, so each call yields a distinct instance. */
    private static Descriptor buildRecordDescriptor(String pkg) throws Exception {
        DescriptorProto record = DescriptorProto.newBuilder()
                .setName("Record")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("rank", 2, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("name", 3, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName(pkg.replace('.', '_') + ".proto")
                .setPackage(pkg)
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
