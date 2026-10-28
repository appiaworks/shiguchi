package dev.appiaworks.shiguchi.graphql.scalars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.RuntimeWiring;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for GatewayDateTimeScalar.
 *
 * Tests the Coercing behavior for GraphQL DateTime scalar type, which handles RFC 3339
 * date-time strings and proto Timestamp maps ({seconds, nanos}).
 *
 * The Coercing is extracted from the anonymous inner class by building the runtime wiring
 * and retrieving the registered GraphQLScalarType.
 */
@DisplayName("GatewayDateTimeScalar")
class GatewayDateTimeScalarTest {

    private Coercing<OffsetDateTime, String> coercing;

    @BeforeEach
    void setUp() {
        // Extract the Coercing from the scalar registration
        GatewayDateTimeScalar scalar = new GatewayDateTimeScalar();
        RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring();
        scalar.addScalar(builder);
        RuntimeWiring wiring = builder.build();

        GraphQLScalarType dateTimeScalar = wiring.getScalars().get("DateTime");
        assertThat(dateTimeScalar).isNotNull();

        @SuppressWarnings("unchecked")
        Coercing<OffsetDateTime, String> extracted = (Coercing<OffsetDateTime, String>) dateTimeScalar.getCoercing();
        this.coercing = extracted;
    }

    // =========================================================================
    // serialize() — converts OffsetDateTime/Map/String to RFC 3339 String
    // =========================================================================

    @Test
    @DisplayName("Given a proto Timestamp map with seconds and nanos, when serializing, should return RFC 3339 string")
    void given_protoTimestampMapWithSecondsAndNanos_when_serializing_should_returnRfc3339String() {
        // GIVEN
        Map<String, Object> input = Map.of("seconds", 1700000000L, "nanos", 0);

        // WHEN
        String result = coercing.serialize(input);

        // THEN
        assertThat(result).isEqualTo("2023-11-14T22:13:20Z");
    }

    @Test
    @DisplayName("Given an OffsetDateTime instance, when serializing, should return RFC 3339 string")
    void given_offsetDateTimeInstance_when_serializing_should_returnRfc3339String() {
        // GIVEN
        OffsetDateTime input = OffsetDateTime.of(2023, 11, 14, 22, 13, 20, 0, ZoneOffset.UTC);

        // WHEN
        String result = coercing.serialize(input);

        // THEN
        assertThat(result).isEqualTo("2023-11-14T22:13:20Z");
    }

    @Test
    @DisplayName("Given an RFC 3339 string, when serializing, should return the string unchanged")
    void given_rfc3339String_when_serializing_should_returnStringPassthrough() {
        // GIVEN
        String input = "2023-11-14T22:13:20Z";

        // WHEN
        String result = coercing.serialize(input);

        // THEN
        assertThat(result).isEqualTo("2023-11-14T22:13:20Z");
    }

    @Test
    @DisplayName("Given an Integer input, when serializing, should throw CoercingSerializeException")
    void given_integerInput_when_serializing_should_throwCoercingSerializeException() {
        // GIVEN
        Integer input = 42;

        // WHEN & THEN
        assertThatThrownBy(() -> coercing.serialize(input))
                .isInstanceOf(CoercingSerializeException.class)
                .hasMessageContaining("Expected something we can convert to 'java.time.OffsetDateTime'")
                .hasMessageContaining("Integer");
    }

    @Test
    @DisplayName("Given a proto Timestamp map with no 'seconds' key, when serializing, should use epoch 0 with nanos")
    void given_protoTimestampMapWithoutSeconds_when_serializing_should_useEpoch0WithNanos() {
        // GIVEN: Map with only nanos, no seconds key (500 nanoseconds)
        Map<String, Object> input = Map.of("nanos", 500);

        // WHEN
        String result = coercing.serialize(input);

        // THEN: Should produce epoch 0 (1970-01-01T00:00:00) with .0000005 seconds (500 nanos)
        assertThat(result).isEqualTo("1970-01-01T00:00:00.0000005Z");
    }

    // =========================================================================
    // parseValue() — converts a value to OffsetDateTime
    // =========================================================================

    @Test
    @DisplayName("Given a valid RFC 3339 string, when parsing value, should return OffsetDateTime")
    void given_validRfc3339String_when_parsingValue_should_returnOffsetDateTime() {
        // GIVEN
        String input = "2023-11-14T22:13:20+00:00";

        // WHEN
        OffsetDateTime result = coercing.parseValue(input);

        // THEN
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isEqualTo(2023);
        assertThat(result.getMonthValue()).isEqualTo(11);
        assertThat(result.getDayOfMonth()).isEqualTo(14);
    }

    @Test
    @DisplayName("Given an invalid date string, when parsing value, should throw CoercingParseValueException")
    void given_invalidDateString_when_parsingValue_should_throwCoercingParseValueException() {
        // GIVEN
        String input = "not-a-date";

        // WHEN & THEN
        assertThatThrownBy(() -> coercing.parseValue(input))
                .isInstanceOf(CoercingParseValueException.class)
                .hasMessageContaining("Invalid DateTime value");
    }

    // =========================================================================
    // parseLiteral() — converts a GraphQL StringValue literal to OffsetDateTime
    // =========================================================================

    @Test
    @DisplayName("Given a StringValue with RFC 3339 content, when parsing literal, should return OffsetDateTime")
    void given_stringValueWithRfc3339Content_when_parsingLiteral_should_returnOffsetDateTime() {
        // GIVEN
        StringValue input = new StringValue("2023-11-14T22:13:20+00:00");

        // WHEN
        OffsetDateTime result = coercing.parseLiteral(input);

        // THEN
        assertThat(result).isNotNull();
        assertThat(result.getYear()).isEqualTo(2023);
        assertThat(result.getMonthValue()).isEqualTo(11);
        assertThat(result.getDayOfMonth()).isEqualTo(14);
    }

    @Test
    @DisplayName("Given a non-StringValue input, when parsing literal, should throw CoercingParseLiteralException")
    void given_nonStringValueInput_when_parsingLiteral_should_throwCoercingParseLiteralException() {
        // GIVEN: An Integer value (not StringValue)
        Integer input = 5;

        // WHEN & THEN
        assertThatThrownBy(() -> coercing.parseLiteral(input))
                .isInstanceOf(CoercingParseLiteralException.class)
                .hasMessageContaining("Expected StringValue for DateTime")
                .hasMessageContaining("got: 5");
    }
}
