package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DoubleValue;
import com.google.protobuf.Value;
import dev.appiaworks.protobuf.extras.serde.JsonNumberFormat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Push payloads are printed once at publish time, in the number format of the service they came from. */
class PushNumberFormatTest {

    @Test
    @DisplayName("GIVEN a typed message WHEN printed for ECMASCRIPT SHOULD store the number without a point")
    void given_typedMessage_when_printedForEcmascript_should_storeIntegralDouble() throws Exception {
        DoubleValue message = DoubleValue.newBuilder().setValue(1.0).build();

        String json = new String(
                SourcePayloads.typed(message, null, JsonNumberFormat.ECMASCRIPT).json(), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("1");
    }

    @Test
    @DisplayName("GIVEN a typed message WHEN printed for JAVA SHOULD keep the Java text")
    void given_typedMessage_when_printedForJava_should_keepJavaText() throws Exception {
        DoubleValue message = DoubleValue.newBuilder().setValue(1.0).build();

        String json = new String(
                SourcePayloads.typed(message, null, JsonNumberFormat.JAVA).json(), StandardCharsets.UTF_8);

        assertThat(json).isEqualTo("1.0");
    }

    @Test
    @DisplayName("GIVEN a Value number WHEN written for ECMASCRIPT SHOULD use the ECMAScript layout")
    void given_valueNumber_when_writtenForEcmascript_should_useEcmascriptLayout() {
        Value value = Value.newBuilder().setNumberValue(1.0E21).build();

        assertThat(new String(PushJson.toJson(value, JsonNumberFormat.ECMASCRIPT), StandardCharsets.UTF_8))
                .isEqualTo("1e+21");
        assertThat(new String(PushJson.toJson(value, JsonNumberFormat.JAVA), StandardCharsets.UTF_8))
                .isEqualTo("1.0E21");
    }
}
