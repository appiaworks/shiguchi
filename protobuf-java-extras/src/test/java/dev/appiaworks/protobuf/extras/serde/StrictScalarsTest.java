package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Field;
import com.google.protobuf.Int64Value;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.type.PostalAddress;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StrictScalarsTest {

    // ── int32 tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given an int32 field, when reading an Integer, should return it unchanged")
    void given_int32Field_when_readInteger_should_returnIt() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("revision");
        assertThat(StrictScalars.fieldValue(fd, 7)).isEqualTo(7);
    }

    @Test
    @DisplayName("Given an int32 field, when reading a numeric string, should parse and return it")
    void given_int32Field_when_readNumericString_should_parseAndReturn() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("revision");
        assertThat(StrictScalars.fieldValue(fd, "12")).isEqualTo(12);
    }

    @Test
    @DisplayName("Given an int32 field, when reading a Double in scientific notation, should return as int")
    void given_int32Field_when_readDoubleScientific_should_returnAsInt() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("revision");
        assertThat(StrictScalars.fieldValue(fd, 100.0d)).isEqualTo(100);
    }

    @Test
    @DisplayName("Given an int32 field, when reading a whole BigDecimal, should return as int")
    void given_int32Field_when_readWholeBigDecimal_should_returnAsInt() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("revision");
        assertThat(StrictScalars.fieldValue(fd, new BigDecimal("100.0"))).isEqualTo(100);
    }

    @Test
    @DisplayName(
            "Given an int32 field, when reading invalid values, should throw ProtoJsonException with correct message and typeName")
    void given_int32Field_when_readInvalidValues_should_throwWithCorrectException() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("revision");

        // Fractional double
        ProtoJsonException frac =
                catchThrowableOfType(() -> StrictScalars.fieldValue(fd, 1.5d), ProtoJsonException.class);
        assertThat(frac).hasMessageContaining("takes a whole number from -2147483648 to 2147483647");
        assertThat(frac.typeName()).isEqualTo("int32");

        // Non-numeric string
        ProtoJsonException nonNumeric =
                catchThrowableOfType(() -> StrictScalars.fieldValue(fd, "abc"), ProtoJsonException.class);
        assertThat(nonNumeric).hasMessageContaining("takes a whole number from -2147483648 to 2147483647");
        assertThat(nonNumeric.value()).isEqualTo("abc");

        // Overflow
        ProtoJsonException overflow =
                catchThrowableOfType(() -> StrictScalars.fieldValue(fd, 2147483648L), ProtoJsonException.class);
        assertThat(overflow).hasMessageContaining("takes a whole number from -2147483648 to 2147483647");

        // Boolean
        ProtoJsonException bool =
                catchThrowableOfType(() -> StrictScalars.fieldValue(fd, Boolean.TRUE), ProtoJsonException.class);
        assertThat(bool).hasMessageContaining("takes a whole number from -2147483648 to 2147483647");
    }

    // ── uint32 tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given a uint32 field, when reading max value, should return bit pattern as negative int")
    void given_uint32Field_when_readMaxValue_should_returnBitPatternAsNegative() {
        FieldDescriptor fd = UInt32Value.getDescriptor().findFieldByName("value");
        assertThat(StrictScalars.fieldValue(fd, 4294967295L)).isEqualTo(-1);
    }

    @Test
    @DisplayName("Given a uint32 field, when reading negative value, should throw ProtoJsonException")
    void given_uint32Field_when_readNegativeValue_should_throw() {
        FieldDescriptor fd = UInt32Value.getDescriptor().findFieldByName("value");
        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, -1))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a whole number from 0 to 4294967295");
    }

    // ── uint64 tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given a uint64 field, when reading max value as string, should return bit pattern as -1L")
    void given_uint64Field_when_readMaxValueAsString_should_returnBitPatternAsNegativeOne() {
        FieldDescriptor fd = UInt64Value.getDescriptor().findFieldByName("value");
        assertThat(StrictScalars.fieldValue(fd, "18446744073709551615")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Given a uint64 field, when reading negative string, should throw ProtoJsonException")
    void given_uint64Field_when_readNegativeString_should_throw() {
        FieldDescriptor fd = UInt64Value.getDescriptor().findFieldByName("value");
        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, "-1"))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a whole number from 0 to");
    }

    @Test
    @DisplayName("Given a uint64 field, when reading overflow value, should throw ProtoJsonException")
    void given_uint64Field_when_readOverflowValue_should_throw() {
        FieldDescriptor fd = UInt64Value.getDescriptor().findFieldByName("value");
        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, "18446744073709551616"))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a whole number from 0 to");
    }

    // ── int64 tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given an int64 field, when reading min value as string, should return Long.MIN_VALUE")
    void given_int64Field_when_readMinValueAsString_should_returnLongMinValue() {
        FieldDescriptor fd = Int64Value.getDescriptor().findFieldByName("value");
        assertThat(StrictScalars.fieldValue(fd, "-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("Given an int64 field, when reading overflow value, should throw ProtoJsonException")
    void given_int64Field_when_readOverflowValue_should_throw() {
        FieldDescriptor fd = Int64Value.getDescriptor().findFieldByName("value");
        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, "9223372036854775808"))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a whole number from");
    }

    // ── float tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given a float field, when reading special values, should return them correctly")
    void given_floatField_when_readSpecialValues_should_returnCorrectly() {
        FieldDescriptor fd = com.google.protobuf.FloatValue.getDescriptor().findFieldByName("value");

        assertThat((Float) StrictScalars.fieldValue(fd, "NaN")).isNaN();
        assertThat((Float) StrictScalars.fieldValue(fd, "-Infinity")).isEqualTo(Float.NEGATIVE_INFINITY);
    }

    @Test
    @DisplayName("Given a float field, when reading a double value, should convert it")
    void given_floatField_when_readDoubleValue_should_convertIt() {
        FieldDescriptor fd = com.google.protobuf.FloatValue.getDescriptor().findFieldByName("value");
        assertThat((Float) StrictScalars.fieldValue(fd, 1.5d)).isEqualTo(1.5f);
    }

    @Test
    @DisplayName("Given a float field, when reading an out-of-range double, should throw ProtoJsonException")
    void given_floatField_when_readOutOfRangeDouble_should_throw() {
        FieldDescriptor fd = com.google.protobuf.FloatValue.getDescriptor().findFieldByName("value");
        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, 1e39d))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("out of range for a float");
    }

    // ── double tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given a double field, when reading a numeric string, should parse and return it")
    void given_doubleField_when_readNumericString_should_parseAndReturn() {
        FieldDescriptor fd = com.google.protobuf.DoubleValue.getDescriptor().findFieldByName("value");
        assertThat((Double) StrictScalars.fieldValue(fd, "2.25")).isEqualTo(2.25d);
    }

    @Test
    @DisplayName("Given a double field, when reading a non-numeric string, should throw ProtoJsonException")
    void given_doubleField_when_readNonNumericString_should_throw() {
        FieldDescriptor fd = com.google.protobuf.DoubleValue.getDescriptor().findFieldByName("value");
        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, "one"))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a number");
    }

    // ── boolean tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given a bool field, when reading Boolean.TRUE or string 'false', should return correctly")
    void given_boolField_when_readBooleanOrString_should_returnCorrectly() {
        FieldDescriptor fd = com.google.protobuf.BoolValue.getDescriptor().findFieldByName("value");

        assertThat(StrictScalars.fieldValue(fd, Boolean.TRUE)).isEqualTo(Boolean.TRUE);
        assertThat(StrictScalars.fieldValue(fd, "false")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("Given a bool field, when reading integer or invalid string, should throw ProtoJsonException")
    void given_boolField_when_readInvalidValues_should_throw() {
        FieldDescriptor fd = com.google.protobuf.BoolValue.getDescriptor().findFieldByName("value");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, 1))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes true or false");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, "yes"))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes true or false");
    }

    // ── string tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given a string field, when reading a string, should return it unchanged")
    void given_stringField_when_readString_should_returnUnchanged() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("region_code");
        assertThat(StrictScalars.fieldValue(fd, "x")).isEqualTo("x");
    }

    @Test
    @DisplayName("Given a string field, when reading non-string values, should throw ProtoJsonException")
    void given_stringField_when_readNonStringValues_should_throw() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("region_code");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, 12))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a JSON string");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, Map.of()))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a JSON string");
    }

    // ── bytes tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given a bytes field, when reading valid base64 string, should decode and return ByteString")
    void given_bytesField_when_readValidBase64_should_decodeAndReturn() {
        FieldDescriptor fd = com.google.protobuf.BytesValue.getDescriptor().findFieldByName("value");

        ByteString result = (ByteString) StrictScalars.fieldValue(fd, "aGk=");
        assertThat(result).isEqualTo(ByteString.copyFromUtf8("hi"));
    }

    @Test
    @DisplayName("Given a bytes field, when reading URL-safe base64, should decode correctly")
    void given_bytesField_when_readUrlSafeBase64_should_decodeCorrectly() {
        FieldDescriptor fd = com.google.protobuf.BytesValue.getDescriptor().findFieldByName("value");

        ByteString result = (ByteString) StrictScalars.fieldValue(fd, "-_8=");
        assertThat(result).isEqualTo(ByteString.copyFrom(new byte[] {(byte) 0xFB, (byte) 0xFF}));
    }

    @Test
    @DisplayName("Given a bytes field, when reading invalid base64, should throw ProtoJsonException")
    void given_bytesField_when_readInvalidBase64_should_throw() {
        FieldDescriptor fd = com.google.protobuf.BytesValue.getDescriptor().findFieldByName("value");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, "not base64!"))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes base64 text");
    }

    // ── enum tests ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given an enum field, when reading enum name or valid number, should return EnumValueDescriptor or null")
    void given_enumField_when_readValidEnumNameOrNumber_should_returnDescriptorOrNull() {
        FieldDescriptor fd = Field.getDescriptor().findFieldByName("kind");

        var typeStringDescriptor = StrictScalars.fieldValue(fd, "TYPE_STRING");
        assertThat(typeStringDescriptor).isNotNull();
        assertThat(((com.google.protobuf.Descriptors.EnumValueDescriptor) typeStringDescriptor).getNumber())
                .isEqualTo(9);

        var numberDescriptor = StrictScalars.fieldValue(fd, 1);
        assertThat(numberDescriptor).isNotNull();
        assertThat(((com.google.protobuf.Descriptors.EnumValueDescriptor) numberDescriptor).getNumber())
                .isEqualTo(1);

        // Unknown enum value returns null (as per behavior for unknown enum values)
        assertThat(StrictScalars.fieldValue(fd, "NO_SUCH_KIND")).isNull();
        assertThat(StrictScalars.fieldValue(fd, 999)).isNull();
    }

    @Test
    @DisplayName("Given an enum field, when reading invalid types, should throw ProtoJsonException")
    void given_enumField_when_readInvalidTypes_should_throw() {
        FieldDescriptor fd = Field.getDescriptor().findFieldByName("kind");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, Boolean.TRUE))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a value name or number of google.protobuf.Field.Kind");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, 1.5))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("takes a value name or number of google.protobuf.Field.Kind");
    }

    // ── null handling ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given any scalar field, when reading null, should throw ProtoJsonException")
    void given_scalarField_when_readNull_should_throw() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("revision");

        assertThatThrownBy(() -> StrictScalars.fieldValue(fd, null))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("does not take null here");
    }

    // ── exception details tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("Given an int32 field rejecting a string, should provide correct exception typeName and value")
    void given_int32Field_when_rejectingString_should_provideCorrectExceptionDetails() {
        FieldDescriptor fd = PostalAddress.getDescriptor().findFieldByName("revision");

        ProtoJsonException ex =
                catchThrowableOfType(() -> StrictScalars.fieldValue(fd, "abc"), ProtoJsonException.class);

        assertThat(ex.typeName()).isEqualTo("int32");
        assertThat(ex.value()).isEqualTo("abc");
    }
}
