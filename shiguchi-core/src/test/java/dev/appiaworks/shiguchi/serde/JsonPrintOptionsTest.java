package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.shiguchi.config.JsonConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.config.ServiceDefinition.JsonPrintOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link JsonPrintOptions}: configuration, merging, and field name mapping.
 */
class JsonPrintOptionsTest {

    @AfterEach
    void restoreDefaults() {
        JsonPrintOptions.install(JsonPrintOptions.DEFAULT);
    }

    // ── isDefault() ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN DEFAULT options WHEN checking isDefault SHOULD return true")
    void given_DEFAULT_when_checkingIsDefault_should_returnTrue() {
        assertThat(JsonPrintOptions.DEFAULT.isDefault()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "true,false,false",
        "false,true,false",
        "false,false,true",
        "true,true,false",
        "true,false,true",
        "false,true,true",
        "true,true,true",
    })
    @DisplayName("GIVEN non-DEFAULT options WHEN checking isDefault SHOULD return false")
    void given_nonDefaultOptions_when_checkingIsDefault_should_returnFalse(
            boolean preserve, boolean enumsAsInts, boolean omitDefaults) {
        JsonPrintOptions options = new JsonPrintOptions(preserve, enumsAsInts, omitDefaults);
        assertThat(options.isDefault()).isFalse();
    }

    // ── from(JsonConfig.PrintOptions) ────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN null config WHEN calling from() SHOULD return DEFAULT")
    void given_nullConfig_when_from_should_returnDEFAULT() {
        assertThat(GatewayJsonSettings.printOptions(null)).isEqualTo(JsonPrintOptions.DEFAULT);
    }

    @Test
    @DisplayName("GIVEN default-valued config WHEN calling from() SHOULD return DEFAULT")
    void given_defaultValuedConfig_when_from_should_returnDEFAULT() {
        JsonConfig.PrintOptions config = new JsonConfig.PrintOptions();
        assertThat(GatewayJsonSettings.printOptions(config)).isEqualTo(JsonPrintOptions.DEFAULT);
    }

    @Test
    @DisplayName(
            "GIVEN config with preserveProtoFieldNames=true WHEN calling from() SHOULD set preserveProtoFieldNames")
    void given_preserveProtoFieldNamesConfig_when_from_should_setFlag() {
        JsonConfig.PrintOptions config = new JsonConfig.PrintOptions();
        config.setPreserveProtoFieldNames(true);

        JsonPrintOptions options = GatewayJsonSettings.printOptions(config);

        assertThat(options.preserveProtoFieldNames()).isTrue();
        assertThat(options.enumsAsInts()).isFalse();
        assertThat(options.omitDefaultValues()).isFalse();
    }

    @Test
    @DisplayName("GIVEN config with enumsAsInts=true WHEN calling from() SHOULD set flag")
    void given_enumsAsIntsConfig_when_from_should_setFlag() {
        JsonConfig.PrintOptions config = new JsonConfig.PrintOptions();
        config.setEnumsAsInts(true);

        JsonPrintOptions options = GatewayJsonSettings.printOptions(config);

        assertThat(options.preserveProtoFieldNames()).isFalse();
        assertThat(options.enumsAsInts()).isTrue();
        assertThat(options.omitDefaultValues()).isFalse();
    }

    @Test
    @DisplayName("GIVEN config with omitDefaultValues=true WHEN calling from() SHOULD set flag")
    void given_omitDefaultValuesConfig_when_from_should_setFlag() {
        JsonConfig.PrintOptions config = new JsonConfig.PrintOptions();
        config.setOmitDefaultValues(true);

        JsonPrintOptions options = GatewayJsonSettings.printOptions(config);

        assertThat(options.preserveProtoFieldNames()).isFalse();
        assertThat(options.enumsAsInts()).isFalse();
        assertThat(options.omitDefaultValues()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "true,true,true",
        "true,false,true",
        "false,true,false",
    })
    @DisplayName("GIVEN config with multiple flags WHEN calling from() SHOULD map all flags")
    void given_multipleFlags_when_from_should_mapAll(boolean preserve, boolean enumsAsInts, boolean omitDefaults) {
        JsonConfig.PrintOptions config = new JsonConfig.PrintOptions();
        config.setPreserveProtoFieldNames(preserve);
        config.setEnumsAsInts(enumsAsInts);
        config.setOmitDefaultValues(omitDefaults);

        JsonPrintOptions options = GatewayJsonSettings.printOptions(config);

        assertThat(options.preserveProtoFieldNames()).isEqualTo(preserve);
        assertThat(options.enumsAsInts()).isEqualTo(enumsAsInts);
        assertThat(options.omitDefaultValues()).isEqualTo(omitDefaults);
    }

    // ── global() and install() ────────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN fresh instance WHEN calling global() SHOULD return DEFAULT")
    void given_fresh_when_globalCalled_should_returnDEFAULT() {
        assertThat(JsonPrintOptions.global()).isEqualTo(JsonPrintOptions.DEFAULT);
    }

    @Test
    @DisplayName("GIVEN installed options WHEN calling global() SHOULD return installed")
    void given_installedOptions_when_globalCalled_should_returnInstalled() {
        JsonPrintOptions options = new JsonPrintOptions(true, false, true);
        JsonPrintOptions.install(options);

        assertThat(JsonPrintOptions.global()).isEqualTo(options);
    }

    @Test
    @DisplayName("GIVEN null install() WHEN calling global() SHOULD return DEFAULT")
    void given_nullInstall_when_globalCalled_should_returnDEFAULT() {
        JsonPrintOptions.install(new JsonPrintOptions(true, true, true));
        JsonPrintOptions.install(null);

        assertThat(JsonPrintOptions.global()).isEqualTo(JsonPrintOptions.DEFAULT);
    }

    // ── forService(ServiceDefinition) ─────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN null service WHEN calling forService() SHOULD return global options")
    void given_nullService_when_forService_should_returnGlobal() {
        JsonPrintOptions global = new JsonPrintOptions(true, false, false);
        JsonPrintOptions.install(global);

        JsonPrintOptions result = GatewayJsonSettings.printOptionsFor(null);

        assertThat(result).isEqualTo(global);
    }

    @Test
    @DisplayName("GIVEN service with no overrides WHEN calling forService() SHOULD return global options")
    void given_serviceWithNoOverrides_when_forService_should_returnGlobal() {
        JsonPrintOptions global = new JsonPrintOptions(true, false, false);
        JsonPrintOptions.install(global);

        ServiceDefinition service = new ServiceDefinition();
        service.setJsonPrint(null);

        JsonPrintOptions result = GatewayJsonSettings.printOptionsFor(service);

        assertThat(result).isEqualTo(global);
    }

    @Test
    @DisplayName("GIVEN service with partial override WHEN calling forService() SHOULD merge with global")
    void given_serviceWithPartialOverride_when_forService_should_merge() {
        JsonPrintOptions global = new JsonPrintOptions(true, false, false);
        JsonPrintOptions.install(global);

        ServiceDefinition service = new ServiceDefinition();
        JsonPrintOverride override = new JsonPrintOverride();
        override.setEnumsAsInts(true);
        service.setJsonPrint(override);

        JsonPrintOptions result = GatewayJsonSettings.printOptionsFor(service);

        assertThat(result.preserveProtoFieldNames()).isTrue();
        assertThat(result.enumsAsInts()).isTrue();
        assertThat(result.omitDefaultValues()).isFalse();
    }

    @Test
    @DisplayName("GIVEN service overriding all flags WHEN calling forService() SHOULD use all overrides")
    void given_serviceWithAllOverrides_when_forService_should_useAllOverrides() {
        JsonPrintOptions global = new JsonPrintOptions(true, false, false);
        JsonPrintOptions.install(global);

        ServiceDefinition service = new ServiceDefinition();
        JsonPrintOverride override = new JsonPrintOverride();
        override.setPreserveProtoFieldNames(false);
        override.setEnumsAsInts(true);
        override.setOmitDefaultValues(true);
        service.setJsonPrint(override);

        JsonPrintOptions result = GatewayJsonSettings.printOptionsFor(service);

        assertThat(result.preserveProtoFieldNames()).isFalse();
        assertThat(result.enumsAsInts()).isTrue();
        assertThat(result.omitDefaultValues()).isTrue();
    }

    @Test
    @DisplayName("GIVEN service with one override as false WHEN calling forService() SHOULD override to false")
    void given_serviceOverridesToFalse_when_forService_should_overrideFalse() {
        JsonPrintOptions global = new JsonPrintOptions(true, true, true);
        JsonPrintOptions.install(global);

        ServiceDefinition service = new ServiceDefinition();
        JsonPrintOverride override = new JsonPrintOverride();
        override.setPreserveProtoFieldNames(false);
        service.setJsonPrint(override);

        JsonPrintOptions result = GatewayJsonSettings.printOptionsFor(service);

        assertThat(result.preserveProtoFieldNames()).isFalse();
        assertThat(result.enumsAsInts()).isTrue();
        assertThat(result.omitDefaultValues()).isTrue();
    }

    // ── fieldName(FieldDescriptor) ───────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN preserveProtoFieldNames=false WHEN calling fieldName() SHOULD return jsonName")
    void given_preserveFalse_when_fieldName_should_returnJsonName() {
        JsonPrintOptions options = new JsonPrintOptions(false, false, false);

        // Use a real field from the parity test proto
        var proto = buildTestProto();
        var field = proto.findMessageTypeByName("TestMsg").findFieldByName("proto_name");

        String result = options.fieldName(field);

        assertThat(result).isEqualTo("protoName");
    }

    @Test
    @DisplayName("GIVEN preserveProtoFieldNames=true WHEN calling fieldName() SHOULD return proto name")
    void given_preserveTrue_when_fieldName_should_returnProtoName() {
        JsonPrintOptions options = new JsonPrintOptions(true, false, false);

        var proto = buildTestProto();
        var field = proto.findMessageTypeByName("TestMsg").findFieldByName("proto_name");

        String result = options.fieldName(field);

        assertThat(result).isEqualTo("proto_name");
    }

    // ── Test helper ──────────────────────────────────────────────────────────────────

    private static com.google.protobuf.Descriptors.FileDescriptor buildTestProto() {
        try {
            var proto = com.google.protobuf.DescriptorProtos.FileDescriptorProto.newBuilder()
                    .setName("test.proto")
                    .setSyntax("proto3")
                    .setPackage("test")
                    .addMessageType(com.google.protobuf.DescriptorProtos.DescriptorProto.newBuilder()
                            .setName("TestMsg")
                            .addField(com.google.protobuf.DescriptorProtos.FieldDescriptorProto.newBuilder()
                                    .setName("proto_name")
                                    .setNumber(1)
                                    .setType(com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(
                                            com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label
                                                    .LABEL_OPTIONAL)))
                    .build();
            return com.google.protobuf.Descriptors.FileDescriptor.buildFrom(
                    proto, new com.google.protobuf.Descriptors.FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
