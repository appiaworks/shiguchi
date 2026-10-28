package dev.appiaworks.protobuf.extras;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * A JSON value that cannot become the proto type its field has: {@code "2026-02-30"} for a
 * {@code google.type.Date}, {@code "yesterday"} for a Timestamp, text a {@link ProtoScalarCodec}
 * rejected.
 *
 * <p>The message names the type, the value and what was expected, and is safe to show to whoever
 * sent the value. Inside the gateway the same failure surfaces as a gRPC
 * {@code INVALID_ARGUMENT} status instead, with the same text.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public class ProtoJsonException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String typeName;
    private final transient Object value;

    public ProtoJsonException(String typeName, Object value, String reason, Throwable cause) {
        super("Invalid " + typeName + " value '" + value + "': " + reason, cause);
        this.typeName = typeName;
        this.value = value;
    }

    /** The full proto name of the type the value was meant for, e.g. {@code google.type.Date}. */
    public String typeName() {
        return typeName;
    }

    /** The rejected value as it arrived: a String, Number, Boolean, Map or List. */
    public Object value() {
        return value;
    }
}
