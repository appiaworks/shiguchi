package dev.appiaworks.protobuf.extras.protoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PluginOptionsTest {

    @Test
    @DisplayName("Given null or empty parameter, when parsed, should have no disabled types and no custom mappings")
    void given_nullOrEmptyParameter_when_parsed_should_haveNoDisabledAndNoCustom() {
        PluginOptions nullOpts = PluginOptions.parse(null);
        assertThat(nullOpts.disabled()).isEmpty();
        assertThat(nullOpts.custom()).isEmpty();
        assertThat(nullOpts.resolve()).isEqualTo(TypeMapping.DEFAULTS);

        PluginOptions emptyOpts = PluginOptions.parse("");
        assertThat(emptyOpts.disabled()).isEmpty();
        assertThat(emptyOpts.custom()).isEmpty();
        assertThat(emptyOpts.resolve()).isEqualTo(TypeMapping.DEFAULTS);

        PluginOptions blankOpts = PluginOptions.parse("   ");
        assertThat(blankOpts.disabled()).isEmpty();
        assertThat(blankOpts.custom()).isEmpty();
        assertThat(blankOpts.resolve()).isEqualTo(TypeMapping.DEFAULTS);
    }

    @Test
    @DisplayName(
            "Given disable option for a type, when parsed, should contain it in disabled set and not in resolved mappings")
    void given_disableOption_when_parsed_should_excludeFromResolved() {
        PluginOptions opts = PluginOptions.parse("disable=google.type.Decimal");

        assertThat(opts.disabled()).contains("google.type.Decimal");
        assertThat(opts.resolve())
                .noneMatch(m -> m.protoType().equals("google.type.Decimal"))
                .anyMatch(m -> m.protoType().equals("google.type.Date"));
    }

    @Test
    @DisplayName("Given a leading dot in the proto type name, when parsed, should be accepted and stripped")
    void given_leadingDotInProtoType_when_parsed_should_accept() {
        PluginOptions opts = PluginOptions.parse("disable=.google.type.Decimal");

        assertThat(opts.disabled()).contains("google.type.Decimal");
        assertThat(opts.resolve()).noneMatch(m -> m.protoType().equals("google.type.Decimal"));
    }

    @Test
    @DisplayName("Given a custom mapping option, when parsed, should extract all parts and add to custom list")
    void given_customMappingOption_when_parsed_should_extractAllParts() {
        PluginOptions opts =
                PluginOptions.parse("map=acme.Sku;com.acme.SkuCode;com.acme.Skus.toCode;com.acme.Skus.toProto");

        assertThat(opts.custom()).hasSize(1);
        TypeMapping mapping = opts.custom().get(0);
        assertThat(mapping.protoType()).isEqualTo("acme.Sku");
        assertThat(mapping.javaType()).isEqualTo("com.acme.SkuCode");
        assertThat(mapping.javaParamType()).isEqualTo("com.acme.SkuCode");
        assertThat(mapping.suffix()).isEqualTo("SkuCode");
        assertThat(mapping.toJava()).isEqualTo("com.acme.Skus.toCode");
        assertThat(mapping.fromJava()).isEqualTo("com.acme.Skus.toProto");
        assertThat(mapping.isEnum()).isFalse();
        assertThat(opts.resolve()).contains(mapping);
    }

    @Test
    @DisplayName("Given multiple options separated by commas with spaces, when parsed, should apply all of them")
    void given_multipleOptionsWithSpaces_when_parsed_should_applyAll() {
        PluginOptions opts = PluginOptions.parse(
                "disable=google.type.Decimal , map=acme.Sku;com.acme.SkuCode;com.acme.Skus.toCode;com.acme.Skus.toProto");

        assertThat(opts.disabled()).contains("google.type.Decimal");
        assertThat(opts.custom()).hasSize(1);
        assertThat(opts.resolve()).noneMatch(m -> m.protoType().equals("google.type.Decimal"));
        assertThat(opts.resolve()).anyMatch(m -> m.protoType().equals("acme.Sku"));
    }

    @Test
    @DisplayName(
            "Given a custom mapping for a type that has defaults, when parsed, should replace all defaults for that type")
    void given_customMappingForDefaultType_when_parsed_should_replaceDefaults() {
        PluginOptions opts = PluginOptions.parse(
                "map=google.protobuf.Timestamp;java.time.ZonedDateTime;com.acme.T.toZoned;com.acme.T.fromZoned");

        var resolved = opts.resolve();
        var timestampMappings = resolved.stream()
                .filter(m -> m.protoType().equals("google.protobuf.Timestamp"))
                .toList();

        assertThat(timestampMappings).hasSize(1);
        assertThat(timestampMappings.get(0).javaType()).isEqualTo("java.time.ZonedDateTime");
    }

    @Test
    @DisplayName("Given a type both mapped and disabled, when resolved, should be absent from the mappings")
    void given_typeBothMappedAndDisabled_when_resolved_should_beAbsent() {
        PluginOptions opts = PluginOptions.parse(
                "map=acme.Sku;com.acme.SkuCode;com.acme.Skus.toCode;com.acme.Skus.toProto , disable=acme.Sku");

        assertThat(opts.resolve()).noneMatch(m -> m.protoType().equals("acme.Sku"));
    }

    @Test
    @DisplayName("Given nonsense option format, when parsed, should throw IllegalArgumentException with the text")
    void given_nonsenseOption_when_parsed_should_throw() {
        assertThatThrownBy(() -> PluginOptions.parse("nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonsense");
    }

    @Test
    @DisplayName(
            "Given unknown option key, when parsed, should throw IllegalArgumentException mentioning known options")
    void given_unknownOptionKey_when_parsed_should_throw() {
        assertThatThrownBy(() -> PluginOptions.parse("frobnicate=x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frobnicate")
                .hasMessageContaining("disable")
                .hasMessageContaining("map");
    }

    @Test
    @DisplayName("Given custom mapping with wrong number of parts, when parsed, should throw IllegalArgumentException")
    void given_wrongNumberOfParts_when_parsed_should_throw() {
        assertThatThrownBy(() -> PluginOptions.parse("map=acme.Sku;com.acme.SkuCode"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("map=");
    }

    @Test
    @DisplayName(
            "Given toJava method as an expression instead of qualified name, when parsed, should throw IllegalArgumentException")
    void given_expressionInMethod_when_parsed_should_throw() {
        assertThatThrownBy(() ->
                        PluginOptions.parse("map=acme.Sku;com.acme.SkuCode;new Foo({proto});com.acme.Skus.toProto"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("map=")
                .hasMessageContaining("expression");
    }

    @Test
    @DisplayName("Given disable option with no proto type name, when parsed, should throw IllegalArgumentException")
    void given_emptyDisableValue_when_parsed_should_throw() {
        assertThatThrownBy(() -> PluginOptions.parse("disable="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disable=");
    }
}
