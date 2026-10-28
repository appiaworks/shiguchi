package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import io.swagger.v3.oas.models.media.Schema;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Wrapper and other well-known types are described by the JSON they unwrap to: an
 * {@code Int32Value} is sent as a number, so a generated client must see an integer, not a string.
 */
@DisplayName("ProtoToOpenApiSchemaConverter — wrapper types")
class ProtoToOpenApiWrapperTypesTest {

    private final ProtoToOpenApiSchemaConverter converter = new ProtoToOpenApiSchemaConverter();

    private Schema<?> schemaOf(Descriptor type) {
        return converter.schemaForDescriptor(type, new HashMap<>(), new HashSet<>());
    }

    static List<Arguments> wrappers() {
        return List.of(
                Arguments.of(Int32Value.getDescriptor(), "integer", "int32"),
                Arguments.of(UInt32Value.getDescriptor(), "integer", "int64"),
                Arguments.of(Int64Value.getDescriptor(), "string", "int64"),
                Arguments.of(UInt64Value.getDescriptor(), "string", "uint64"),
                Arguments.of(FloatValue.getDescriptor(), "number", "float"),
                Arguments.of(DoubleValue.getDescriptor(), "number", "double"),
                Arguments.of(BoolValue.getDescriptor(), "boolean", null),
                Arguments.of(StringValue.getDescriptor(), "string", null),
                Arguments.of(BytesValue.getDescriptor(), "string", "byte"),
                Arguments.of(FieldMask.getDescriptor(), "string", null),
                Arguments.of(Empty.getDescriptor(), "object", null),
                Arguments.of(Struct.getDescriptor(), "object", null));
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @MethodSource("wrappers")
    @DisplayName("GIVEN a well-known type WHEN its schema is built SHOULD match the JSON it is sent as")
    void given_wellKnownType_when_built_should_matchItsJson(Descriptor type, String jsonType, String format) {
        Schema<?> schema = schemaOf(type);

        assertThat(schema.getType()).isEqualTo(jsonType);
        assertThat(schema.getFormat()).isEqualTo(format);
    }

    @Test
    @DisplayName(
            "GIVEN google.protobuf.Value WHEN its schema is built SHOULD not constrain the type, as it holds any JSON value")
    void given_value_when_built_should_beUnconstrained() {
        Schema<?> schema = schemaOf(Value.getDescriptor());

        assertThat(schema.getType()).isNull();
        assertThat(schema.get$ref()).isNull();
    }
}
