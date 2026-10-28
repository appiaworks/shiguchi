package dev.appiaworks.shiguchi.autoconfigure;

import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the gRPC-native OTel plugin when an {@link OpenTelemetry} bean is present.
 *
 * {@link GrpcOpenTelemetry} calls {@code configureChannelBuilder()} on each outbound
 * {@link io.grpc.ManagedChannelBuilder}, which installs W3C {@code traceparent} /
 * {@code tracestate} propagation and gRPC call metrics in one step.
 *
 * Configure the exporter and sampler via Spring Boot's {@code management.otlp.*} and
 * {@code management.tracing.*} properties (see {@code application.yml}).
 */
@Configuration
@ConditionalOnBean(OpenTelemetry.class)
public class GrpcTracingConfiguration {

    @Bean
    public GrpcOpenTelemetry grpcOpenTelemetry(OpenTelemetry openTelemetry) {
        return GrpcOpenTelemetry.newBuilder().sdk(openTelemetry).build();
    }
}
