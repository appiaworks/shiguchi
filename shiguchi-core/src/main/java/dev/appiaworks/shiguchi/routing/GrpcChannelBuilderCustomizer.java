package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.ManagedChannelBuilder;

/**
 * Callback for customizing the outbound {@link ManagedChannelBuilder} the gateway creates for
 * each downstream gRPC service, applied after the gateway's own configuration (interceptor
 * stack, keepalive/idle tuning, transport security) and immediately before the channel is built.
 *
 * <p>Register one or more beans of this type to add {@link io.grpc.ClientInterceptor}s
 * (retries, circuit breakers, custom trace propagation, API-key injection), override transport
 * settings, or tune anything the YAML surface does not expose:
 *
 * <pre>{@code
 * @Bean
 * GrpcChannelBuilderCustomizer retryCustomizer() {
 *     return (service, builder) -> builder.enableRetry().maxRetryAttempts(3);
 * }
 * }</pre>
 *
 * <p>Beans are applied in {@link org.springframework.core.annotation.Order} order. The
 * {@code service} argument identifies the backend, so a customizer can target a single
 * service by name and leave the rest untouched.
 */
@FunctionalInterface
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.14.0")
public interface GrpcChannelBuilderCustomizer {

    /**
     * Customize the channel builder for one downstream service.
     *
     * @param service the service definition (name, host, port, per-service config) from
     *                {@code shiguchi.services[]}
     * @param builder the pre-configured builder about to produce the service's channel
     */
    void customize(ServiceDefinition service, ManagedChannelBuilder<?> builder);
}
