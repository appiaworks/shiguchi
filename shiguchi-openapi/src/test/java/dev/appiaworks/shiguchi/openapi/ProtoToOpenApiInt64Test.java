package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.DurationFormat;
import io.swagger.v3.oas.models.media.Schema;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 64-bit integer fields are described the way shiguchi.json.int64-as writes them. */
@DisplayName("ProtoToOpenApiSchemaConverter — 64-bit integers")
class ProtoToOpenApiInt64Test {

    private static final Descriptor COUNTERS = buildCounters();

    private final ProtoToOpenApiSchemaConverter converter = new ProtoToOpenApiSchemaConverter();

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    private Schema<?> schemaOf(String field) {
        return converter.fieldSchema(COUNTERS.findFieldByName(field), new HashMap<>(), new HashSet<>(), 0);
    }

    @Test
    @DisplayName(
            "GIVEN the default STRING form WHEN int64 and uint64 fields are described SHOULD be strings with int64 / uint64 formats")
    void given_defaults_when_described_should_beStrings() {
        assertThat(schemaOf("total").getType()).isEqualTo("string");
        assertThat(schemaOf("total").getFormat()).isEqualTo("int64");
        assertThat(schemaOf("big").getType()).isEqualTo("string");
        assertThat(schemaOf("big").getFormat()).isEqualTo("uint64");
    }

    @Test
    @DisplayName("GIVEN int64-as NUMBER WHEN int64 and uint64 fields are described SHOULD be integers")
    void given_numbers_when_described_should_beIntegers() {
        ProtoJsonMapping.install(
                new ProtoJsonMapping(true, true, true, true, false, DurationFormat.PROTO, true, false, true));

        assertThat(schemaOf("total").getType()).isEqualTo("integer");
        assertThat(schemaOf("total").getFormat()).isEqualTo("int64");
        assertThat(schemaOf("big").getType()).isEqualTo("integer");
        assertThat(converter
                        .schemaForDescriptor(
                                com.google.protobuf.Int64Value.getDescriptor(), new HashMap<>(), new HashSet<>())
                        .getType())
                .as("the Int64Value wrapper follows the switch too")
                .isEqualTo("integer");
    }

    private static Descriptor buildCounters() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("t/counters.proto")
                .setPackage("t")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Counters")
                        .addField(field("total", 1, FieldDescriptorProto.Type.TYPE_INT64))
                        .addField(field("big", 2, FieldDescriptorProto.Type.TYPE_UINT64)))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {}).findMessageTypeByName("Counters");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
