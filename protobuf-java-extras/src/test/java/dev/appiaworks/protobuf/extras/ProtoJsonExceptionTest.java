package dev.appiaworks.protobuf.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.type.Date;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for ProtoJsonException.
 * Tests verify that ProtoJsonException correctly formats messages and preserves type/value metadata.
 */
class ProtoJsonExceptionTest {

    @Test
    @DisplayName(
            "Given ProtoJsonException with typeName, value and reason, when getMessage, should return formatted message")
    void given_protoJsonExceptionWithAllFields_when_getMessage_should_returnFormattedMessage() {
        ProtoJsonException exception = new ProtoJsonException("acme.Sku", "??", "expected AB-1234", null);

        assertThat(exception.getMessage()).isEqualTo("Invalid acme.Sku value '??': expected AB-1234");
    }

    @Test
    @DisplayName("Given ProtoJsonException with Map value, when value, should return the Map")
    void given_protoJsonExceptionWithMapValue_when_value_should_returnTheMap() {
        Map<String, Object> mapValue = new HashMap<>();
        mapValue.put("year", 2026);
        mapValue.put("month", 2);
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", mapValue, "bad day", null);

        assertThat(exception.value()).isEqualTo(mapValue);
    }

    @Test
    @DisplayName("Given ProtoJsonException with Integer value, when value, should return the Integer")
    void given_protoJsonExceptionWithIntegerValue_when_value_should_returnTheInteger() {
        ProtoJsonException exception =
                new ProtoJsonException("google.protobuf.Int32Value", 999999999, "out of range", null);

        assertThat(exception.value()).isEqualTo(999999999);
    }

    @Test
    @DisplayName("Given ProtoJsonException, when typeName, should return the typeName")
    void given_protoJsonException_when_typeName_should_returnTheTypeName() {
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", "2026-02-30", "bad day", null);

        assertThat(exception.typeName()).isEqualTo("google.type.Date");
    }

    @Test
    @DisplayName("Given ProtoJsonException with cause, when getCause, should return the cause")
    void given_protoJsonExceptionWithCause_when_getCause_should_returnTheCause() {
        Throwable cause = new IllegalArgumentException("root cause");
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", "2026-02-30", "bad day", cause);

        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("Given ProtoJsonException with null cause, when getCause, should return null")
    void given_protoJsonExceptionWithNullCause_when_getCause_should_returnNull() {
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", "2026-02-30", "bad day", null);

        assertThat(exception.getCause()).isNull();
    }

    @Test
    @DisplayName("Given ProtoJsonException is IllegalArgumentException, when instanceof, should be true")
    void given_protoJsonException_when_instanceofIllegalArgumentException_should_beTrue() {
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", "2026-02-30", "bad day", null);

        assertThat(exception).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "Given ProtoWkt with invalid Int32Value, when fromJava, should throw ProtoJsonException with typeName and value")
    void given_protoWktWithInvalidInt32Value_when_fromJava_should_throwProtoJsonException() {
        assertThatThrownBy(() -> ProtoWkt.fromJava("abc", com.google.protobuf.Int32Value.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class)
                .extracting("typeName")
                .isEqualTo("google.protobuf.Int32Value");

        assertThatThrownBy(() -> ProtoWkt.fromJava("abc", com.google.protobuf.Int32Value.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class)
                .extracting("value")
                .isEqualTo("abc");
    }

    @Test
    @DisplayName(
            "Given ProtoWkt with invalid Date, when fromJava, should throw ProtoJsonException with typeName google.type.Date")
    void given_protoWktWithInvalidDate_when_fromJava_should_throwProtoJsonExceptionWithTypeName() {
        assertThatThrownBy(() -> ProtoWkt.fromJava("2026-02-30", Date.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class)
                .extracting("typeName")
                .isEqualTo("google.type.Date");
    }
}
