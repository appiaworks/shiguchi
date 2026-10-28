package dev.appiaworks.shiguchi.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.avro.AvroFraming;
import dev.appiaworks.shiguchi.avro.AvroMapConverter;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.avro.Schema;
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
 * The upstream body codecs of a {@code REST_DOWNSTREAM} backend, measured at the point where they
 * differ: bytes ⇄ the plain {@code Map} the dispatcher then binds through the synthetic message.
 * Everything after that step is shared, so this is the whole cost of choosing Avro over JSON.
 *
 * <ul>
 *   <li>{@code avro_decode_toMap}: raw Avro datum → GenericRecord → Map (the Avro response path)
 *   <li>{@code json_parse_toMap}: JSON bytes → Map via Jackson (the JSON response path)
 *   <li>{@code avro_encode_fromMap}: Map → GenericRecord → bytes (Avro request body)
 *   <li>{@code json_write_fromMap}: Map → JSON bytes (JSON request body)
 *   <li>{@code avro_decode_singleObject_toMap}: as decode, with the 10-byte header and fingerprint check
 * </ul>
 *
 * <p>{@code tags} is a repeated string field of {@code repeatedSize} elements so scaling with
 * payload size is visible. Both payloads carry the same values.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class AvroCodecBenchmark {

    @Param({"10", "100"})
    public int repeatedSize;

    private static final ObjectMapper JSON = new ObjectMapper();

    private Schema schema;
    private byte[] avroBytes;
    private byte[] avroSingleObjectBytes;
    private byte[] jsonBytes;
    private Map<String, Object> map;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        schema = new Schema.Parser()
                .parse("{\"type\":\"record\",\"name\":\"Item\",\"namespace\":\"bench\",\"fields\":["
                        + "{\"name\":\"sku\",\"type\":\"string\"},"
                        + "{\"name\":\"name\",\"type\":[\"null\",\"string\"],\"default\":null},"
                        + "{\"name\":\"qty\",\"type\":\"long\"},"
                        + "{\"name\":\"price\",\"type\":\"double\"},"
                        + "{\"name\":\"active\",\"type\":\"boolean\"},"
                        + "{\"name\":\"updatedAt\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},"
                        + "{\"name\":\"tags\",\"type\":{\"type\":\"array\",\"items\":\"string\"}},"
                        + "{\"name\":\"category\",\"type\":{\"type\":\"record\",\"name\":\"Category\",\"fields\":["
                        + "{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"name\",\"type\":\"string\"}]}}]}");

        List<String> tags = new ArrayList<>();
        for (int i = 0; i < repeatedSize; i++) tags.add("tag-" + i);
        map = new LinkedHashMap<>();
        map.put("sku", "SKU-0001");
        map.put("name", "Widget");
        map.put("qty", 7L);
        map.put("price", 12.5d);
        map.put("active", true);
        map.put("updatedAt", "2024-01-02T03:04:05Z");
        map.put("tags", tags);
        map.put("category", Map.of("id", 3, "name", "tools"));

        Object datum = AvroMapConverter.fromJava(map, schema);
        avroBytes = AvroFraming.encode(datum, schema, AvroWireFormat.BINARY);
        avroSingleObjectBytes = AvroFraming.encode(datum, schema, AvroWireFormat.SINGLE_OBJECT);
        jsonBytes = JSON.writeValueAsBytes(map);
    }

    @Benchmark
    public Object avro_decode_toMap() {
        return AvroMapConverter.toJava(AvroFraming.decode(avroBytes, schema, fp -> Optional.empty()), schema);
    }

    @Benchmark
    public Object avro_decode_singleObject_toMap() {
        return AvroMapConverter.toJava(
                AvroFraming.decode(avroSingleObjectBytes, schema, fp -> Optional.empty()), schema);
    }

    @Benchmark
    public Object json_parse_toMap() throws Exception {
        return JSON.readValue(jsonBytes, Map.class);
    }

    @Benchmark
    public byte[] avro_encode_fromMap() {
        return AvroFraming.encode(AvroMapConverter.fromJava(map, schema), schema, AvroWireFormat.BINARY);
    }

    @Benchmark
    public byte[] json_write_fromMap() throws Exception {
        return JSON.writeValueAsBytes(map);
    }
}
