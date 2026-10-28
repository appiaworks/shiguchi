package dev.appiaworks.shiguchi.validation;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * SPI hook called by {@code ReactiveGrpcDispatcher} after request deserialization.
 *
 * Implement this interface and register a Spring bean to plug in custom request
 * validation without modifying the dispatcher. The validation starter provides
 * a protovalidate-based implementation activated via
 * {@code shiguchi.validation.gateway-enabled=true}.
 *
 * Implementations must throw {@link io.grpc.StatusRuntimeException} with code
 * INVALID_ARGUMENT to signal a validation failure â€” the existing gateway error
 * path converts that to HTTP 400 automatically.
 */
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.2.0")
public interface RequestValidationHook {

    void validate(DynamicMessage request, MethodDescriptor methodDescriptor);
}
