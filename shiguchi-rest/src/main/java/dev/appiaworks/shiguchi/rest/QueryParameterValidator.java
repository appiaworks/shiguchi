package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Finds the query parameters of a transcoded request that bind to nothing, so a caller's typo
 * ({@code ?pageSzie=}) can be answered with 400 instead of being silently dropped.
 *
 * <p>A parameter is known when it is one the gateway consumes itself (paging, sorting,
 * {@code fields}), is on the configured ignore list, or names a field of the request message.
 * Field names are matched the way the request map is read ({@code ProtoMapConverter.dynamicFromMap}):
 * JSON or proto spelling, dotted for a nested field ({@code ?book.title=}). A map field, or a
 * {@code Struct}, {@code Value} or {@code Any}, accepts whatever follows it, since its keys are
 * data rather than schema.
 */
public final class QueryParameterValidator {

    /** {@code BadRequest.FieldViolation.reason} of an unknown query parameter. */
    public static final String UNKNOWN_QUERY_PARAMETER = "UNKNOWN_QUERY_PARAMETER";

    private QueryParameterValidator() {}

    /** The parameters in {@code names} that bind to nothing, in the order given. */
    public static List<String> unknownParameters(
            Descriptor input, Collection<String> names, Set<String> ignoredParameters) {
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            if (GrpcRequestBuilder.isGatewayParam(name) || ignoredParameters.contains(name)) continue;
            if (!bindsToField(input, name)) unknown.add(name);
        }
        return unknown;
    }

    /**
     * {@code INVALID_ARGUMENT} listing each unknown parameter as a {@code BadRequest} field
     * violation — the same shape protovalidate failures take, so a client handles both alike.
     */
    public static BadRequestFailure failure(Descriptor input, List<String> unknown) {
        BadRequest.Builder badRequest = BadRequest.newBuilder();
        for (String name : unknown) {
            badRequest.addFieldViolations(BadRequest.FieldViolation.newBuilder()
                    .setField(name)
                    .setReason(UNKNOWN_QUERY_PARAMETER)
                    .setDescription("No field named '" + name + "' on " + input.getFullName()));
        }
        Status status = Status.INVALID_ARGUMENT.withDescription(
                (unknown.size() == 1 ? "Unknown query parameter: " : "Unknown query parameters: ")
                        + String.join(", ", unknown));
        return new BadRequestFailure(status, Any.pack(badRequest.build()));
    }

    /** The status and packed {@code BadRequest} detail of a rejected request. */
    public record BadRequestFailure(Status status, Any detail) {}

    private static boolean bindsToField(Descriptor input, String dottedName) {
        Descriptor cursor = input;
        String[] segments = dottedName.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            FieldDescriptor field = field(cursor, segments[i]);
            if (field == null) return false;
            boolean last = i == segments.length - 1;
            if (last || acceptsAnyKey(field)) return true;
            if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return false;
            cursor = field.getMessageType();
        }
        return true;
    }

    /** The field {@code name} addresses, by proto or JSON spelling; null when there is none. */
    static FieldDescriptor field(Descriptor descriptor, String name) {
        if (name.isEmpty()) return null;
        FieldDescriptor byProtoName = descriptor.findFieldByName(name);
        if (byProtoName != null) return byProtoName;
        for (FieldDescriptor fd : descriptor.getFields()) {
            if (fd.getJsonName().equals(name)) return fd;
        }
        return null;
    }

    private static boolean acceptsAnyKey(FieldDescriptor field) {
        if (field.isMapField()) return true;
        if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return false;
        return switch (field.getMessageType().getFullName()) {
            case "google.protobuf.Struct", "google.protobuf.Value", "google.protobuf.Any" -> true;
            default -> false;
        };
    }
}
