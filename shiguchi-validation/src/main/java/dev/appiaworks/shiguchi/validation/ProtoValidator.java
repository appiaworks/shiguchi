package dev.appiaworks.shiguchi.validation;

import build.buf.protovalidate.ValidationResult;
import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around protovalidate {@link Validator}.
 *
 * Converts {@link ValidationResult} violations into a {@link StatusRuntimeException}
 * with code INVALID_ARGUMENT so the existing gateway error path (GrpcStatusMapper → HTTP 400)
 * works without modification, carrying the violations as a {@code google.rpc.BadRequest}
 * detail so the REST error body and gRPC trailers list them field by field.
 */
public class ProtoValidator {

    private static final Logger log = LoggerFactory.getLogger(ProtoValidator.class);

    private final Validator validator;
    private final boolean logViolations;

    public ProtoValidator(boolean logViolations) throws ValidationException {
        this.validator = ValidatorFactory.newBuilder().build();
        this.logViolations = logViolations;
    }

    /**
     * Validates {@code message} against its proto constraints.
     *
     * @throws StatusRuntimeException INVALID_ARGUMENT when any violation is found
     * @throws StatusRuntimeException INTERNAL when protovalidate itself throws unexpectedly
     */
    public void validate(Message message) {
        ValidationResult result;
        try {
            result = validator.validate(message);
        } catch (ValidationException e) {
            throw Status.INTERNAL
                    .withDescription("Validation engine error: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException();
        }

        if (!result.isSuccess()) {
            String description = formatViolations(result.getViolations());
            if (logViolations) {
                log.warn("Proto validation failed: {}", description);
            }
            throw invalidArgument(description, result.getViolations());
        }
    }

    /**
     * {@code INVALID_ARGUMENT} with the summary as description and a {@code google.rpc.BadRequest}
     * detail in the trailers — one {@code FieldViolation} per violation, {@code field} being the
     * dotted path and {@code reason} the protovalidate rule id — so REST callers get the structured
     * list in the error body's {@code details} and gRPC callers get it in
     * {@code grpc-status-details-bin}.
     */
    private static StatusRuntimeException invalidArgument(
            String description, List<build.buf.protovalidate.Violation> violations) {
        BadRequest.Builder badRequest = BadRequest.newBuilder();
        for (build.buf.protovalidate.Violation v : violations) {
            build.buf.validate.Violation proto = v.toProto();
            badRequest.addFieldViolations(BadRequest.FieldViolation.newBuilder()
                    .setField(fieldPath(proto))
                    .setDescription(proto.getMessage())
                    .setReason(proto.getRuleId()));
        }
        com.google.rpc.Status rich = com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage(description)
                .addDetails(Any.pack(badRequest.build()))
                .build();
        return StatusProto.toStatusRuntimeException(rich);
    }

    private static String formatViolations(List<build.buf.protovalidate.Violation> violations) {
        StringBuilder sb = new StringBuilder();
        for (build.buf.protovalidate.Violation v : violations) {
            if (sb.length() > 0) sb.append("; ");
            build.buf.validate.Violation proto = v.toProto();
            sb.append(proto.hasField() ? fieldPath(proto) : "(root)").append(": ");
            sb.append(proto.getMessage());
        }
        return sb.toString();
    }

    private static String fieldPath(build.buf.validate.Violation proto) {
        if (!proto.hasField()) return "";
        return proto.getField().getElementsList().stream()
                .map(build.buf.validate.FieldPathElement::getFieldName)
                .collect(Collectors.joining("."));
    }
}
