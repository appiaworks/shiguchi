package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.protobuf.extras.serde.typed.TypedParity;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TypedWriterPlanTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "foo_bar, FooBar",
        "foo, Foo",
        "field_1x, Field1X",
        "class, Class",
        "FooBar, FooBar",
        "_leading, Leading",
        "a__b, AB",
        "x1y2, X1Y2",
    })
    @DisplayName(
            "GIVEN a proto field name, WHEN capitalised the way protoc does, SHOULD match the generated accessor stem")
    void GIVEN_fieldName_WHEN_capitalised_SHOULD_matchProtoc(String protoName, String expected) throws Exception {
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("names.proto")
                        .setSyntax("proto3")
                        .setPackage("names")
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("M")
                                .addField(FieldDescriptorProto.newBuilder()
                                        .setName(protoName)
                                        .setNumber(1)
                                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                        .build(),
                new FileDescriptor[0]);
        FieldDescriptor fd = file.findMessageTypeByName("M").getFields().get(0);

        assertThat(TypedWriterPlan.capitalizedName(fd)).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "GIVEN the kitchen-sink class, WHEN planned, SHOULD read every field through the accessor protoc generated")
    void GIVEN_generatedClass_WHEN_planned_SHOULD_useProtocAccessors() {
        TypedWriterPlan plan = TypedWriterPlan.of(TypedParity.AllTypes.class, TypedParity.AllTypes.getDescriptor());
        Map<String, TypedWriterPlan.Field> byName = plan.fields().stream()
                .collect(Collectors.toMap(f -> f.descriptor().getName(), f -> f));

        assertThat(byName.get("class").getter().getName()).isEqualTo("getClass_");
        assertThat(byName.get("serialized_size").getter().getName()).isEqualTo("getSerializedSize_");
        assertThat(byName.get("field_1x").getter().getName()).isEqualTo("getField1X");
        assertThat(byName.get("kind").valueGetter().getName()).isEqualTo("getKindValue");
        assertThat(byName.get("kinds").valueGetter().getName()).isEqualTo("getKindsValue");
        assertThat(byName.get("kinds").count().getName()).isEqualTo("getKindsCount");
        assertThat(byName.get("m_lk").getter().getName()).isEqualTo("getMLkValueMap");
        assertThat(byName.get("m_ss").getter().getName()).isEqualTo("getMSsMap");
        assertThat(byName.get("opt_i32").has().getName()).isEqualTo("hasOptI32");
        assertThat(byName.get("choice_inner").has().getName()).isEqualTo("hasChoiceInner");
        assertThat(byName.get("i32").has()).isNull();
        assertThat(byName.get("inner").messageClass()).isEqualTo(TypedParity.Inner.class);
        assertThat(byName.get("m_im").messageClass()).isEqualTo(TypedParity.Inner.class);
        assertThat(byName.get("ts").messageClass())
                .as("a single-value type is not a nested writer")
                .isNull();
        assertThat(plan.nestedTypes())
                .containsEntry("typedparity.Inner", TypedParity.Inner.class)
                .doesNotContainKey("google.protobuf.Timestamp");
    }
}
