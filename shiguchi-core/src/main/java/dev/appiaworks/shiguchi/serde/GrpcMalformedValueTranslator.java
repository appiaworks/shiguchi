package dev.appiaworks.shiguchi.serde;

import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.serde.MalformedValueTranslator;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Reports a value that cannot become its proto type as gRPC {@code INVALID_ARGUMENT}, which REST
 * answers with HTTP 400 and GraphQL with a field error. Registered through
 * {@code META-INF/services}, so it applies wherever this module is on the classpath.
 */
public final class GrpcMalformedValueTranslator implements MalformedValueTranslator {

    @Override
    public RuntimeException translate(ProtoJsonException e) {
        return Status.INVALID_ARGUMENT
                .withDescription(e.getMessage())
                .withCause(e.getCause())
                .asRuntimeException();
    }

    @Override
    public boolean isTranslated(RuntimeException e) {
        return e instanceof StatusRuntimeException;
    }
}
