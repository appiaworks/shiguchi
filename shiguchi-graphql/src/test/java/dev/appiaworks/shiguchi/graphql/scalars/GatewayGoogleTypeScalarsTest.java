package dev.appiaworks.shiguchi.graphql.scalars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.GraphQLScalarType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for GatewayGoogleTypeScalars.
 *
 * Tests the Coercing behavior for GraphQL scalar types that represent google.type.* messages:
 * LocalDate (google.type.Date), LocalTime (google.type.TimeOfDay), and Decimal
 * (google.type.Decimal). Each scalar handles ISO-8601 text serialization, value parsing, and
 * literal parsing.
 */
@DisplayName("GatewayGoogleTypeScalars")
class GatewayGoogleTypeScalarsTest {

    private Coercing<String, Object> localDateCoercing;
    private Coercing<String, Object> localTimeCoercing;
    private Coercing<String, String> decimalCoercing;

    @BeforeEach
    void setUp() {
        // Extract LocalDate coercing from scalar registration
        GraphQLScalarType localDateScalar = GatewayGoogleTypeScalars.localDate();
        @SuppressWarnings("unchecked")
        Coercing<String, Object> extracted = (Coercing<String, Object>) localDateScalar.getCoercing();
        this.localDateCoercing = extracted;

        // Extract LocalTime coercing
        GraphQLScalarType localTimeScalar = GatewayGoogleTypeScalars.localTime();
        @SuppressWarnings("unchecked")
        Coercing<String, Object> timeExtracted = (Coercing<String, Object>) localTimeScalar.getCoercing();
        this.localTimeCoercing = timeExtracted;

        // Extract Decimal coercing
        GraphQLScalarType decimalScalar = GatewayGoogleTypeScalars.decimal();
        @SuppressWarnings("unchecked")
        Coercing<String, String> decimalExtracted = (Coercing<String, String>) decimalScalar.getCoercing();
        this.decimalCoercing = decimalExtracted;
    }

    // =========================================================================
    // LocalDate serialize() — text and object passthrough
    // =========================================================================

    @Test
    @DisplayName("Given ISO-8601 date string '2026-09-22', when serializing LocalDate, should return string unchanged")
    void given_isoDateString_when_serializingLocalDate_should_returnStringPassthrough() {
        String input = "2026-09-22";

        Object result = localDateCoercing.serialize(input);

        assertThat(result).isEqualTo("2026-09-22");
    }

    @Test
    @DisplayName("Given a java.time.LocalDate for 2026-09-22, when serializing LocalDate, should return ISO string")
    void given_javaLocalDate_when_serializingLocalDate_should_returnIsoString() {
        LocalDate input = LocalDate.of(2026, 9, 22);

        Object result = localDateCoercing.serialize(input);

        assertThat(result).isEqualTo("2026-09-22");
    }

    @Test
    @DisplayName("Given a Map (proto object form), when serializing LocalDate, should pass through unchanged")
    void given_mapObjectForm_when_serializingLocalDate_should_returnMapPassthrough() {
        Map<String, Object> input = Map.of("year", 2026, "month", 9, "day", 22);

        Object result = localDateCoercing.serialize(input);

        assertThat(result).isEqualTo(input);
    }

    // =========================================================================
    // LocalDate parseValue() — accept multiple date formats
    // =========================================================================

    @Test
    @DisplayName("Given full date string '2026-09-22', when parsing value, should return the text unchanged")
    void given_fullDateString_when_parsingValueLocalDate_should_returnText() {
        String input = "2026-09-22";

        String result = localDateCoercing.parseValue(input);

        assertThat(result).isEqualTo("2026-09-22");
    }

    @Test
    @DisplayName("Given year-month string '2026-09', when parsing value, should return the text")
    void given_yearMonthString_when_parsingValueLocalDate_should_returnText() {
        String input = "2026-09";

        String result = localDateCoercing.parseValue(input);

        assertThat(result).isEqualTo("2026-09");
    }

    @Test
    @DisplayName("Given year-only string '2026', when parsing value, should return the text")
    void given_yearOnlyString_when_parsingValueLocalDate_should_returnText() {
        String input = "2026";

        String result = localDateCoercing.parseValue(input);

        assertThat(result).isEqualTo("2026");
    }

    @Test
    @DisplayName("Given month-day partial string '--09-22', when parsing value, should return the text")
    void given_monthDayString_when_parsingValueLocalDate_should_returnText() {
        String input = "--09-22";

        String result = localDateCoercing.parseValue(input);

        assertThat(result).isEqualTo("--09-22");
    }

    @Test
    @DisplayName("Given malformed date string '22/09/2026', when parsing value, should throw exception")
    void given_malformedDateString_when_parsingValueLocalDate_should_throwException() {
        String input = "22/09/2026";

        assertThatThrownBy(() -> localDateCoercing.parseValue(input))
                .isInstanceOf(CoercingParseValueException.class)
                .hasMessageContaining("Invalid LocalDate value");
    }

    // =========================================================================
    // LocalDate parseLiteral() — StringValue only
    // =========================================================================

    @Test
    @DisplayName("Given StringValue with date '2026-09-22', when parsing literal, should return text")
    void given_stringValueLiteral_when_parsingLiteralLocalDate_should_returnText() {
        StringValue input = new StringValue("2026-09-22");

        String result = localDateCoercing.parseLiteral(input);

        assertThat(result).isEqualTo("2026-09-22");
    }

    @Test
    @DisplayName("Given IntValue literal, when parsing literal, should throw exception")
    void given_intValueLiteral_when_parsingLiteralLocalDate_should_throwException() {
        IntValue input = new IntValue(new java.math.BigInteger("20260922"));

        assertThatThrownBy(() -> localDateCoercing.parseLiteral(input))
                .isInstanceOf(CoercingParseLiteralException.class)
                .hasMessageContaining("Invalid LocalDate literal");
    }

    // =========================================================================
    // LocalTime serialize() — text and LocalTime passthrough
    // =========================================================================

    @Test
    @DisplayName("Given ISO-8601 time string '14:03:11', when serializing LocalTime, should return unchanged")
    void given_isoTimeString_when_serializingLocalTime_should_returnStringPassthrough() {
        String input = "14:03:11";

        Object result = localTimeCoercing.serialize(input);

        assertThat(result).isEqualTo("14:03:11");
    }

    @Test
    @DisplayName("Given java.time.LocalTime 14:03, when serializing, should return ISO string")
    void given_javaLocalTime_when_serializingLocalTime_should_returnIsoString() {
        LocalTime input = LocalTime.of(14, 3, 11, 123_000_000);

        Object result = localTimeCoercing.serialize(input);

        assertThat(result.toString()).startsWith("14:03:11");
    }

    // =========================================================================
    // LocalTime parseValue() — accept HH:mm, HH:mm:ss, and fractions
    // =========================================================================

    @Test
    @DisplayName("Given time string '14:03', when parsing value, should return text")
    void given_shortTimeString_when_parsingValueLocalTime_should_returnText() {
        String input = "14:03";

        String result = localTimeCoercing.parseValue(input);

        assertThat(result).isEqualTo("14:03");
    }

    @Test
    @DisplayName("Given time string '14:03:11', when parsing value, should return text")
    void given_fullTimeString_when_parsingValueLocalTime_should_returnText() {
        String input = "14:03:11";

        String result = localTimeCoercing.parseValue(input);

        assertThat(result).isEqualTo("14:03:11");
    }

    @Test
    @DisplayName("Given time with fraction '14:03:11.123', when parsing value, should return text")
    void given_timeWithFraction_when_parsingValueLocalTime_should_returnText() {
        String input = "14:03:11.123";

        String result = localTimeCoercing.parseValue(input);

        assertThat(result).isEqualTo("14:03:11.123");
    }

    @Test
    @DisplayName("Given end-of-day time '24:00:00', when parsing value, should return text")
    void given_endOfDayTime_when_parsingValueLocalTime_should_returnText() {
        String input = "24:00:00";

        String result = localTimeCoercing.parseValue(input);

        assertThat(result).isEqualTo("24:00:00");
    }

    @Test
    @DisplayName("Given invalid time string '2pm', when parsing value, should throw exception")
    void given_textualTimeString_when_parsingValueLocalTime_should_throwException() {
        String input = "2pm";

        assertThatThrownBy(() -> localTimeCoercing.parseValue(input))
                .isInstanceOf(CoercingParseValueException.class)
                .hasMessageContaining("Invalid LocalTime value");
    }

    // =========================================================================
    // LocalTime parseLiteral() — StringValue only
    // =========================================================================

    @Test
    @DisplayName("Given StringValue with time '14:03:11', when parsing literal, should return text")
    void given_stringValueTime_when_parsingLiteralLocalTime_should_returnText() {
        StringValue input = new StringValue("14:03:11");

        String result = localTimeCoercing.parseLiteral(input);

        assertThat(result).isEqualTo("14:03:11");
    }

    @Test
    @DisplayName("Given IntValue literal, when parsing literal LocalTime, should throw exception")
    void given_intValueLiteral_when_parsingLiteralLocalTime_should_throwException() {
        IntValue input = new IntValue(new java.math.BigInteger("140311"));

        assertThatThrownBy(() -> localTimeCoercing.parseLiteral(input))
                .isInstanceOf(CoercingParseLiteralException.class)
                .hasMessageContaining("Invalid LocalTime literal");
    }

    // =========================================================================
    // Decimal serialize() — BigDecimal, Double, Number to string
    // =========================================================================

    @Test
    @DisplayName("Given BigDecimal 12.340, when serializing Decimal, should return plain string")
    void given_bigDecimal_when_serializingDecimal_should_returnPlainString() {
        BigDecimal input = new BigDecimal("12.340");

        String result = decimalCoercing.serialize(input);

        assertThat(result).isEqualTo("12.340");
    }

    @Test
    @DisplayName("Given string '12.34', when serializing Decimal, should return unchanged")
    void given_decimalString_when_serializingDecimal_should_returnStringPassthrough() {
        String input = "12.34";

        String result = decimalCoercing.serialize(input);

        assertThat(result).isEqualTo("12.34");
    }

    @Test
    @DisplayName("Given Double 12.5, when serializing Decimal, should convert to string")
    void given_double_when_serializingDecimal_should_returnString() {
        Double input = 12.5;

        String result = decimalCoercing.serialize(input);

        assertThat(result).contains("12.5");
    }

    // =========================================================================
    // Decimal parseValue() — string, number, or BigDecimal input
    // =========================================================================

    @Test
    @DisplayName("Given string '12.34', when parsing value Decimal, should return text")
    void given_decimalStringValue_when_parsingValueDecimal_should_returnText() {
        String input = "12.34";

        String result = decimalCoercing.parseValue(input);

        assertThat(result).isEqualTo("12.34");
    }

    @Test
    @DisplayName("Given Double 12.5, when parsing value Decimal, should return string")
    void given_doubleValue_when_parsingValueDecimal_should_returnString() {
        Double input = 12.5;

        String result = decimalCoercing.parseValue(input);

        assertThat(result).contains("12.5");
    }

    @Test
    @DisplayName("Given Integer 100, when parsing value Decimal, should return string")
    void given_integerValue_when_parsingValueDecimal_should_returnString() {
        Integer input = 100;

        String result = decimalCoercing.parseValue(input);

        assertThat(result).isEqualTo("100");
    }

    @Test
    @DisplayName("Given BigDecimal, when parsing value Decimal, should return plain string")
    void given_bigDecimalValue_when_parsingValueDecimal_should_returnPlainString() {
        BigDecimal input = new BigDecimal("99.99");

        String result = decimalCoercing.parseValue(input);

        assertThat(result).isEqualTo("99.99");
    }

    @Test
    @DisplayName("Given invalid string 'abc', when parsing value Decimal, should throw exception")
    void given_nonDecimalString_when_parsingValueDecimal_should_throwException() {
        String input = "abc";

        assertThatThrownBy(() -> decimalCoercing.parseValue(input))
                .isInstanceOf(CoercingParseValueException.class)
                .hasMessageContaining("Invalid Decimal value");
    }

    // =========================================================================
    // Decimal parseLiteral() — StringValue, IntValue, FloatValue
    // =========================================================================

    @Test
    @DisplayName("Given StringValue '12.34', when parsing literal Decimal, should return text")
    void given_stringValueDecimal_when_parsingLiteralDecimal_should_returnText() {
        StringValue input = new StringValue("12.34");

        String result = decimalCoercing.parseLiteral(input);

        assertThat(result).isEqualTo("12.34");
    }

    @Test
    @DisplayName("Given IntValue 100, when parsing literal Decimal, should return string")
    void given_intValueDecimal_when_parsingLiteralDecimal_should_returnString() {
        IntValue input = new IntValue(new java.math.BigInteger("100"));

        String result = decimalCoercing.parseLiteral(input);

        assertThat(result).isEqualTo("100");
    }

    @Test
    @DisplayName("Given FloatValue 12.5, when parsing literal Decimal, should return string")
    void given_floatValueDecimal_when_parsingLiteralDecimal_should_returnString() {
        FloatValue input = new FloatValue(new BigDecimal("12.5"));

        String result = decimalCoercing.parseLiteral(input);

        assertThat(result).isEqualTo("12.5");
    }

    @Test
    @DisplayName("Given invalid StringValue 'not-a-number', when parsing literal, should throw exception")
    void given_invalidStringValueDecimal_when_parsingLiteralDecimal_should_throwException() {
        StringValue input = new StringValue("not-a-number");

        assertThatThrownBy(() -> decimalCoercing.parseLiteral(input))
                .isInstanceOf(CoercingParseLiteralException.class)
                .hasMessageContaining("Invalid Decimal literal");
    }
}
