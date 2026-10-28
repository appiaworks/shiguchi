package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the two-pass nested-serializer wiring added to DynamicSerializerFactory.precompile().
 *
 * Pass 1 compiles every message descriptor into an AbstractDynamicSerializer and stores it in the
 * ServiceEndpointContext cache.  Pass 2 iterates over each compiled serializer's fields and, for
 * every MESSAGE-type field that is NOT google.protobuf.Timestamp, wires the already-compiled inner
 * serializer via AbstractDynamicSerializer.setNested().
 *
 * All tests use real proto Descriptor objects built inline — no mocking of proto types.
 */
class DynamicSerializerFactoryNestedWiringTest {

    @BeforeAll
    static void requiresCodegen() {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
    }

    private DynamicSerializerFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DynamicSerializerFactory();
    }

    // ── Pass 1: serializers compiled for all message types in the file ──────────

    @Test
    @DisplayName(
            "GIVEN a file descriptor with Outer and Inner message types, WHEN precompile(), SHOULD compile a serializer for each message type")
    void GIVEN_fileWithOuterAndInnerMessages_WHEN_precompile_SHOULD_compileBothSerializers() throws Exception {
        FileDescriptor fd = buildFileWithNestedMessage("outer_inner.proto");
        ServiceEndpointContext ctx = buildContext(fd);
        factory.precompile(ctx);

        Descriptor outer = fd.findMessageTypeByName("Outer");
        Descriptor inner = fd.findMessageTypeByName("Inner");
        assertThat(ctx.serializerFor(outer))
                .as("Outer serializer should be compiled")
                .isPresent();
        assertThat(ctx.serializerFor(inner))
                .as("Inner serializer should be compiled")
                .isPresent();
    }

    // ── Pass 2: nested serializer wired into outer via setNested() ───────────────

    @Test
    @DisplayName(
            "GIVEN Outer has a MESSAGE field pointing to Inner, WHEN precompile() completes, SHOULD wire Inner serializer into Outer via setNested()")
    void GIVEN_outerHasMessageField_WHEN_precompile_SHOULD_wireInnerSerializerIntoOuter() throws Exception {
        FileDescriptor fd = buildFileWithNestedMessage("wiring.proto");
        ServiceEndpointContext ctx = buildContext(fd);

        factory.precompile(ctx);

        Descriptor outer = fd.findMessageTypeByName("Outer");
        Descriptor inner = fd.findMessageTypeByName("Inner");
        AbstractDynamicSerializer outerSer = ctx.serializerFor(outer).orElseThrow();
        AbstractDynamicSerializer innerSer = ctx.serializerFor(inner).orElseThrow();

        // nestedSerializer() should return the compiled inner serializer — same instance
        AbstractDynamicSerializer wired = nestedSerializer(outerSer, inner.getFullName());
        assertThat(wired)
                .as("Inner serializer should be wired into Outer by Pass 2")
                .isSameAs(innerSer);
    }

    // ── Pass 2: Timestamp fields excluded from nested wiring ─────────────────────

    @Test
    @DisplayName(
            "GIVEN Outer has a google.protobuf.Timestamp field, WHEN precompile() completes, SHOULD NOT wire a nested serializer for Timestamp")
    void GIVEN_outerHasTimestampField_WHEN_precompile_SHOULD_notWireTimestampAsNested() throws Exception {
        FileDescriptor fd = buildFileWithTimestampField("ts_wiring.proto");
        ServiceEndpointContext ctx = buildContext(fd);

        factory.precompile(ctx);

        Descriptor outer = fd.findMessageTypeByName("Event");
        AbstractDynamicSerializer outerSer = ctx.serializerFor(outer).orElseThrow();

        AbstractDynamicSerializer wired = nestedSerializer(outerSer, "google.protobuf.Timestamp");
        assertThat(wired)
                .as("Timestamp must never be wired as a nested serializer — it is handled specially")
                .isNull();
    }

    // ── Pass 2: flat message produces no nested wiring ───────────────────────────

    @Test
    @DisplayName(
            "GIVEN a flat message with only scalar fields, WHEN precompile(), SHOULD compile the serializer with no nested serializers registered")
    void GIVEN_flatMessageWithScalarFields_WHEN_precompile_SHOULD_noNestedWiring() throws Exception {
        FileDescriptor fd = buildFlatFile("flat.proto");
        ServiceEndpointContext ctx = buildContext(fd);

        factory.precompile(ctx);

        Descriptor flat = fd.findMessageTypeByName("Flat");
        AbstractDynamicSerializer ser = ctx.serializerFor(flat).orElseThrow();

        // No nested serializers were wired; querying any name should return null
        assertThat(nestedSerializer(ser, "anything.NonExistent")).isNull();
    }

    // ── nestedSerializer() look-up after wiring ────────────────────────────────

    @Test
    @DisplayName(
            "GIVEN nested serializer wired via setNested(), WHEN nestedSerializer() called with that type name, SHOULD return the registered serializer")
    void GIVEN_nestedWired_WHEN_nestedSerializerCalledWithTypeName_SHOULD_returnRegistered() throws Exception {
        FileDescriptor fd = buildFileWithNestedMessage("lookup.proto");
        ServiceEndpointContext ctx = buildContext(fd);
        factory.precompile(ctx);

        Descriptor outer = fd.findMessageTypeByName("Outer");
        Descriptor inner = fd.findMessageTypeByName("Inner");
        AbstractDynamicSerializer outerSer = ctx.serializerFor(outer).orElseThrow();

        assertThat(nestedSerializer(outerSer, inner.getFullName()))
                .as("nestedSerializer() must return the serializer registered by Pass 2")
                .isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN nestedSerializer() called with an unknown type name, SHOULD return null (generated code falls back to ProtoMapConverter)")
    void GIVEN_nestedSerializerCalledWithUnknownType_SHOULD_returnNull() throws Exception {
        FileDescriptor fd = buildFileWithNestedMessage("fallback.proto");
        ServiceEndpointContext ctx = buildContext(fd);
        factory.precompile(ctx);

        Descriptor outer = fd.findMessageTypeByName("Outer");
        AbstractDynamicSerializer outerSer = ctx.serializerFor(outer).orElseThrow();

        assertThat(nestedSerializer(outerSer, "com.example.DoesNotExist"))
                .as("Unknown type name must produce null so generated code falls back to ProtoMapConverter")
                .isNull();
    }

    // ── toMap() with wired nested serializer delegates to inner serializer ────────

    @Test
    @DisplayName(
            "GIVEN nested wiring complete, WHEN toMap() called on outer message with nested message set, SHOULD return inner fields in a nested Map")
    void GIVEN_nestedWiringComplete_WHEN_toMapCalledOnOuterWithNestedSet_SHOULD_delegateToInnerSerializer()
            throws Exception {
        FileDescriptor fd = buildFileWithNestedMessage("tomap.proto");
        ServiceEndpointContext ctx = buildContext(fd);
        factory.precompile(ctx);

        Descriptor outer = fd.findMessageTypeByName("Outer");
        Descriptor inner = fd.findMessageTypeByName("Inner");
        AbstractDynamicSerializer outerSer = ctx.serializerFor(outer).orElseThrow();

        DynamicMessage innerMsg = DynamicMessage.newBuilder(inner)
                .setField(inner.findFieldByName("name"), "hello")
                .build();
        DynamicMessage outerMsg = DynamicMessage.newBuilder(outer)
                .setField(outer.findFieldByName("inner"), innerMsg)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = outerSer.toMap(outerMsg);

        assertThat(result).containsKey("inner");
        @SuppressWarnings("unchecked")
        Map<String, Object> innerMap = (Map<String, Object>) result.get("inner");
        assertThat(innerMap)
                .as("Inner message fields should appear in the nested map produced by the compiled inner serializer")
                .containsEntry("name", "hello");
    }

    // ── repeated MESSAGE field uses nested serializer for each element ────────────

    @Test
    @DisplayName(
            "GIVEN outer message with repeated MESSAGE field, WHEN toMap() called with two inner elements, SHOULD return a List of Maps each containing inner fields")
    void GIVEN_repeatedMessageField_WHEN_toMapCalled_SHOULD_useNestedSerializerForEachElement() throws Exception {
        FileDescriptor fd = buildFileWithRepeatedNestedMessage("repeated.proto");
        ServiceEndpointContext ctx = buildContext(fd);
        factory.precompile(ctx);

        Descriptor outer = fd.findMessageTypeByName("Outer");
        Descriptor inner = fd.findMessageTypeByName("Inner");
        AbstractDynamicSerializer outerSer = ctx.serializerFor(outer).orElseThrow();

        DynamicMessage inner1 = DynamicMessage.newBuilder(inner)
                .setField(inner.findFieldByName("name"), "first")
                .build();
        DynamicMessage inner2 = DynamicMessage.newBuilder(inner)
                .setField(inner.findFieldByName("name"), "second")
                .build();
        DynamicMessage outerMsg = DynamicMessage.newBuilder(outer)
                .addRepeatedField(outer.findFieldByName("items"), inner1)
                .addRepeatedField(outer.findFieldByName("items"), inner2)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = outerSer.toMap(outerMsg);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("name", "first");
        assertThat(items.get(1)).containsEntry("name", "second");
    }

    // ── repeated scalar fromMap() coerces String elements to the field type ───────

    @Test
    @DisplayName(
            "GIVEN a repeated int32 field, WHEN fromMap() called with String values in the list, SHOULD coerce all elements to Integer")
    void GIVEN_repeatedInt32Field_WHEN_fromMapCalledWithStringValues_SHOULD_coerceToInt() throws Exception {
        FileDescriptor fd = buildFileWithRepeatedInt32("repeated_scalar.proto");
        Descriptor descriptor = fd.findMessageTypeByName("Batch");
        ServiceEndpointContext ctx = buildContext(fd);
        factory.precompile(ctx);

        AbstractDynamicSerializer ser = ctx.serializerFor(descriptor).orElseThrow();

        DynamicMessage result =
                ser.fromMap(Map.of("counts", List.of("10", "20", "30")), DynamicMessage.newBuilder(descriptor));

        @SuppressWarnings("unchecked")
        List<Integer> counts = (List<Integer>) result.getField(descriptor.findFieldByName("counts"));
        assertThat(counts)
                .as("String values in a repeated int32 list should be coerced to Integer by fromMap()")
                .containsExactly(10, 20, 30);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Builds a FileDescriptor containing two messages:
     * <pre>
     *   message Inner { string name = 1; }
     *   message Outer { string title = 1; Inner inner = 2; int32 count = 3; }
     * </pre>
     */
    private static FileDescriptor buildFileWithNestedMessage(String protoName) throws Exception {
        DescriptorProto inner = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto outer = DescriptorProto.newBuilder()
                .setName("Outer")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("title")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("inner")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".Inner")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("count")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName(protoName)
                        .setSyntax("proto3")
                        .addMessageType(inner)
                        .addMessageType(outer)
                        .build(),
                new FileDescriptor[0]);
    }

    /**
     * Builds a FileDescriptor with a repeated MESSAGE field:
     * <pre>
     *   message Inner { string name = 1; }
     *   message Outer { repeated Inner items = 1; }
     * </pre>
     */
    private static FileDescriptor buildFileWithRepeatedNestedMessage(String protoName) throws Exception {
        DescriptorProto inner = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto outer = DescriptorProto.newBuilder()
                .setName("Outer")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("items")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".Inner")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName(protoName)
                        .setSyntax("proto3")
                        .addMessageType(inner)
                        .addMessageType(outer)
                        .build(),
                new FileDescriptor[0]);
    }

    /**
     * Builds a FileDescriptor with a Timestamp field:
     * <pre>
     *   message Event { google.protobuf.Timestamp occurred_at = 1; }
     * </pre>
     */
    private static FileDescriptor buildFileWithTimestampField(String protoName) throws Exception {
        DescriptorProto event = DescriptorProto.newBuilder()
                .setName("Event")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("occurred_at")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.protobuf.Timestamp")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName(protoName)
                        .setSyntax("proto3")
                        .addDependency("google/protobuf/timestamp.proto")
                        .addMessageType(event)
                        .build(),
                new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
    }

    /**
     * Builds a flat FileDescriptor with only scalar fields:
     * <pre>
     *   message Flat { string label = 1; int32 value = 2; }
     * </pre>
     */
    private static FileDescriptor buildFlatFile(String protoName) throws Exception {
        DescriptorProto flat = DescriptorProto.newBuilder()
                .setName("Flat")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("label")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("value")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName(protoName)
                        .setSyntax("proto3")
                        .addMessageType(flat)
                        .build(),
                new FileDescriptor[0]);
    }

    /**
     * Builds a FileDescriptor with a repeated int32 field:
     * <pre>
     *   message Batch { repeated int32 counts = 1; }
     * </pre>
     */
    private static FileDescriptor buildFileWithRepeatedInt32(String protoName) throws Exception {
        DescriptorProto batch = DescriptorProto.newBuilder()
                .setName("Batch")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("counts")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName(protoName)
                        .setSyntax("proto3")
                        .addMessageType(batch)
                        .build(),
                new FileDescriptor[0]);
    }

    /**
     * Constructs a ServiceEndpointContext pre-loaded with the given FileDescriptor.
     * ManagedChannel is null — precompile() never uses the channel.
     */
    private static ServiceEndpointContext buildContext(FileDescriptor fd) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("test-svc");
        def.setHost("localhost");
        def.setPort(50051);

        ServiceEndpointContext ctx = new ServiceEndpointContext(def, null);
        ctx.setFileDescriptors(Map.of(fd.getName(), fd));
        return ctx;
    }

    /** {@code nestedSerializer} is protected, and AbstractDynamicSerializer lives in another package. */
    private static AbstractDynamicSerializer nestedSerializer(AbstractDynamicSerializer ser, String fieldFullName) {
        try {
            Method m = AbstractDynamicSerializer.class.getDeclaredMethod("nestedSerializer", String.class);
            m.setAccessible(true);
            return (AbstractDynamicSerializer) m.invoke(ser, fieldFullName);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
