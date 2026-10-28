package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import dev.appiaworks.shiguchi.bench.proto.OrderResponse;
import java.io.StringWriter;
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
 * {@link ProtoJson} as a library user meets it — a generated message in, JSON text out, and
 * back — against protobuf's own {@code JsonFormat}.
 *
 * <p>The fixture is the end-to-end order with 8 items: 6 scalars, 8 nested messages, a 3-entry
 * map and a Timestamp. Every leg starts from the generated {@code OrderResponse}, so the cost of
 * getting from a generated class to whatever a path needs (bytes, a {@code DynamicMessage}) is
 * inside the measurement, as it is for a caller.
 *
 * <ul>
 *   <li>{@code print_jsonFormat} — the baseline.
 *   <li>{@code print_protoJson} — {@code ProtoJson.print(message)}: the {@code writeJson} the
 *       protoc plugin generated into the stub, straight to JSON (on any Java).
 *   <li>{@code printWriter_protoJson} — {@code print(message, writer)}: the generated writer,
 *       into a writer.
 *   <li>{@code printDynamic_compiled} — a {@code DynamicMessage} through a generated
 *       serializer: the path for code that works from descriptors.
 *   <li>{@code printDynamic_reflective} — the same with compiled serializers off: what an
 *       application without the generator gets for a {@code DynamicMessage}.
 *   <li>{@code printBytes_protoJson} — {@code transcode(bytes, descriptor)}: the caller already
 *       holds the wire form, so nothing is serialized first. Also the Tier 0 path a generated message
 *       without a carried writer takes, minus its serialization.
 *   <li>{@code parse_jsonFormat} / {@code parse_protoJson} — JSON text to the generated class;
 *       {@code parse_protoJson} streams through the {@code mergeJson} the plugin generated into
 *       the stub's Builder (on any Java).
 *   <li>{@code parse_protoJson_dynamic} / {@code parse_protoJson_reflective} — the path for
 *       code that works from descriptors, then re-read into the generated class: through a
 *       generated parser (Java 25+), and through a tree and the reflective converter.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ProtoJsonBenchmark {

    private OrderResponse order;
    private byte[] wire;
    private DynamicMessage dynamicOrder;
    private String jsonText;

    private JsonFormat.Printer jsonFormatPrinter;
    private JsonFormat.Parser jsonFormatParser;
    private ProtoJson compiled;
    private ProtoJson reflective;
    private boolean generator;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        order = BenchOrders.order(1, 8);
        wire = order.toByteArray();
        dynamicOrder = DynamicMessage.parseFrom(OrderResponse.getDescriptor(), wire);
        jsonFormatPrinter =
                JsonFormat.printer().omittingInsignificantWhitespace().alwaysPrintFieldsWithNoPresence();
        jsonFormatParser = JsonFormat.parser();
        compiled = ProtoJson.builder().build();
        reflective = ProtoJson.builder().compiledSerializers(false).build();
        // Below Java 25 the generator is absent: the legs that need it refuse to run (JMH reports
        // them and goes on); the others measure the typed paths the generated stub carries.
        generator = compiled.usesCompiledSerializers();
        jsonText = compiled.print(order);
    }

    @Benchmark
    public String print_jsonFormat() throws Exception {
        return jsonFormatPrinter.print(order);
    }

    @Benchmark
    public String print_protoJson() {
        return compiled.print(order);
    }

    @Benchmark
    public String printWriter_protoJson() {
        StringWriter out = new StringWriter(512);
        compiled.print(order, out);
        return out.toString();
    }

    @Benchmark
    public String printDynamic_compiled() {
        requireGenerator();
        StringWriter out = new StringWriter(512);
        compiled.print(dynamicOrder, out);
        return out.toString();
    }

    @Benchmark
    public String printDynamic_reflective() {
        StringWriter out = new StringWriter(512);
        reflective.print(dynamicOrder, out);
        return out.toString();
    }

    @Benchmark
    public byte[] printBytes_protoJson() {
        return compiled.transcode(wire, OrderResponse.getDescriptor());
    }

    @Benchmark
    public OrderResponse parse_jsonFormat() throws Exception {
        OrderResponse.Builder builder = OrderResponse.newBuilder();
        jsonFormatParser.merge(jsonText, builder);
        return builder.build();
    }

    @Benchmark
    public OrderResponse parse_protoJson() {
        return compiled.parse(jsonText, OrderResponse.getDefaultInstance());
    }

    @Benchmark
    public OrderResponse parse_protoJson_dynamic() {
        requireGenerator();
        return ProtoTypes.as(
                compiled.parse(jsonText, OrderResponse.getDescriptor()), OrderResponse.getDefaultInstance());
    }

    @Benchmark
    public OrderResponse parse_protoJson_reflective() {
        return ProtoTypes.as(
                reflective.parse(jsonText, OrderResponse.getDescriptor()), OrderResponse.getDefaultInstance());
    }

    private void requireGenerator() {
        if (!generator) {
            throw new IllegalStateException(
                    "needs the serializer code generator (Java 25+ with protobuf-java-extras-classfile)");
        }
    }
}
