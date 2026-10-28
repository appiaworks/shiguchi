package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Under strict input the compiled parser and the reflective converter are one parser: for every
 * document they build the same message or refuse it with the same message. The corpus mixes
 * documents both accept with every kind of value strict input refuses.
 */
@DisplayName("Strict input — compiled parser and reflective converter agree")
class StrictParseParityTest {

    private static final JsonFactory JSON = new JsonFactory();
    private static final JsonDialect STRICT = JsonDialect.of(ProtoJsonMapping.DEFAULTS, List.of(), true);

    private static Descriptor everything;
    private static AbstractDynamicSerializer compiled;

    @BeforeAll
    static void compile() throws Exception {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
        everything = buildEverything();
        Descriptor item = everything.findNestedTypeByName("Item");
        compiled = STRICT.call(() -> DynamicSerializerCompiler.compile(everything));
        AbstractDynamicSerializer nested = STRICT.call(() -> DynamicSerializerCompiler.compile(item));
        compiled.setNested(item.getFullName(), nested);
    }

    /** What reading a document came to: the message, or the refusal's text. */
    private record Outcome(DynamicMessage message, String refusal) {}

    private static Outcome compiledRead(String json) throws java.io.IOException {
        return STRICT.call(() -> {
            try (JsonParser p = JSON.createParser(json)) {
                p.nextToken();
                return new Outcome(compiled.deserialize(p, DynamicMessage.newBuilder(everything)), null);
            } catch (ProtoJsonException e) {
                return new Outcome(null, e.getMessage());
            }
        });
    }

    private static Outcome reflectiveRead(String json) throws java.io.IOException {
        return STRICT.call(() -> {
            try (JsonParser p = JSON.createParser(json)) {
                p.nextToken();
                Map<String, Object> tree = JsonTrees.readObject(p);
                return new Outcome(ProtoMapConverter.dynamicFromMap(tree, everything), null);
            } catch (ProtoJsonException e) {
                return new Outcome(null, e.getMessage());
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                // accepted
                "{}",
                "{\"i32\":7,\"u32\":4294967295,\"i64\":\"-9223372036854775808\",\"u64\":\"18446744073709551615\"}",
                "{\"i32\":\"12\",\"i64\":12,\"u64\":12,\"i32b\":1e2,\"f\":1.5,\"d\":\"2.25\"}",
                "{\"f\":\"NaN\",\"d\":\"-Infinity\",\"b\":\"true\",\"s\":\"x\",\"by\":\"aGk=\"}",
                "{\"by\":\"-_8=\",\"e\":\"GREEN\",\"es\":[\"RED\",2,\"UNKNOWN_NAME\",7]}",
                "{\"ri\":[1,\"2\",3e0],\"rs\":[\"a\",\"b\"],\"ms\":{\"a\":1,\"b\":\"2\"},\"mi\":{\"-5\":\"x\"}}",
                "{\"item\":{\"name\":\"n\",\"qty\":3},\"items\":[{\"qty\":1},{\"name\":\"z\"}],\"unknown\":[1,{}]}",
                "{\"at\":\"2026-09-22T14:03:11Z\",\"v\":null,\"vs\":[1,null,\"x\"],\"w\":5,\"day\":\"2026-09-22\"}",
                "{\"i32\":null,\"item\":null,\"ri\":null,\"ms\":null,\"rs\":[]}",
                "{\"display_name\":\"proto name\",\"i32\":-2147483648}"
            })
    @DisplayName("GIVEN a document strict input accepts WHEN both parsers read it SHOULD build the same message")
    void given_acceptedDocument_when_readStrictly_should_buildTheSameMessage(String json) throws Exception {
        Outcome viaCompiled = compiledRead(json);

        assertThat(viaCompiled.refusal()).as("refusal").isNull();
        assertThat(viaCompiled).isEqualTo(reflectiveRead(json));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "{\"i32\":\"abc\"}",
                "{\"i32\":1.5}",
                "{\"i32\":2147483648}",
                "{\"i32\":true}",
                "{\"u32\":-1}",
                "{\"u64\":\"-1\"}",
                "{\"i64\":\"9223372036854775808\"}",
                "{\"f\":1e39}",
                "{\"d\":\"one\"}",
                "{\"b\":1}",
                "{\"b\":\"yes\"}",
                "{\"s\":12}",
                "{\"s\":{}}",
                "{\"by\":\"not base64!\"}",
                "{\"e\":true}",
                "{\"e\":1.5}",
                "{\"ri\":5}",
                "{\"ri\":[1,null]}",
                "{\"ri\":[1,\"x\"]}",
                "{\"rs\":[\"a\",1]}",
                "{\"ms\":[1]}",
                "{\"ms\":{\"a\":\"x\"}}",
                "{\"ms\":{\"a\":null}}",
                "{\"mi\":{\"abc\":\"x\"}}",
                "{\"item\":\"n\"}",
                "{\"item\":{\"qty\":\"many\"}}",
                "{\"items\":[1]}",
                "{\"items\":[{\"qty\":1.5}]}",
                "{\"items\":{\"qty\":1}}",
                "{\"at\":\"yesterday\"}",
                "{\"w\":\"abc\"}",
                "{\"day\":\"2026-02-30\"}"
            })
    @DisplayName("GIVEN a value strict input refuses WHEN both parsers read it SHOULD refuse it with the same message")
    void given_refusedDocument_when_readStrictly_should_refuseAlike(String json) throws Exception {
        Outcome viaCompiled = compiledRead(json);

        assertThat(viaCompiled.refusal()).as("refusal").isNotNull();
        assertThat(viaCompiled).isEqualTo(reflectiveRead(json));
    }

    @Test
    @DisplayName(
            "GIVEN the compiled strict parser WHEN it reads values it would once coerce SHOULD refuse them naming field, type and value")
    void given_compiledStrict_when_valueDoesNotFit_should_refuseNamingFieldTypeAndValue() throws Exception {
        assertThat(compiledRead("{\"i32\":\"abc\"}").refusal())
                .isEqualTo(
                        "Invalid int32 value 'abc': t.Everything.i32 takes a whole number from -2147483648 to 2147483647");
        assertThat(compiledRead("{\"items\":[{\"qty\":1.5}]}").refusal()).contains("t.Everything.Item.qty");
        assertThat(compiledRead("{\"s\":12}").refusal())
                .isEqualTo("Invalid string value '12': t.Everything.s takes a JSON string");
    }

    @Test
    @DisplayName(
            "GIVEN the compiled strict parser WHEN it reads accepted forms SHOULD build the values proto3 JSON gives them")
    void given_compiledStrict_when_acceptedForms_should_buildProto3Values() throws Exception {
        DynamicMessage m = compiledRead(
                        "{\"i32b\":1e2,\"u32\":4294967295,\"u64\":\"18446744073709551615\",\"es\":[\"RED\",\"NOPE\",2]}")
                .message();

        assertThat(m.getField(field("i32b"))).isEqualTo(100);
        assertThat(m.getField(field("u32"))).isEqualTo(-1); // the uint32 bit pattern
        assertThat(m.getField(field("u64"))).isEqualTo(-1L);
        assertThat((List<?>) m.getField(field("es"))).hasSize(2); // the unknown name is ignored
    }

    @Test
    @DisplayName(
            "GIVEN a lenient dialect WHEN the compiled parser reads \"abc\" for an int32 SHOULD still coerce it to 0, as before")
    void given_lenientDialect_when_compiledReadsAbc_should_coerceAsBefore() throws Exception {
        JsonDialect lenient = JsonDialect.of(ProtoJsonMapping.DEFAULTS, List.of(), false);
        AbstractDynamicSerializer lenientCompiled = lenient.call(() -> DynamicSerializerCompiler.compile(everything));

        DynamicMessage m = lenient.call(() -> {
            try (JsonParser p = JSON.createParser("{\"i32\":\"abc\"}")) {
                p.nextToken();
                return lenientCompiled.deserialize(p, DynamicMessage.newBuilder(everything));
            }
        });

        assertThat(m.getField(field("i32"))).isEqualTo(0);
    }

    private static FieldDescriptor field(String name) {
        return everything.findFieldByName(name);
    }

    // ── The message under test ───────────────────────────────────────────────────────

    private static Descriptor buildEverything() throws Exception {
        DescriptorProto item = DescriptorProto.newBuilder()
                .setName("Item")
                .addField(scalar("name", 1, Type.TYPE_STRING))
                .addField(scalar("qty", 2, Type.TYPE_INT32))
                .build();
        DescriptorProto msEntry = DescriptorProto.newBuilder()
                .setName("MsEntry")
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(scalar("key", 1, Type.TYPE_STRING))
                .addField(scalar("value", 2, Type.TYPE_INT32))
                .build();
        DescriptorProto miEntry = DescriptorProto.newBuilder()
                .setName("MiEntry")
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(scalar("key", 1, Type.TYPE_INT64))
                .addField(scalar("value", 2, Type.TYPE_STRING))
                .build();
        DescriptorProto everythingProto = DescriptorProto.newBuilder()
                .setName("Everything")
                .addNestedType(item)
                .addNestedType(msEntry)
                .addNestedType(miEntry)
                .addField(scalar("i32", 1, Type.TYPE_INT32))
                .addField(scalar("i32b", 2, Type.TYPE_SINT32))
                .addField(scalar("u32", 3, Type.TYPE_UINT32))
                .addField(scalar("i64", 4, Type.TYPE_INT64))
                .addField(scalar("u64", 5, Type.TYPE_UINT64))
                .addField(scalar("f", 6, Type.TYPE_FLOAT))
                .addField(scalar("d", 7, Type.TYPE_DOUBLE))
                .addField(scalar("b", 8, Type.TYPE_BOOL))
                .addField(scalar("s", 9, Type.TYPE_STRING))
                .addField(scalar("by", 10, Type.TYPE_BYTES))
                .addField(typed("e", 11, Type.TYPE_ENUM, ".t.Color", Label.LABEL_OPTIONAL))
                .addField(typed("es", 12, Type.TYPE_ENUM, ".t.Color", Label.LABEL_REPEATED))
                .addField(scalar("ri", 13, Type.TYPE_INT32).toBuilder().setLabel(Label.LABEL_REPEATED))
                .addField(scalar("rs", 14, Type.TYPE_STRING).toBuilder().setLabel(Label.LABEL_REPEATED))
                .addField(typed("ms", 15, Type.TYPE_MESSAGE, ".t.Everything.MsEntry", Label.LABEL_REPEATED))
                .addField(typed("mi", 16, Type.TYPE_MESSAGE, ".t.Everything.MiEntry", Label.LABEL_REPEATED))
                .addField(typed("item", 17, Type.TYPE_MESSAGE, ".t.Everything.Item", Label.LABEL_OPTIONAL))
                .addField(typed("items", 18, Type.TYPE_MESSAGE, ".t.Everything.Item", Label.LABEL_REPEATED))
                .addField(typed("at", 19, Type.TYPE_MESSAGE, ".google.protobuf.Timestamp", Label.LABEL_OPTIONAL))
                .addField(typed("v", 20, Type.TYPE_MESSAGE, ".google.protobuf.Value", Label.LABEL_OPTIONAL))
                .addField(typed("vs", 21, Type.TYPE_MESSAGE, ".google.protobuf.Value", Label.LABEL_REPEATED))
                .addField(typed("w", 22, Type.TYPE_MESSAGE, ".google.protobuf.Int32Value", Label.LABEL_OPTIONAL))
                .addField(typed("day", 23, Type.TYPE_MESSAGE, ".google.type.Date", Label.LABEL_OPTIONAL))
                .addField(scalar("display_name", 24, Type.TYPE_STRING))
                .build();
        EnumDescriptorProto color = EnumDescriptorProto.newBuilder()
                .setName("Color")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("UNSET").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(1))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("GREEN").setNumber(2))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("t/everything.proto")
                .setPackage("t")
                .setSyntax("proto3")
                .addDependency("google/protobuf/timestamp.proto")
                .addDependency("google/protobuf/struct.proto")
                .addDependency("google/protobuf/wrappers.proto")
                .addDependency("google/type/date.proto")
                .addMessageType(everythingProto)
                .addEnumType(color)
                .build();
        FileDescriptor built = FileDescriptor.buildFrom(file, new FileDescriptor[] {
            com.google.protobuf.Timestamp.getDescriptor().getFile(),
            com.google.protobuf.Struct.getDescriptor().getFile(),
            com.google.protobuf.Int32Value.getDescriptor().getFile(),
            com.google.type.Date.getDescriptor().getFile()
        });
        return built.findMessageTypeByName("Everything");
    }

    private static FieldDescriptorProto scalar(String name, int number, Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto typed(String name, int number, Type type, String typeName, Label label) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setTypeName(typeName)
                .setLabel(label)
                .build();
    }
}
