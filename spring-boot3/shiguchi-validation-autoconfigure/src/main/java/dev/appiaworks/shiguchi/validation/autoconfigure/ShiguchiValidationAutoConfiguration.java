package dev.appiaworks.shiguchi.validation.autoconfigure;

import build.buf.protovalidate.exceptions.ValidationException;
import dev.appiaworks.shiguchi.validation.GatewayValidationFilter;
import dev.appiaworks.shiguchi.validation.ProtoValidator;
import dev.appiaworks.shiguchi.validation.ValidationProperties;
import dev.appiaworks.shiguchi.validation.ValidationServerInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Auto-configuration for proto constraint validation (protovalidate-java).
 *
 * Two independent options, each activated by a property:
 *
 * <pre>
 * shiguchi:
 *   validation:
 *     # Option A — validate at the gateway before dispatching to gRPC
 *     gateway-enabled: true
 *
 *     # Option B — validate inside the downstream gRPC server interceptor
 *     server-enabled: true
 *
 *     # Log field-path + constraint name on violation (no field values)
 *     log-violations: true
 * </pre>
 *
 * Both options can be active simultaneously for defence-in-depth.
 */
@AutoConfiguration
@EnableConfigurationProperties(ValidationProperties.class)
public class ShiguchiValidationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OnValidationEnabledCondition.class)
    public ProtoValidator protoValidator(ValidationProperties props) throws ValidationException {
        return new ProtoValidator(props.isLogViolations());
    }

    /**
     * Option A — gateway layer.
     * {@link GatewayValidationFilter} is injected into {@code ReactiveGrpcDispatcher}
     * via an {@code @Autowired(required = false)} setter — no change to the dispatcher's
     * constructor required.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "shiguchi.validation", name = "gateway-enabled", havingValue = "true")
    public GatewayValidationFilter gatewayValidationFilter(ProtoValidator protoValidator) {
        return new GatewayValidationFilter(protoValidator);
    }

    /**
     * Option B — downstream server interceptor.
     * Callers register this bean as a {@code ServerInterceptor} in their gRPC server.
     * With grpc-spring-boot-starter: annotate the service with {@code @GrpcGlobalInterceptor}
     * or register the bean name in {@code grpc.server.security.auth.interceptors}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "shiguchi.validation", name = "server-enabled", havingValue = "true")
    public ValidationServerInterceptor validationServerInterceptor(ProtoValidator protoValidator) {
        return new ValidationServerInterceptor(protoValidator);
    }
}
