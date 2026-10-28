package dev.appiaworks.shiguchi.bench;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.serde.JsonTrees;
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
 * Reading a JSON value into maps and lists: {@link JsonTrees}, which the compiled request parser
 * and {@code ProtoJson.parse} use, against the {@code ObjectMapper} it replaced so that the serde
 * needs no jackson-databind. The input is the fixture order as JSON, 8 nested items.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class JsonTreeReadBenchmark {

    private static final JsonFactory JSON = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String json;

    @Setup(Level.Trial)
    public void setup() {
        json = ProtoJson.defaults().print(BenchOrders.order(1, 8));
    }

    @Benchmark
    public Object read_jsonTrees() throws Exception {
        try (JsonParser parser = JSON.createParser(json)) {
            parser.nextToken();
            return JsonTrees.read(parser);
        }
    }

    @Benchmark
    public Object read_objectMapper() throws Exception {
        try (JsonParser parser = JSON.createParser(json)) {
            parser.nextToken();
            return MAPPER.readValue(parser, Object.class);
        }
    }
}
