package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.config.JsonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for GatewayJsonSettings mapping function.
 * Tests verify that JsonConfig properties are correctly translated to ProtoJsonMapping settings.
 */
class GatewayJsonSettingsTest {

    @Test
    @DisplayName("Given default JsonConfig, when mapping, should equal ProtoJsonMapping.DEFAULTS")
    void given_defaultJsonConfig_when_mapping_should_equalDefaults() {
        ProtoJsonMapping result = GatewayJsonSettings.mapping(new JsonConfig());

        assertThat(result).isEqualTo(ProtoJsonMapping.DEFAULTS);
    }

    @Test
    @DisplayName("Given JsonConfig with date disabled, when mapping, should set date false and others to defaults")
    void given_jsonConfigWithDateDisabled_when_mapping_should_setDateFalseOnly() {
        JsonConfig config = new JsonConfig();
        config.getScalarTypes().setDate(false);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.date()).isFalse();
        assertThat(result.timeOfDay()).isTrue();
        assertThat(result.dateTime()).isTrue();
        assertThat(result.decimal()).isTrue();
        assertThat(result.money()).isFalse();
    }

    @Test
    @DisplayName("Given JsonConfig with timeOfDay disabled, when mapping, should set timeOfDay false only")
    void given_jsonConfigWithTimeOfDayDisabled_when_mapping_should_setTimeOfDayFalseOnly() {
        JsonConfig config = new JsonConfig();
        config.getScalarTypes().setTimeOfDay(false);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.timeOfDay()).isFalse();
        assertThat(result.date()).isTrue();
        assertThat(result.dateTime()).isTrue();
        assertThat(result.decimal()).isTrue();
    }

    @Test
    @DisplayName("Given JsonConfig with dateTime disabled, when mapping, should set dateTime false only")
    void given_jsonConfigWithDateTimeDisabled_when_mapping_should_setDateTimeFalseOnly() {
        JsonConfig config = new JsonConfig();
        config.getScalarTypes().setDateTime(false);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.dateTime()).isFalse();
        assertThat(result.date()).isTrue();
        assertThat(result.timeOfDay()).isTrue();
        assertThat(result.decimal()).isTrue();
    }

    @Test
    @DisplayName("Given JsonConfig with decimal disabled, when mapping, should set decimal false only")
    void given_jsonConfigWithDecimalDisabled_when_mapping_should_setDecimalFalseOnly() {
        JsonConfig config = new JsonConfig();
        config.getScalarTypes().setDecimal(false);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.decimal()).isFalse();
        assertThat(result.date()).isTrue();
        assertThat(result.timeOfDay()).isTrue();
        assertThat(result.dateTime()).isTrue();
    }

    @Test
    @DisplayName("Given JsonConfig with money enabled, when mapping, should set money true only")
    void given_jsonConfigWithMoneyEnabled_when_mapping_should_setMoneyTrueOnly() {
        JsonConfig config = new JsonConfig();
        config.getScalarTypes().setMoney(true);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.money()).isTrue();
        assertThat(result.date()).isTrue();
        assertThat(result.timeOfDay()).isTrue();
        assertThat(result.dateTime()).isTrue();
        assertThat(result.decimal()).isTrue();
    }

    @Test
    @DisplayName("Given JsonConfig with duration ISO_8601 output, when mapping, should set ISO_8601 format")
    void given_jsonConfigWithDurationIso8601_when_mapping_should_setIso8601Format() {
        JsonConfig config = new JsonConfig();
        config.getDuration().setOutput(JsonConfig.DurationFormat.ISO_8601);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.durationOutput()).isEqualTo(ProtoJsonMapping.DurationFormat.ISO_8601);
    }

    @Test
    @DisplayName("Given JsonConfig with duration PROTO output, when mapping, should set PROTO format")
    void given_jsonConfigWithDurationProto_when_mapping_should_setProtoFormat() {
        JsonConfig config = new JsonConfig();
        config.getDuration().setOutput(JsonConfig.DurationFormat.PROTO);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.durationOutput()).isEqualTo(ProtoJsonMapping.DurationFormat.PROTO);
    }

    @Test
    @DisplayName(
            "Given JsonConfig with acceptIso8601 true and acceptEpochMillis true and int64As NUMBER, when mapping, should set all three flags")
    void given_jsonConfigWithAllInputFlags_when_mapping_should_setAllThreeFlags() {
        JsonConfig config = new JsonConfig();
        config.getDuration().setAcceptIso8601(true);
        config.getTimestamp().setAcceptEpochMillis(true);
        config.setInt64As(JsonConfig.Int64Format.NUMBER);

        ProtoJsonMapping result = GatewayJsonSettings.mapping(config);

        assertThat(result.acceptIso8601Duration()).isTrue();
        assertThat(result.acceptEpochMillis()).isTrue();
        assertThat(result.int64AsNumber()).isTrue();
    }

    // ── protoJson ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given the default JsonConfig, when a ProtoJson is built from it, should print as ProtoJson.defaults() does")
    void given_defaultConfig_when_protoJsonBuilt_should_matchDefaults() {
        com.google.type.Date day = com.google.type.Date.newBuilder()
                .setYear(2026)
                .setMonth(9)
                .setDay(22)
                .build();
        com.google.type.Money price = com.google.type.Money.newBuilder()
                .setCurrencyCode("USD")
                .setUnits(12)
                .build();

        dev.appiaworks.protobuf.extras.ProtoJson json =
                GatewayJsonSettings.protoJson(new JsonConfig(), java.util.List.of());

        assertThat(json.print(day))
                .isEqualTo(dev.appiaworks.protobuf.extras.ProtoJson.defaults().print(day));
        assertThat(json.print(price))
                .isEqualTo(dev.appiaworks.protobuf.extras.ProtoJson.defaults().print(price));
    }

    @Test
    @DisplayName("Given money mapped, ISO durations and omitted defaults, when a ProtoJson is built, should apply each")
    void given_changedSettings_when_protoJsonBuilt_should_applyEach() {
        JsonConfig config = new JsonConfig();
        config.getScalarTypes().setMoney(true);
        config.getDuration().setOutput(JsonConfig.DurationFormat.ISO_8601);
        config.getPrint().setOmitDefaultValues(true);

        dev.appiaworks.protobuf.extras.ProtoJson json = GatewayJsonSettings.protoJson(config, java.util.List.of());

        assertThat(json.print(com.google.type.Money.newBuilder()
                        .setCurrencyCode("USD")
                        .setUnits(12)
                        .build()))
                .isEqualTo("{\"currency\":\"USD\",\"amount\":\"12\"}");
        assertThat(json.print(com.google.rpc.RetryInfo.newBuilder()
                        .setRetryDelay(com.google.protobuf.Duration.newBuilder().setSeconds(5400))
                        .build()))
                .isEqualTo("{\"retryDelay\":\"PT1H30M\"}");
        assertThat(json.print(com.google.type.PostalAddress.newBuilder()
                        .setRegionCode("US")
                        .build()))
                .isEqualTo("{\"regionCode\":\"US\"}");
    }

    @Test
    @DisplayName("Given scalar codecs, when a ProtoJson is built, should use them")
    void given_codecs_when_protoJsonBuilt_should_useThem() {
        dev.appiaworks.protobuf.extras.ProtoScalarCodec latLng =
                dev.appiaworks.protobuf.extras.ProtoScalarCodec.ofString(
                        com.google.type.LatLng.getDefaultInstance(),
                        ll -> ll.getLatitude() + "," + ll.getLongitude(),
                        text -> com.google.type.LatLng.getDefaultInstance());

        dev.appiaworks.protobuf.extras.ProtoJson json =
                GatewayJsonSettings.protoJson(new JsonConfig(), java.util.List.of(latLng));

        assertThat(json.print(com.google.type.LatLng.newBuilder()
                        .setLatitude(1.5)
                        .setLongitude(2.5)
                        .build()))
                .isEqualTo("\"1.5,2.5\"");
    }
}
