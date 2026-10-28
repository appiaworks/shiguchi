package dev.appiaworks.protobuf.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.type.LatLng;
import com.google.type.PostalAddress;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape;
import dev.appiaworks.protobuf.extras.serde.JsonDialect;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScalarSchemaTest {

    // ────────────────────────────────────────────────────────────────────────────
    // Construction and withers
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given complete schema with all fields, when constructed, should preserve all values")
    void given_completeSchemaWithAllFields_when_constructed_should_preserveAllValues() {
        ScalarSchema schema = ScalarSchema.string()
                .withFormat("sku")
                .withPattern("[A-Z]{2}-\\d+")
                .withDescription("Stock-keeping unit")
                .withExample("AB-1")
                .withGraphQlName("Sku");

        assertThat(schema.shape()).isEqualTo(JsonShape.STRING);
        assertThat(schema.format()).isEqualTo("sku");
        assertThat(schema.pattern()).isEqualTo("[A-Z]{2}-\\d+");
        assertThat(schema.description()).isEqualTo("Stock-keeping unit");
        assertThat(schema.example()).isEqualTo("AB-1");
        assertThat(schema.graphQlName()).isEqualTo("Sku");
    }

    @Test
    @DisplayName("Given base schema, when withFormat is called, should not mutate the original")
    void given_baseSchema_when_withFormatIsCalled_should_notMutateTheOriginal() {
        ScalarSchema base = ScalarSchema.string();
        base.withFormat("x");

        assertThat(base.format()).isNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Pattern validation
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given non-STRING shape, when constructing with pattern, should throw IllegalArgumentException")
    void given_nonStringShape_when_constructingWithPattern_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ScalarSchema.integer().withPattern("\\d+"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STRING");
    }

    @Test
    @DisplayName("Given invalid regex pattern, when constructing, should throw IllegalArgumentException")
    void given_invalidRegexPattern_when_constructing_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ScalarSchema.string().withPattern("["))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid regular expression");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Example validation
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given examples fitting or not fitting their shapes, when constructing, should validate correctly")
    void given_examplesValidation_when_constructing_should_validateCorrectly() {
        assertThatThrownBy(() -> ScalarSchema.integer().withExample("12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a");

        assertThatThrownBy(() -> ScalarSchema.integer().withExample(1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a");

        assertThatCode(() -> ScalarSchema.number().withExample(1.5)).doesNotThrowAnyException();

        assertThatCode(() -> ScalarSchema.integer().withExample(12L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Given example not matching pattern, when constructing, should throw IllegalArgumentException")
    void given_exampleNotMatchingPattern_when_constructing_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ScalarSchema.string().withPattern("[A-Z]+").withExample("ab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // GraphQL name validation
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given invalid GraphQL names, when constructing, should throw IllegalArgumentException")
    void given_invalidGraphQlNames_when_constructing_should_throwIllegalArgumentException() {
        String[] invalidNames = {"1Sku", "my-sku", "__Sku"};
        for (String name : invalidNames) {
            assertThatThrownBy(() -> ScalarSchema.string().withGraphQlName(name))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Given reserved GraphQL name, when constructing, should throw IllegalArgumentException")
    void given_reservedGraphQlName_when_constructing_should_throwIllegalArgumentException() {
        String[] reservedNames = {"String", "JSON", "LocalDate"};
        for (String name : reservedNames) {
            assertThatThrownBy(() -> ScalarSchema.string().withGraphQlName(name))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("taken");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Field validation (blank values)
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given blank format or description, when constructing, should throw IllegalArgumentException")
    void given_blankFormatOrDescription_when_constructing_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ScalarSchema.string().withFormat("  ")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ScalarSchema.string().withDescription("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // fits() method
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given various shapes and values, when fits is called, should return correct results")
    void given_variousShapesAndValues_when_fits_should_returnCorrectResults() {
        assertThat(ScalarSchema.fits(JsonShape.ANY, Map.of())).isTrue();
        assertThat(ScalarSchema.fits(JsonShape.ANY, List.of())).isTrue();
        assertThat(ScalarSchema.fits(JsonShape.BOOLEAN, "true")).isFalse();
        assertThat(ScalarSchema.fits(JsonShape.INTEGER, BigInteger.TEN)).isTrue();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // ProtoScalarCodec.schema() default and withSchema
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given codec with NUMBER shape, when schema is called, should return schema with that shape")
    void given_codecWithNumberShape_when_schemaIsCalled_should_returnSchemaWithThatShape() {
        ProtoScalarCodec codec = ProtoScalarCodec.of(
                LatLng.getDefaultInstance(), JsonShape.NUMBER, ll -> 1.0, j -> LatLng.getDefaultInstance());

        ScalarSchema schema = codec.schema();

        assertThat(schema).isEqualTo(ScalarSchema.number());
    }

    @Test
    @DisplayName(
            "Given codec with custom schema, when withSchema is called, should preserve fullName, toJson behavior, and report custom schema")
    void given_codecWithCustomSchema_when_withSchemaIsCalled_should_preserveFullNameAndBehavior() {
        ProtoScalarCodec latLngCodec = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(),
                ll -> ll.getLatitude() + "," + ll.getLongitude(),
                t -> LatLng.getDefaultInstance());

        ScalarSchema customSchema = ScalarSchema.integer().withExample(7);
        ProtoScalarCodec wrappedCodec = latLngCodec.withSchema(customSchema);

        LatLng point = LatLng.newBuilder().setLatitude(1).setLongitude(2).build();

        assertThat(wrappedCodec.fullName()).isEqualTo("google.type.LatLng");
        assertThat(wrappedCodec.toJson(point)).isEqualTo("1.0,2.0");
        assertThat(wrappedCodec.schema()).isEqualTo(customSchema);
        assertThat(wrappedCodec.jsonShape()).isEqualTo(JsonShape.INTEGER);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // JsonDialect codec registration
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given two codecs declaring same GraphQL name with different schemas, when JsonDialect.of is called, should throw IllegalArgumentException")
    void given_twoCodecsWithSameGraphQlNameAndDifferentSchemas_when_jsonDialectOf_should_throw() {
        ProtoScalarCodec latLngCodec = ProtoScalarCodec.ofString(
                        LatLng.getDefaultInstance(),
                        ll -> ll.getLatitude() + "," + ll.getLongitude(),
                        t -> LatLng.getDefaultInstance())
                .withSchema(ScalarSchema.string().withGraphQlName("Geo"));

        ProtoScalarCodec postalCodec = ProtoScalarCodec.ofString(
                        PostalAddress.getDefaultInstance(),
                        PostalAddress::getRegionCode,
                        t -> PostalAddress.newBuilder().setRegionCode(t).build())
                .withSchema(ScalarSchema.string().withGraphQlName("Geo").withDescription("other"));

        assertThatThrownBy(() -> JsonDialect.of(ProtoJsonMapping.DEFAULTS, List.of(latLngCodec, postalCodec)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Geo");
    }

    @Test
    @DisplayName(
            "Given two codecs declaring same GraphQL name with identical schema, when JsonDialect.of is called, should not throw")
    void given_twoCodecsWithSameGraphQlNameAndIdenticalSchema_when_jsonDialectOf_should_notThrow() {
        ProtoScalarCodec latLngCodec = ProtoScalarCodec.ofString(
                        LatLng.getDefaultInstance(),
                        ll -> ll.getLatitude() + "," + ll.getLongitude(),
                        t -> LatLng.getDefaultInstance())
                .withSchema(ScalarSchema.string().withGraphQlName("Geo"));

        ProtoScalarCodec postalCodec = ProtoScalarCodec.ofString(
                        PostalAddress.getDefaultInstance(),
                        PostalAddress::getRegionCode,
                        t -> PostalAddress.newBuilder().setRegionCode(t).build())
                .withSchema(ScalarSchema.string().withGraphQlName("Geo"));

        assertThatCode(() -> JsonDialect.of(ProtoJsonMapping.DEFAULTS, List.of(latLngCodec, postalCodec)))
                .doesNotThrowAnyException();
    }
}
