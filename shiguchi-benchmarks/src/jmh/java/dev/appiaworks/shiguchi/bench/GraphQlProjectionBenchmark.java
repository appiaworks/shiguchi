package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.serde.ProjectionNode;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
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
 * The GraphQL response path: proto message → {@code Map}, with and without the selection
 * projection ({@code shiguchi.graphql.selection-projection}).
 *
 * <p>The trade-off the numbers expose: a full conversion goes through the Tier 2 compiled
 * {@code toMap} (fast, but converts every composite subtree), while a projected conversion goes
 * through {@code ProtoMapConverter.projectToMap} — the reflective Tier 3 walker — converting only
 * the composite fields the query selected. Pruning wins when the parts skipped dominate the
 * message; on a message that is mostly scalars the compiled full walk is the faster choice.
 * {@code items} sets how much there is to skip.
 *
 * <ul>
 *   <li>{@code tier2_fullMap} — what {@code selection-projection: false} does, and what a query
 *       selecting everything does.
 *   <li>{@code tier3_fullMap} — the reflective walker converting everything, the projection path's
 *       ceiling.
 *   <li>{@code projected_scalarsOnly} — a query selecting {@code id name}: every composite subtree
 *       skipped (scalars are always converted, see {@code projectToMap}).
 *   <li>{@code projected_itemsSku} — a query selecting {@code items { sku }}: the repeated message
 *       converted, the map field skipped.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class GraphQlProjectionBenchmark {

    @Param({"3", "30"})
    public int items;

    private SerdeSelector serde;
    private ServiceEndpointContext ctx;
    private Descriptor descriptor;
    private DynamicMessage message;
    private ProjectionNode scalarsOnly;
    private ProjectionNode itemsSku;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        FileDescriptor file = BenchOrders.dynamicFile();
        descriptor = file.findMessageTypeByName("OrderResponse");
        ctx = BenchOrders.context("orders", file, null);
        serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        message = DynamicMessage.parseFrom(
                descriptor, BenchOrders.order(42_000_000_123L, items).toByteArray());

        scalarsOnly = ProjectionNode.root();
        scalarsOnly.addPath("id");
        scalarsOnly.addPath("name");

        itemsSku = ProjectionNode.root();
        itemsSku.addPath("id");
        itemsSku.addPath("items/sku");

        // Sanity: the projected shapes really differ.
        Map<String, Object> pruned = serde.serializeToMapProjected(message, descriptor, ctx, scalarsOnly);
        if (pruned.containsKey("items") || pruned.containsKey("labels")) {
            throw new IllegalStateException("projection did not prune composites: " + pruned.keySet());
        }
    }

    @Benchmark
    public Map<String, Object> tier2_fullMap() throws Exception {
        return serde.serializeToMap(message, descriptor, ctx);
    }

    @Benchmark
    public Map<String, Object> tier3_fullMap() {
        return ProtoMapConverter.dynamicToMap(message);
    }

    @Benchmark
    public Map<String, Object> projected_scalarsOnly() throws Exception {
        return serde.serializeToMapProjected(message, descriptor, ctx, scalarsOnly);
    }

    @Benchmark
    public Map<String, Object> projected_itemsSku() throws Exception {
        return serde.serializeToMapProjected(message, descriptor, ctx, itemsSku);
    }
}
