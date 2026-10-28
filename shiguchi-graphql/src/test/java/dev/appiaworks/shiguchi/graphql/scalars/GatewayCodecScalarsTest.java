package dev.appiaworks.shiguchi.graphql.scalars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.protobuf.extras.ScalarSchema;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.scalars.ExtendedScalars;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GatewayCodecScalars — a GraphQL scalar per codec schema that names one")
class GatewayCodecScalarsTest {

    private static final GraphQLContext CONTEXT = GraphQLContext.getDefault();

    private static Coercing<?, ?> coercing(ScalarSchema schema) {
        return GatewayCodecScalars.scalar(schema).getCoercing();
    }

    @Test
    @DisplayName(
            "GIVEN a STRING scalar with a pattern WHEN a variable matches or not SHOULD pass it through or refuse it")
    void given_patternedString_when_parsingValue_should_checkThePattern() {
        Coercing<?, ?> sku =
                coercing(ScalarSchema.string().withPattern("[A-Z]{2}-\\d+").withGraphQlName("Sku"));

        assertThat(sku.parseValue("AB-1", CONTEXT, Locale.ROOT)).isEqualTo("AB-1");
        assertThatThrownBy(() -> sku.parseValue("ab1", CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseValueException.class)
                .hasMessageContaining("Sku");
        assertThatThrownBy(() -> sku.parseValue(12, CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseValueException.class);
    }

    @Test
    @DisplayName(
            "GIVEN an INTEGER scalar WHEN literals arrive SHOULD take whole numbers of any size and refuse text and fractions")
    void given_integerScalar_when_parsingLiterals_should_takeWholeNumbersOnly() {
        Coercing<?, ?> millis = coercing(ScalarSchema.integer().withGraphQlName("EpochMillis"));

        assertThat(millis.parseLiteral(
                        new IntValue(BigInteger.valueOf(42)), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isEqualTo(42);
        assertThat(millis.parseLiteral(
                        new IntValue(BigInteger.valueOf(1_790_000_000_000L)),
                        CoercedVariables.emptyVariables(),
                        CONTEXT,
                        Locale.ROOT))
                .isEqualTo(1_790_000_000_000L);
        assertThatThrownBy(() -> millis.parseLiteral(
                        new StringValue("42"), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseLiteralException.class);
        assertThatThrownBy(() -> millis.parseLiteral(
                        new FloatValue(new BigDecimal("4.2")), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseLiteralException.class);
    }

    @Test
    @DisplayName("GIVEN a NUMBER scalar WHEN an integer literal arrives SHOULD accept it as a number")
    void given_numberScalar_when_integerLiteral_should_accept() {
        Coercing<?, ?> ratio = coercing(ScalarSchema.number().withGraphQlName("Ratio"));

        assertThat(ratio.parseLiteral(
                        new IntValue(BigInteger.TWO), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isEqualTo(2);
        assertThat(ratio.parseLiteral(
                        new FloatValue(new BigDecimal("0.5")), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isEqualTo(new BigDecimal("0.5"));
    }

    @Test
    @DisplayName(
            "GIVEN a STRING scalar WHEN the codec's output is not text SHOULD fail to serialize rather than send it")
    void given_stringScalar_when_serializingANumber_should_fail() {
        Coercing<?, ?> sku = coercing(ScalarSchema.string().withGraphQlName("Sku"));

        assertThat(sku.serialize("AB-1", CONTEXT, Locale.ROOT)).isEqualTo("AB-1");
        assertThatThrownBy(() -> sku.serialize(12, CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingSerializeException.class);
    }

    @Test
    @DisplayName("GIVEN an ANY scalar WHEN built SHOULD use the JSON scalar's coercing, so objects pass")
    void given_anyScalar_when_built_should_coerceLikeJson() {
        Coercing<?, ?> geo = coercing(ScalarSchema.any().withGraphQlName("Geo").withDescription("A point"));

        assertThat(geo).isSameAs(ExtendedScalars.Json.getCoercing());
        assertThat(GatewayCodecScalars.scalar(
                                ScalarSchema.any().withGraphQlName("Geo").withDescription("A point"))
                        .getDescription())
                .isEqualTo("A point");
        assertThat(geo.serialize(Map.of("lat", 1), CONTEXT, Locale.ROOT)).isEqualTo(Map.of("lat", 1));
    }

    @Test
    @DisplayName("GIVEN a schema with no GraphQL name WHEN a scalar is asked for SHOULD refuse")
    void given_noName_when_scalarBuilt_should_refuse() {
        assertThatThrownBy(() -> GatewayCodecScalars.scalar(ScalarSchema.string()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
