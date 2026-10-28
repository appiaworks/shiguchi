package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.type.PostalAddress;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * {@code shiguchi.json.strict-input}: off, the gateway coerces a value that does not fit its
 * field as it always has; on, every parse path refuses it with {@code INVALID_ARGUMENT}.
 */
@DisplayName("shiguchi.json.strict-input")
class StrictInputTest {

    @BeforeAll
    static void requiresCodegen() {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
    }

    private static final JsonFactory JSON = new JsonFactory();
    private static final Descriptor ADDRESS = PostalAddress.getDescriptor();

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    private static void installStrict(boolean strict) {
        GatewayProperties properties = new GatewayProperties();
        properties.getJson().setStrictInput(strict);
        new ProtoJsonMappingConfigurer(
                properties, new DefaultListableBeanFactory().getBeanProvider(ProtoScalarCodec.class));
    }

    private static DynamicMessage compiledParse(String json) throws Exception {
        AbstractDynamicSerializer serializer = new DynamicSerializerFactory().compileDescriptor(ADDRESS);
        try (JsonParser p = JSON.createParser(json)) {
            p.nextToken();
            return serializer.deserialize(p, DynamicMessage.newBuilder(ADDRESS));
        }
    }

    @Test
    @DisplayName("GIVEN the default settings WHEN the configurer installs them SHOULD leave input lenient")
    void given_defaults_when_installed_should_beLenient() {
        installStrict(new GatewayProperties().getJson().isStrictInput());

        assertThat(ProtoJsonMapping.strictInput()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN strict-input off WHEN the compiled parser reads \"abc\" for an int32 SHOULD coerce it to 0, as it always has")
    void given_strictOff_when_compiledReadsAbc_should_coerceToZero() throws Exception {
        installStrict(false);

        DynamicMessage address = compiledParse("{\"revision\":\"abc\",\"regionCode\":\"US\"}");

        assertThat(address.getField(ADDRESS.findFieldByName("revision"))).isEqualTo(0);
        assertThat(address.getField(ADDRESS.findFieldByName("region_code"))).isEqualTo("US");
    }

    @Test
    @DisplayName(
            "GIVEN strict-input on WHEN the compiled parser reads \"abc\" for an int32 SHOULD refuse with INVALID_ARGUMENT naming field and value")
    void given_strictOn_when_compiledReadsAbc_should_refuseWithInvalidArgument() {
        installStrict(true);

        assertThatThrownBy(() -> compiledParse("{\"revision\":\"abc\"}"))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription())
                            .isEqualTo("Invalid int32 value 'abc': google.type.PostalAddress.revision"
                                    + " takes a whole number from -2147483648 to 2147483647");
                });
    }

    @Test
    @DisplayName("GIVEN strict-input on WHEN the body is not a JSON object SHOULD refuse with INVALID_ARGUMENT")
    void given_strictOn_when_bodyIsNotAnObject_should_refuse() {
        installStrict(true);

        assertThatThrownBy(() -> compiledParse("[1,2]"))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getDescription())
                                .contains("google.type.PostalAddress")
                                .contains("expected a JSON object"));
    }

    @Test
    @DisplayName(
            "GIVEN strict-input on WHEN the reflective converter and the compiled GraphQL path read a list for a string SHOULD both refuse it")
    void given_strictOn_when_mapPathsReadWrongShape_should_refuse() throws Exception {
        installStrict(true);
        Map<String, Object> args = Map.of("regionCode", List.of("US"));
        AbstractDynamicSerializer serializer = new DynamicSerializerFactory().compileDescriptor(ADDRESS);

        assertThatThrownBy(() -> ProtoMapConverter.dynamicFromMap(args, ADDRESS))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("google.type.PostalAddress.region_code takes a JSON string");
        assertThatThrownBy(() -> serializer.fromMap(args, DynamicMessage.newBuilder(ADDRESS)))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("google.type.PostalAddress.region_code takes a JSON string");
    }

    @Test
    @DisplayName("GIVEN strict-input on WHEN a well-formed body is read SHOULD build the same message as with it off")
    void given_strictOn_when_wellFormedBody_should_buildTheSameMessage() throws Exception {
        String body = "{\"revision\":3,\"regionCode\":\"US\",\"addressLines\":[\"1 Main St\"],\"unknown\":true}";
        installStrict(false);
        DynamicMessage lenient = compiledParse(body);

        installStrict(true);
        DynamicMessage strict = compiledParse(body);

        assertThat(strict).isEqualTo(lenient);
    }
}
