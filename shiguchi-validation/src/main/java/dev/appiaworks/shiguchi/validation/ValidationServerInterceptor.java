package dev.appiaworks.shiguchi.validation;

import com.google.protobuf.Message;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.StatusRuntimeException;

/**
 * Option B — downstream gRPC server-layer validation interceptor.
 *
 * Register this as a {@code ServerInterceptor} in a downstream service (e.g. via
 * {@code @GrpcGlobalInterceptor} when using grpc-spring-boot-starter, or via
 * {@code ServerInterceptors.intercept()} manually).
 *
 * Validates each incoming request message after it is fully received
 * ({@link io.grpc.ServerCall.Listener#onMessage}). Returns INVALID_ARGUMENT
 * and closes the call immediately on constraint violation — the response message
 * is never sent, and {@code onHalfClose} is never called.
 *
 * Activated when {@code shiguchi.validation.server-enabled=true}.
 */
public class ValidationServerInterceptor implements ServerInterceptor {

    private final ProtoValidator validator;

    public ValidationServerInterceptor(ProtoValidator validator) {
        this.validator = validator;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                if (message instanceof Message protoMessage) {
                    try {
                        validator.validate(protoMessage);
                    } catch (StatusRuntimeException e) {
                        call.close(e.getStatus(), new Metadata());
                        return;
                    }
                }
                super.onMessage(message);
            }
        };
    }
}
