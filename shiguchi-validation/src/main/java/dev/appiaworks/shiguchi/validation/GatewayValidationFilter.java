package dev.appiaworks.shiguchi.validation;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;

/**
 * Option A — gateway-layer validation hook.
 *
 * Implements {@link RequestValidationHook} so it can be injected into
 * {@code ReactiveGrpcDispatcher} via the SPI interface defined in shiguchi-core,
 * keeping shiguchi-rest free of a compile dependency on shiguchi-validation.
 *
 * Activated when {@code shiguchi.validation.gateway-enabled=true}.
 */
public class GatewayValidationFilter implements RequestValidationHook {

    private final ProtoValidator validator;

    public GatewayValidationFilter(ProtoValidator validator) {
        this.validator = validator;
    }

    @Override
    public void validate(DynamicMessage request, MethodDescriptor methodDescriptor) {
        validator.validate(request);
    }
}
