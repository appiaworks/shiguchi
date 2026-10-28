package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int32Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A value that cannot become the well-known type it is sent for is rejected with
 * {@code INVALID_ARGUMENT}, naming the type and the value — never silently dropped, which used to
 * send the backend a request without the field.
 */
@DisplayName("ProtoWkt.fromJava — malformed input")
class MalformedWktInputTest {

    static List<Arguments> malformed() {
        return List.of(
                Arguments.of(Timestamp.getDescriptor(), "yesterday"),
                Arguments.of(Timestamp.getDescriptor(), "2026-13-01T00:00:00Z"),
                Arguments.of(Duration.getDescriptor(), "ninety minutes"),
                Arguments.of(Int32Value.getDescriptor(), "abc"),
                Arguments.of(BoolValue.getDescriptor(), "yes"),
                Arguments.of(BytesValue.getDescriptor(), 42),
                Arguments.of(Struct.getDescriptor(), "not an object"),
                Arguments.of(ListValue.getDescriptor(), Map.of("not", "a list")));
    }

    @ParameterizedTest(name = "{0} <- {1}")
    @MethodSource("malformed")
    @DisplayName(
            "GIVEN a value that cannot become the well-known type WHEN read SHOULD throw ProtoJsonException naming type and value")
    void given_malformedValue_when_read_should_throwProtoJsonException(Descriptor type, Object value) {
        assertThatThrownBy(() -> ProtoWkt.fromJava(value, type)).isInstanceOfSatisfying(ProtoJsonException.class, e -> {
            assertThat(e.typeName()).isEqualTo(type.getFullName());
            assertThat(e.getMessage()).contains(type.getFullName()).contains(String.valueOf(value));
        });
    }

    @Test
    @DisplayName("GIVEN JSON null for a Timestamp WHEN read SHOULD mean unset, not an error")
    void given_null_when_read_should_meanUnset() {
        assertThat(ProtoWkt.fromJava(null, Timestamp.getDescriptor())).isNull();
    }

    @Test
    @DisplayName("GIVEN a BoolValue sent as the text true (as a query parameter arrives) WHEN read SHOULD accept it")
    void given_boolValueAsText_when_read_should_accept() {
        DynamicMessage b = ProtoWkt.fromJava("true", BoolValue.getDescriptor());

        assertThat(b.getField(BoolValue.getDescriptor().findFieldByName("value")))
                .isEqualTo(true);
    }
}
