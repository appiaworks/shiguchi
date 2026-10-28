package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import java.util.Map;

/**
 * Shared descriptor and message fixtures for JMH benchmarks.
 * All setup is done once per trial — benchmarks call setup(repeatedSize) in @Setup(Level.Trial).
 *
 * <p>{@code repeatedSize} is driven by a JMH {@code @Param} on the benchmark class so the
 * repeated-field paths exercise multiple payload sizes (10 / 100 / 1000). This both prevents
 * the JIT from over-specializing on a single message shape and surfaces the Tier-2 vs Tier-3
 * scaling curve in results.
 */
public final class BenchmarkFixtures {

    // ── Descriptors ──────────────────────────────────────────────────────────────

    /** 8 scalar fields — representative flat response message. */
    public Descriptor flatDescriptor;
    /** Outer message with one nested inner message + 3 scalar fields. */
    public Descriptor nestedDescriptor;
    /** Message with a single google.protobuf.Timestamp field. */
    public Descriptor timestampDescriptor;
    /** Message with repeated int64 field (100 elements). */
    public Descriptor repeatedDescriptor;

    // ── Pre-built DynamicMessages ────────────────────────────────────────────────

    public DynamicMessage flatMsg;
    public DynamicMessage nestedMsg;
    public DynamicMessage timestampMsg;
    public DynamicMessage repeatedMsg;

    // ── Pre-built Maps (for fromMap benchmarks) ──────────────────────────────────

    public Map<String, Object> flatMap;
    public Map<String, Object> nestedMap;
    public Map<String, Object> timestampMap;
    public Map<String, Object> repeatedMap;

    // ── Tier 2 compiled serializers ──────────────────────────────────────────────

    public AbstractDynamicSerializer flatSerializer;
    public AbstractDynamicSerializer nestedSerializer;
    public AbstractDynamicSerializer timestampSerializer;
    public AbstractDynamicSerializer repeatedSerializer;

    /** Backwards-compatible default — 100-element repeated payload. */
    public void setup() throws Exception {
        setup(100);
    }

    /**
     * @param repeatedSize number of elements in the repeated int64 field; driven by a
     *                     JMH {@code @Param} so benchmarks see a scaling curve.
     */
    @SuppressWarnings("unchecked")
    public void setup(int repeatedSize) throws Exception {
        var factory = new DynamicSerializerFactory();

        flatDescriptor = buildFlatDescriptor();
        nestedDescriptor = buildNestedDescriptor();
        timestampDescriptor = buildTimestampDescriptor();
        repeatedDescriptor = buildRepeatedDescriptor();

        flatSerializer = factory.compileDescriptor(flatDescriptor);
        nestedSerializer = factory.compileDescriptor(nestedDescriptor);
        timestampSerializer = factory.compileDescriptor(timestampDescriptor);
        repeatedSerializer = factory.compileDescriptor(repeatedDescriptor);

        flatMsg = buildFlatMessage();
        nestedMsg = buildNestedMessage();
        timestampMsg = buildTimestampMessage();
        repeatedMsg = buildRepeatedMessage(repeatedSize);

        flatMap = (Map<String, Object>) flatSerializer.toMap(flatMsg);
        nestedMap = (Map<String, Object>) nestedSerializer.toMap(nestedMsg);
        repeatedMap = (Map<String, Object>) repeatedSerializer.toMap(repeatedMsg);
        timestampMap = Map.of("occurredAt", "2023-11-14T22:13:20Z");
    }

    // ── Descriptor builders ──────────────────────────────────────────────────────

    private Descriptor buildFlatDescriptor() throws Exception {
        var msg = DescriptorProto.newBuilder().setName("FlatMsg");
        int n = 1;
        msg.addField(field("id", n++, FieldDescriptorProto.Type.TYPE_INT64));
        msg.addField(field("name", n++, FieldDescriptorProto.Type.TYPE_STRING));
        msg.addField(field("status", n++, FieldDescriptorProto.Type.TYPE_INT32));
        msg.addField(field("score", n++, FieldDescriptorProto.Type.TYPE_DOUBLE));
        msg.addField(field("active", n++, FieldDescriptorProto.Type.TYPE_BOOL));
        msg.addField(field("description", n++, FieldDescriptorProto.Type.TYPE_STRING));
        msg.addField(field("price", n++, FieldDescriptorProto.Type.TYPE_FLOAT));
        msg.addField(field("count", n, FieldDescriptorProto.Type.TYPE_INT32));
        return FileDescriptor.buildFrom(
                        FileDescriptorProto.newBuilder()
                                .setName("flat.proto")
                                .setSyntax("proto3")
                                .addMessageType(msg)
                                .build(),
                        new FileDescriptor[0])
                .findMessageTypeByName("FlatMsg");
    }

    private Descriptor buildNestedDescriptor() throws Exception {
        var inner = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(field("value", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(field("code", 2, FieldDescriptorProto.Type.TYPE_INT32));
        var outer = DescriptorProto.newBuilder()
                .setName("Outer")
                .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(field("label", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("inner")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".Inner")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
        var fd = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("nested.proto")
                        .setSyntax("proto3")
                        .addMessageType(inner)
                        .addMessageType(outer)
                        .build(),
                new FileDescriptor[0]);
        return fd.findMessageTypeByName("Outer");
    }

    private Descriptor buildTimestampDescriptor() throws Exception {
        var proto = FileDescriptorProto.newBuilder()
                .setName("ts.proto")
                .setSyntax("proto3")
                .addDependency("google/protobuf/timestamp.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Event")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("occurred_at")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Timestamp")
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)));
        return FileDescriptor.buildFrom(
                        proto.build(),
                        new FileDescriptor[] {Timestamp.getDescriptor().getFile()})
                .findMessageTypeByName("Event");
    }

    private Descriptor buildRepeatedDescriptor() throws Exception {
        var proto = FileDescriptorProto.newBuilder()
                .setName("rep.proto")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Batch")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("ids")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)));
        return FileDescriptor.buildFrom(proto.build(), new FileDescriptor[0]).findMessageTypeByName("Batch");
    }

    // ── Message builders ─────────────────────────────────────────────────────────

    private DynamicMessage buildFlatMessage() {
        var b = DynamicMessage.newBuilder(flatDescriptor);
        b.setField(flatDescriptor.findFieldByName("id"), 100L);
        b.setField(flatDescriptor.findFieldByName("name"), "benchmark-item");
        b.setField(flatDescriptor.findFieldByName("status"), 2);
        b.setField(flatDescriptor.findFieldByName("score"), 9.87d);
        b.setField(flatDescriptor.findFieldByName("active"), true);
        b.setField(flatDescriptor.findFieldByName("description"), "a description string");
        b.setField(flatDescriptor.findFieldByName("price"), 1.5f);
        b.setField(flatDescriptor.findFieldByName("count"), 42);
        return b.build();
    }

    private DynamicMessage buildNestedMessage() throws Exception {
        Descriptor innerDesc = nestedDescriptor.findFieldByName("inner").getMessageType();
        DynamicMessage inner = DynamicMessage.newBuilder(innerDesc)
                .setField(innerDesc.findFieldByName("value"), "inner-value")
                .setField(innerDesc.findFieldByName("code"), 7)
                .build();
        return DynamicMessage.newBuilder(nestedDescriptor)
                .setField(nestedDescriptor.findFieldByName("id"), 42L)
                .setField(nestedDescriptor.findFieldByName("label"), "outer-label")
                .setField(nestedDescriptor.findFieldByName("inner"), inner)
                .build();
    }

    private DynamicMessage buildTimestampMessage() {
        Descriptor tsDesc = Timestamp.getDescriptor();
        DynamicMessage ts = DynamicMessage.newBuilder(tsDesc)
                .setField(tsDesc.findFieldByNumber(1), 1700000000L)
                .setField(tsDesc.findFieldByNumber(2), 0)
                .build();
        return DynamicMessage.newBuilder(timestampDescriptor)
                .setField(timestampDescriptor.findFieldByName("occurred_at"), ts)
                .build();
    }

    private DynamicMessage buildRepeatedMessage(int size) {
        FieldDescriptor idsFd = repeatedDescriptor.findFieldByName("ids");
        var b = DynamicMessage.newBuilder(repeatedDescriptor);
        for (long i = 0; i < size; i++) b.addRepeatedField(idsFd, i);
        return b.build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
