package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/** The ECMAScript {@code Number::toString} layout, driven by {@code serde/ecmascript-numbers.csv}. */
class JsonNumbersTest {

    private static final JsonFactory JSON = new JsonFactory();

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvFileSource(resources = "/serde/ecmascript-numbers.csv", numLinesToSkip = 1)
    @DisplayName("GIVEN a number WHEN laid out in ECMAScript form SHOULD match the JavaScript text")
    void given_number_when_ecmascript_should_matchJavaScriptText(String kind, String input, String expected) {
        String actual = switch (kind) {
            case "double" -> JsonNumbers.ecmascript(Double.parseDouble(input));
            case "float" -> JsonNumbers.ecmascript(Float.parseFloat(input));
            case "text" -> JsonNumbers.ecmascriptFromText(input);
            default -> throw new IllegalArgumentException(kind);
        };

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("GIVEN a double WHEN written in JAVA format SHOULD keep the Double.toString text")
    void given_double_when_writtenInJavaFormat_should_keepJavaText() throws Exception {
        assertThat(write(1.0d, JsonNumberFormat.JAVA)).isEqualTo("1.0");
        assertThat(write(1.0E20d, JsonNumberFormat.JAVA)).isEqualTo("1.0E20");
    }

    @Test
    @DisplayName("GIVEN a double WHEN written in ECMASCRIPT format SHOULD write the ECMAScript number")
    void given_double_when_writtenInEcmascriptFormat_should_writeEcmascriptNumber() throws Exception {
        assertThat(write(1.0d, JsonNumberFormat.ECMASCRIPT)).isEqualTo("1");
        assertThat(write(Double.MIN_VALUE, JsonNumberFormat.ECMASCRIPT)).isEqualTo("5e-324");
    }

    @Test
    @DisplayName("GIVEN a float WHEN written in each format SHOULD follow the format")
    void given_float_when_writtenInEachFormat_should_followFormat() throws Exception {
        StringWriter java = new StringWriter();
        StringWriter es = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(java)) {
            JsonNumbers.writeFloat(g, 0.1f, JsonNumberFormat.JAVA);
        }
        try (JsonGenerator g = JSON.createGenerator(es)) {
            JsonNumbers.writeFloat(g, 1.0E10f, JsonNumberFormat.ECMASCRIPT);
        }

        assertThat(java.toString()).isEqualTo("0.1");
        assertThat(es.toString()).isEqualTo("10000000000");
    }

    @Test
    @DisplayName("GIVEN a non-finite double WHEN laid out SHOULD return its Java text")
    void given_nonFiniteDouble_when_laidOut_should_returnJavaText() {
        assertThat(JsonNumbers.ecmascript(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        assertThat(JsonNumbers.ecmascript(Double.NaN)).isEqualTo("NaN");
    }

    private static String write(double v, JsonNumberFormat format) throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            JsonNumbers.writeDouble(g, v, format);
        }
        return out.toString();
    }
}
