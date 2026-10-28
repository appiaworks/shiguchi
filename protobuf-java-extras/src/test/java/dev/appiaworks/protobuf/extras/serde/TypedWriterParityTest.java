package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.typed.TypedParity;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Tier 1 contract: for a kitchen-sink message compiled to Java, the typed writer must emit
 * the JSON the Tier 2 serializer and the reflective writer emit for the dynamic form of the
 * same message, in the gateway's default dialect (google.type as text) — and, in the plain
 * proto3 dialect, the JSON {@code JsonFormat} prints.
 */
class TypedWriterParityTest {

    private static final JsonFactory JSON = new JsonFactory();

    private static TypedMessageWriter writer;
    private static Map<Descriptor, AbstractDynamicSerializer> tier2;

    @BeforeAll
    static void compile() throws Exception {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
        writer = TypedWriters.compile(TypedParity.AllTypes.getDefaultInstance(), new HashMap<>());
        Descriptor all = TypedParity.AllTypes.getDescriptor();
        tier2 = SerializerCompilation.compile(SerializerCompilation.reachable(all), d -> null, (d, e) -> {
            throw new AssertionError("Tier 2 did not compile " + d.getFullName(), e);
        });
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765, 10946})
    @DisplayName(
            "GIVEN a random kitchen-sink message, WHEN the typed writer prints it, SHOULD emit what Tier 2 and the reflective writer emit for its dynamic form")
    void GIVEN_randomMessage_WHEN_typedWriterPrints_SHOULD_matchTier2AndReflective(long seed) throws Exception {
        TypedParity.AllTypes typed = randomAllTypes(new Random(seed));
        DynamicMessage dynamic = DynamicMessage.parseFrom(typed.getDescriptorForType(), typed.toByteArray());

        String typedJson = write(gen -> writer.write(typed, gen));
        String tier2Json = write(gen -> tier2.get(typed.getDescriptorForType()).serialize(dynamic, gen));
        String reflectiveJson = write(gen -> ProtoMapConverter.writeMessageJson(gen, dynamic));

        assertThat(tree(typedJson))
                .as("seed %d\ntyped: %s\ntier2: %s", seed, typedJson, tier2Json)
                .isEqualTo(tree(tier2Json));
        assertThat(tree(typedJson))
                .as("seed %d\ntyped: %s\nreflective: %s", seed, typedJson, reflectiveJson)
                .isEqualTo(tree(reflectiveJson));
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    @DisplayName(
            "GIVEN the plain proto3 dialect, WHEN the typed writer prints a random message, SHOULD emit what JsonFormat prints")
    void GIVEN_proto3Dialect_WHEN_typedWriterPrints_SHOULD_matchJsonFormat(long seed) throws Exception {
        TypedParity.AllTypes typed = randomAllTypes(new Random(seed));
        JsonDialect proto3 = JsonDialect.of(ProtoJsonMapping.PROTO3, List.of());

        String typedJson = proto3.call(() -> {
            TypedMessageWriter plain = TypedWriters.compile(TypedParity.AllTypes.getDefaultInstance(), new HashMap<>());
            return write(gen -> plain.write(typed, gen));
        });
        @SuppressWarnings("deprecation")
        String jsonFormat = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(typed);

        assertThat(tree(typedJson))
                .as("seed %d\ntyped: %s\nJsonFormat: %s", seed, typedJson, jsonFormat)
                .isEqualTo(tree(jsonFormat));
    }

    @Test
    @DisplayName(
            "GIVEN a field protoc renamed (class, serialized_size), WHEN written, SHOULD appear under its JSON name")
    void GIVEN_renamedFields_WHEN_written_SHOULD_useJsonNames() throws Exception {
        TypedParity.AllTypes typed = TypedParity.AllTypes.newBuilder()
                .setClass_("c")
                .setSerializedSize_(7)
                .setField1X("d")
                .build();

        String json = write(gen -> writer.write(typed, gen));

        assertThat(json)
                .contains("\"class\":\"c\"")
                .contains("\"serializedSize\":7")
                .contains("\"field1x\":\"d\"");
    }

    @Test
    @DisplayName(
            "GIVEN an enum number the schema does not know, WHEN written, SHOULD print the number as JsonFormat does")
    void GIVEN_unknownEnumNumber_WHEN_written_SHOULD_printNumber() throws Exception {
        TypedParity.AllTypes typed = TypedParity.AllTypes.newBuilder()
                .setKindValue(99)
                .addKindsValue(7)
                .putMLk(1L, TypedParity.Kind.KIND_B)
                .build();

        String json = write(gen -> writer.write(typed, gen));

        assertThat(json).contains("\"kind\":99").contains("\"kinds\":[7]").contains("\"mLk\":{\"1\":\"KIND_B\"}");
    }

    @Test
    @DisplayName(
            "GIVEN a message type with a single JSON value, WHEN compiled, SHOULD write that value at the top level")
    void GIVEN_singleValueType_WHEN_compiled_SHOULD_writeTheValue() throws Exception {
        Timestamp ts = Timestamp.newBuilder()
                .setSeconds(1_700_000_000L)
                .setNanos(500_000_000)
                .build();
        TypedMessageWriter tsWriter = TypedWriters.compile(ts, new HashMap<>());

        String json = write(gen -> tsWriter.write(ts, gen));

        assertThat(json).isEqualTo(JsonFormat.printer().print(ts));
    }

    @Test
    @DisplayName("GIVEN a type reaching google.protobuf.Any, WHEN compiled, SHOULD refuse: Any needs a type registry")
    void GIVEN_typeReachingAny_WHEN_compiled_SHOULD_refuse() {
        assertThatThrownBy(() -> TypedWriters.compile(TypedParity.WithAny.getDefaultInstance(), new HashMap<>()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName(
            "GIVEN nested message fields, WHEN compiled, SHOULD wire the writers of the types they reach, map values included")
    void GIVEN_nestedFields_WHEN_compiled_SHOULD_wireNestedWriters() throws Exception {
        Map<Class<? extends Message>, TypedMessageWriter> cache = new HashMap<>();
        TypedMessageWriter root = TypedWriters.compile(TypedParity.AllTypes.getDefaultInstance(), cache);

        assertThat(root.nestedWriter("typedparity.Inner")).isSameAs(cache.get(TypedParity.Inner.class));
        assertThat(cache.get(TypedParity.Inner.class).nestedWriter("typedparity.Inner"))
                .as("a self-referencing type is wired to itself")
                .isSameAs(cache.get(TypedParity.Inner.class));
        assertThat(cache).containsKey(TypedParity.AllTypes.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Body {
        void run(JsonGenerator gen) throws IOException;
    }

    private static String write(Body body) throws IOException {
        StringWriter out = new StringWriter();
        try (JsonGenerator gen = JSON.createGenerator(out)) {
            body.run(gen);
        }
        return out.toString();
    }

    private static Object tree(String json) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            parser.nextToken();
            return JsonTrees.read(parser);
        }
    }

    static TypedParity.AllTypes randomAllTypes(Random rnd) throws Exception {
        DynamicMessage dynamic = RandomMessages.random(TypedParity.AllTypes.getDescriptor(), rnd, 0);
        return TypedParity.AllTypes.parseFrom(dynamic.toByteArray());
    }

    /** A random message of any descriptor, every field shape covered, well-known types kept valid. */
    static final class RandomMessages {

        private RandomMessages() {}

        static DynamicMessage random(Descriptor descriptor, Random rnd, int depth) {
            DynamicMessage.Builder b = DynamicMessage.newBuilder(descriptor);
            for (FieldDescriptor fd : descriptor.getFields()) {
                if (fd.getRealContainingOneof() != null) {
                    // One member per oneof, chosen among the oneof's fields, or none at all
                    List<FieldDescriptor> members = fd.getRealContainingOneof().getFields();
                    if (fd != members.get(0)) continue;
                    int pick = rnd.nextInt(members.size() + 1);
                    if (pick == members.size()) continue;
                    FieldDescriptor chosen = members.get(pick);
                    Object value = value(chosen, rnd, depth);
                    if (value != null) b.setField(chosen, value);
                    continue;
                }
                if (fd.isMapField()) {
                    for (int i = 0, n = rnd.nextInt(4); i < n; i++) {
                        b.addRepeatedField(fd, random(fd.getMessageType(), rnd, depth + 1));
                    }
                } else if (fd.isRepeated()) {
                    for (int i = 0, n = rnd.nextInt(4); i < n; i++) {
                        Object value = value(fd, rnd, depth);
                        if (value != null) b.addRepeatedField(fd, value);
                    }
                } else if (fd.hasPresence() && rnd.nextInt(3) == 0) {
                    continue; // left unset
                } else {
                    Object value = value(fd, rnd, depth);
                    if (value != null) b.setField(fd, value);
                }
            }
            return b.build();
        }

        private static Object value(FieldDescriptor fd, Random rnd, int depth) {
            return switch (fd.getType()) {
                case INT32, SINT32, SFIXED32, UINT32, FIXED32 -> rnd.nextInt();
                case INT64, SINT64, SFIXED64, UINT64, FIXED64 -> rnd.nextLong();
                case FLOAT -> rnd.nextInt(10_000) / 2.0f;
                case DOUBLE -> rnd.nextInt(10_000) / 4.0d;
                case BOOL -> rnd.nextBoolean();
                case STRING -> "s-" + rnd.nextInt(1_000_000);
                case BYTES -> {
                    byte[] bytes = new byte[rnd.nextInt(6)];
                    rnd.nextBytes(bytes);
                    yield ByteString.copyFrom(bytes);
                }
                case ENUM ->
                    fd.getEnumType()
                            .getValues()
                            .get(rnd.nextInt(fd.getEnumType().getValues().size()));
                case MESSAGE -> message(fd.getMessageType(), rnd, depth);
                default -> null;
            };
        }

        private static DynamicMessage message(Descriptor d, Random rnd, int depth) {
            DynamicMessage.Builder b = DynamicMessage.newBuilder(d);
            switch (d.getFullName()) {
                case "google.protobuf.Timestamp" -> {
                    set(b, "seconds", (long) rnd.nextInt(2_000_000_000));
                    set(b, "nanos", rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(1_000_000_000));
                }
                case "google.protobuf.Duration" -> {
                    long seconds = rnd.nextInt(200_000) - 100_000L;
                    int nanos = rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(1_000_000_000);
                    set(b, "seconds", seconds);
                    set(b, "nanos", seconds < 0 ? -nanos : nanos);
                }
                case "google.protobuf.FieldMask" -> {
                    for (int i = 0, n = rnd.nextInt(3); i < n; i++) {
                        b.addRepeatedField(d.findFieldByName("paths"), "path_" + rnd.nextInt(10) + ".sub_field");
                    }
                }
                case "google.protobuf.Struct" -> {
                    FieldDescriptor fields = d.findFieldByName("fields");
                    Descriptor entry = fields.getMessageType();
                    for (int i = 0, n = depth > 2 ? 0 : rnd.nextInt(3); i < n; i++) {
                        b.addRepeatedField(
                                fields,
                                DynamicMessage.newBuilder(entry)
                                        .setField(entry.findFieldByName("key"), "k" + i)
                                        .setField(
                                                entry.findFieldByName("value"),
                                                message(
                                                        entry.findFieldByName("value")
                                                                .getMessageType(),
                                                        rnd,
                                                        depth + 1))
                                        .build());
                    }
                }
                case "google.protobuf.Value" -> {
                    int kind = depth > 2 ? rnd.nextInt(4) : rnd.nextInt(6);
                    switch (kind) {
                        case 0 ->
                            set(
                                    b,
                                    "null_value",
                                    d.findFieldByName("null_value")
                                            .getEnumType()
                                            .getValues()
                                            .get(0));
                        case 1 -> set(b, "number_value", rnd.nextInt(1000) / 8.0d);
                        case 2 -> set(b, "string_value", "v-" + rnd.nextInt(100));
                        case 3 -> set(b, "bool_value", rnd.nextBoolean());
                        case 4 ->
                            set(
                                    b,
                                    "struct_value",
                                    message(d.findFieldByName("struct_value").getMessageType(), rnd, depth + 1));
                        default ->
                            set(
                                    b,
                                    "list_value",
                                    message(d.findFieldByName("list_value").getMessageType(), rnd, depth + 1));
                    }
                }
                case "google.protobuf.ListValue" -> {
                    FieldDescriptor values = d.findFieldByName("values");
                    for (int i = 0, n = depth > 2 ? 0 : rnd.nextInt(3); i < n; i++) {
                        b.addRepeatedField(values, message(values.getMessageType(), rnd, depth + 1));
                    }
                }
                case "google.type.Date" -> {
                    set(b, "year", 1 + rnd.nextInt(9999));
                    set(b, "month", 1 + rnd.nextInt(12));
                    set(b, "day", 1 + rnd.nextInt(28));
                }
                case "google.type.Money" -> {
                    long units = rnd.nextInt(100_000) - 50_000L;
                    int nanos = rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(1_000_000_000);
                    set(b, "currency_code", "USD");
                    set(b, "units", units);
                    set(b, "nanos", units < 0 ? -nanos : nanos);
                }
                case "google.type.Decimal" -> set(b, "value", rnd.nextInt(100_000) + "." + rnd.nextInt(100));
                case "google.type.TimeOfDay" -> {
                    set(b, "hours", rnd.nextInt(24));
                    set(b, "minutes", rnd.nextInt(60));
                    set(b, "seconds", rnd.nextInt(60));
                    set(b, "nanos", rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(1_000_000_000));
                }
                default -> {
                    if (depth > 3) return b.build();
                    return random(d, rnd, depth + 1);
                }
            }
            return b.build();
        }

        private static void set(DynamicMessage.Builder b, String field, Object value) {
            b.setField(b.getDescriptorForType().findFieldByName(field), value);
        }
    }
}
