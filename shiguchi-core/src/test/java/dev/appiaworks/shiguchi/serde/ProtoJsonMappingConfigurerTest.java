package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.type.LatLng;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** The configurer installs the application's {@link ProtoScalarCodec} beans with the mapping. */
class ProtoJsonMappingConfigurerTest {

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    @Test
    @DisplayName("Given a ProtoScalarCodec bean, when the configurer is created, should register the codec")
    void given_codecBean_when_configurerCreated_should_registerCodec() {
        ProtoScalarCodec codec = latLngCodec();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("latLngCodec", codec);

        new ProtoJsonMappingConfigurer(new GatewayProperties(), beans.getBeanProvider(ProtoScalarCodec.class));

        assertThat(ProtoJsonMapping.codecFor(LatLng.getDescriptor())).isSameAs(codec);
        assertThat(ProtoWkt.isWkt(LatLng.getDescriptor())).isTrue();
    }

    @Test
    @DisplayName(
            "Given no ProtoScalarCodec bean, when the configurer is created, should leave only the built-in types mapped")
    void given_noCodecBean_when_configurerCreated_should_mapOnlyBuiltIns() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

        ProtoJsonMappingConfigurer configurer =
                new ProtoJsonMappingConfigurer(new GatewayProperties(), beans.getBeanProvider(ProtoScalarCodec.class));

        assertThat(configurer.mapping()).isEqualTo(ProtoJsonMapping.DEFAULTS);
        assertThat(ProtoJsonMapping.codecFor(LatLng.getDescriptor())).isNull();
        assertThat(ProtoJsonMapping.codecFor(com.google.type.Date.getDescriptor()))
                .isNotNull();
    }

    @Test
    @DisplayName(
            "Given two ProtoScalarCodec beans for one message, when the configurer is created, should fail startup naming the message")
    void given_twoCodecBeansForOneMessage_when_configurerCreated_should_fail() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("first", latLngCodec());
        beans.registerSingleton("second", latLngCodec());

        assertThatThrownBy(() -> new ProtoJsonMappingConfigurer(
                        new GatewayProperties(), beans.getBeanProvider(ProtoScalarCodec.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("google.type.LatLng");
    }

    private static ProtoScalarCodec latLngCodec() {
        return ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] parts = text.split(",");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(parts[0]))
                            .setLongitude(Double.parseDouble(parts[1]))
                            .build();
                });
    }
}
