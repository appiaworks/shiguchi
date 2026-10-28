package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import java.util.List;
import java.util.Map;
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
 * Tier 0 on a message made of the well-known types whose JSON is a structure, not a scalar:
 * a {@code Struct} (ten keys, nested struct and list), a {@code ListValue}, a {@code Value}
 * and a {@code FieldMask}. These are the types Tier 0 did not read off the wire itself but
 * materialised as {@code DynamicMessage}s and handed to {@code ProtoWkt}; {@code tier0} is that
 * path, {@code jsonFormat} the baseline it must match.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class WktTranscodeBenchmark {

    private WireJsonTranscoder transcoder;
    private WireJsonTranscoder.Program program;
    private Descriptor doc;
    private byte[] wire;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        DescriptorProto message = DescriptorProto.newBuilder()
                .setName("Doc")
                .addField(field("meta", 1, ".google.protobuf.Struct"))
                .addField(field("tags", 2, ".google.protobuf.ListValue"))
                .addField(field("mask", 3, ".google.protobuf.FieldMask"))
                .addField(field("any_value", 4, ".google.protobuf.Value"))
                .build();
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("wkt_bench.proto")
                        .setSyntax("proto3")
                        .setPackage("bench")
                        .addDependency("google/protobuf/struct.proto")
                        .addDependency("google/protobuf/field_mask.proto")
                        .addMessageType(message)
                        .build(),
                new FileDescriptor[] {
                    Struct.getDescriptor().getFile(), FieldMask.getDescriptor().getFile()
                });
        doc = file.findMessageTypeByName("Doc");

        Struct.Builder meta = Struct.newBuilder();
        for (int i = 0; i < 6; i++) {
            meta.putFields("k" + i, Value.newBuilder().setNumberValue(i * 1.5).build());
            meta.putFields(
                    "s" + i, Value.newBuilder().setStringValue("value-" + i).build());
        }
        meta.putFields("flag", Value.newBuilder().setBoolValue(true).build());
        meta.putFields("none", Value.newBuilder().setNullValueValue(0).build());
        meta.putFields(
                "nested",
                Value.newBuilder()
                        .setStructValue(Struct.newBuilder()
                                .putFields(
                                        "a",
                                        Value.newBuilder().setStringValue("x").build())
                                .putFields(
                                        "b",
                                        Value.newBuilder().setNumberValue(2).build()))
                        .build());
        ListValue.Builder list = ListValue.newBuilder();
        for (int i = 0; i < 10; i++) list.addValues(Value.newBuilder().setStringValue("tag-" + i));
        meta.putFields("items", Value.newBuilder().setListValue(list).build());

        DynamicMessage msg = DynamicMessage.newBuilder(doc)
                .setField(
                        doc.findFieldByName("meta"),
                        ProtoWkt.fromJava(
                                structAsMap(meta.build()),
                                doc.findFieldByName("meta").getMessageType()))
                .setField(
                        doc.findFieldByName("tags"),
                        ProtoWkt.fromJava(
                                List.of("alpha", "beta", 3.0, true),
                                doc.findFieldByName("tags").getMessageType()))
                .setField(
                        doc.findFieldByName("mask"),
                        ProtoWkt.fromJava(
                                "user.display_name,created_at,items",
                                doc.findFieldByName("mask").getMessageType()))
                .setField(
                        doc.findFieldByName("any_value"),
                        ProtoWkt.fromJava(
                                Map.of("k", "v"),
                                doc.findFieldByName("any_value").getMessageType()))
                .build();
        wire = msg.toByteArray();

        transcoder = new WireJsonTranscoder();
        program = transcoder.programFor(doc);
        if (program == null) throw new IllegalStateException("Doc must be Tier 0 eligible");
        byte[] json = transcoder.transcode(program, wire);
        if (json == null) throw new IllegalStateException("unexpected Tier 0 bail");
        String expected = JsonFormat.printer().omittingInsignificantWhitespace().print(msg);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (!mapper.readTree(json).equals(mapper.readTree(expected))) {
            throw new IllegalStateException("Tier 0 differs from JsonFormat:\n" + new String(json) + "\n" + expected);
        }
    }

    private static Map<String, Object> structAsMap(Struct struct) throws Exception {
        String json = JsonFormat.printer().print(struct);
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private static FieldDescriptorProto field(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    @Benchmark
    public byte[] tier0() {
        return transcoder.transcode(program, wire);
    }

    @Benchmark
    public String jsonFormat() throws Exception {
        return JsonFormat.printer().omittingInsignificantWhitespace().print(DynamicMessage.parseFrom(doc, wire));
    }
}
