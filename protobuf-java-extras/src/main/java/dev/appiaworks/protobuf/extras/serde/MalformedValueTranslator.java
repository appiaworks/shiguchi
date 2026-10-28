package dev.appiaworks.protobuf.extras.serde;

import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * Turns a {@link ProtoJsonException} into the exception type the host reports bad input with.
 *
 * <p>The serde is static and called from many places, generated code among them, so a host that
 * needs its own type cannot catch and convert at every call site. It registers one translator
 * through {@link java.util.ServiceLoader} instead: the gateway's turns the failure into a gRPC
 * {@code INVALID_ARGUMENT} status. With none on the classpath, the {@link ProtoJsonException}
 * itself is thrown.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public interface MalformedValueTranslator {

    /** The exception to throw in place of {@code e}. */
    RuntimeException translate(ProtoJsonException e);

    /**
     * True when the exception is already of the host's type, so a codec that threw it on purpose
     * has it passed through instead of wrapped.
     */
    boolean isTranslated(RuntimeException e);
}
