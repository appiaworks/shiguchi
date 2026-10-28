package fixtures;

import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer;
import dev.appiaworks.shiguchi.rest.GrpcStatusMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

/**
 * User-override fixtures for {@code ShiguchiAutoConfigurationTest}.
 *
 * <p>Deliberately placed OUTSIDE the {@code dev.appiaworks.shiguchi} package tree: {@code
 * ShiguchiAutoConfiguration} declares {@code @ComponentScan(basePackages = "dev.appiaworks.shiguchi")},
 * so a nested {@code @Configuration} class living under {@code dev.appiaworks.shiguchi.autoconfigure}
 * (the test's own package) would be picked up by that scan on EVERY context refresh — including
 * the "no user beans" scenarios — silently supplying a custom bean where the test means to observe
 * the gateway's default. Keeping these fixtures in an unrelated package keeps them invisible to
 * the scan; they only enter the context when a test explicitly opts in via {@code
 * withUserConfiguration(...)}.
 */
public final class CustomBeanOverrideFixtures {

    private CustomBeanOverrideFixtures() {}

    @Configuration
    public static class CustomStatusMapperConfig {
        public static final GrpcStatusMapper INSTANCE = status -> HttpStatus.I_AM_A_TEAPOT;

        @Bean
        GrpcStatusMapper customGrpcStatusMapper() {
            return INSTANCE;
        }
    }

    @Configuration
    public static class CustomAuthorizerConfig {
        public static final GatewayAuthorizer INSTANCE = request -> Mono.empty();

        @Bean
        GatewayAuthorizer customGatewayAuthorizer() {
            return INSTANCE;
        }
    }

    @Configuration
    public static class CustomErrorRendererConfig {
        public static final GatewayErrorRenderer INSTANCE = (httpStatus, grpcStatus, exchange) -> new byte[0];

        @Bean
        GatewayErrorRenderer customGatewayErrorRenderer() {
            return INSTANCE;
        }
    }

    @Configuration
    public static class CustomProtoJsonConfig {
        public static final dev.appiaworks.protobuf.extras.ProtoJson INSTANCE =
                dev.appiaworks.protobuf.extras.ProtoJson.proto3();

        @Bean
        dev.appiaworks.protobuf.extras.ProtoJson customProtoJson() {
            return INSTANCE;
        }
    }

    @Configuration
    public static class LatLngCodecConfig {
        @Bean
        dev.appiaworks.protobuf.extras.ProtoScalarCodec latLngCodec() {
            return dev.appiaworks.protobuf.extras.ProtoScalarCodec.ofString(
                    com.google.type.LatLng.getDefaultInstance(),
                    ll -> ll.getLatitude() + "," + ll.getLongitude(),
                    text -> com.google.type.LatLng.getDefaultInstance());
        }
    }
}
