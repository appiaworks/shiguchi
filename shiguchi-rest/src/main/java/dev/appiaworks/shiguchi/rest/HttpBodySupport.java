package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;

/**
 * {@code google.api.HttpBody}: a message that carries a raw HTTP body — any content type, not
 * JSON — in {@code data}, with its media type in {@code content_type}. It is how an RPC uploads or
 * downloads a file, an image or a CSV over the transcoded REST surface.
 *
 * <p>Request side: when the method's input is {@code HttpBody} (with {@code body: "*"} or no
 * {@code body}), or the rule's {@code body} names a field of that type, the HTTP body is not
 * parsed; its bytes and {@code Content-Type} fill that {@code HttpBody}, and path variables and
 * query parameters bind the rest of the request as usual.
 *
 * <p>Response side: when the method's output is {@code HttpBody} (and there is no
 * {@code response_body}), or {@code response_body} names a field of that type, the gateway sends
 * {@code data} as the HTTP body with {@code content_type} as its {@code Content-Type}.
 *
 * <p>{@code extensions} is neither filled nor sent: there is no HTTP counterpart to build it from
 * or map it to.
 *
 * <p>Detection is by full name, so it works whatever the descriptor source (reflection, a
 * {@code .pb} file or compiled stubs).
 */
public final class HttpBodySupport {

    public static final String HTTP_BODY = "google.api.HttpBody";

    // Field numbers from google/api/httpbody.proto (looked up by number: no per-call string).
    private static final int CONTENT_TYPE = 1;
    private static final int DATA = 2;

    /** What a raw response carries onto the HTTP response. */
    public record Payload(String contentType, byte[] data) {}

    private HttpBodySupport() {}

    public static boolean isHttpBody(Descriptor descriptor) {
        return descriptor != null && HTTP_BODY.equals(descriptor.getFullName());
    }

    /** Whether the HTTP request body is raw bytes for an {@code HttpBody}, rather than JSON. */
    public static boolean rawRequest(Descriptor input, String bodyRule) {
        if (GrpcRequestBuilder.isNamedBodyField(bodyRule)) {
            FieldDescriptor field = input.findFieldByName(bodyRule);
            return field != null && !field.isRepeated() && isMessage(field) && isHttpBody(field.getMessageType());
        }
        return isHttpBody(input);
    }

    /** Whether the HTTP response body is an {@code HttpBody}'s raw bytes, rather than JSON. */
    public static boolean rawResponse(Descriptor output, String responseBodyRule) {
        if (responseBodyRule != null) {
            FieldDescriptor field = output.findFieldByName(responseBodyRule);
            return field != null && !field.isRepeated() && isMessage(field) && isHttpBody(field.getMessageType());
        }
        return isHttpBody(output);
    }

    /**
     * {@code request} with the raw body placed where the rule says: the request itself when it
     * is the {@code HttpBody}, else the named field.
     */
    public static DynamicMessage withBody(DynamicMessage request, String bodyRule, String contentType, byte[] data) {
        Descriptor input = request.getDescriptorForType();
        if (GrpcRequestBuilder.isNamedBodyField(bodyRule)) {
            FieldDescriptor field = input.findFieldByName(bodyRule);
            return request.toBuilder()
                    .setField(field, httpBody(field.getMessageType(), contentType, data))
                    .build();
        }
        // The whole request is the HttpBody: nothing else binds to it.
        return httpBody(input, contentType, data);
    }

    /**
     * {@link #withBody(DynamicMessage, String, String, byte[])} for a rule that names a body field
     * the caller resolved once (see {@link #namedBodyField}), so a stream does not look it up per chunk.
     */
    public static DynamicMessage withBody(
            DynamicMessage request, FieldDescriptor bodyField, String contentType, byte[] data) {
        return request.toBuilder()
                .setField(bodyField, httpBody(bodyField.getMessageType(), contentType, data))
                .build();
    }

    /** The field a named {@code body} rule points at, or null when the rule is not a field name or matches none. */
    public static FieldDescriptor namedBodyField(Descriptor input, String bodyRule) {
        return GrpcRequestBuilder.isNamedBodyField(bodyRule) ? input.findFieldByName(bodyRule) : null;
    }

    /** The {@code HttpBody} to send back: the response itself, or its {@code response_body} field. */
    public static Payload payload(DynamicMessage response, String responseBodyRule) {
        DynamicMessage body = response;
        if (responseBodyRule != null) {
            FieldDescriptor field = response.getDescriptorForType().findFieldByName(responseBodyRule);
            body = (DynamicMessage) response.getField(field);
        }
        Descriptor type = body.getDescriptorForType();
        String contentType = (String) body.getField(type.findFieldByNumber(CONTENT_TYPE));
        ByteString data = (ByteString) body.getField(type.findFieldByNumber(DATA));
        return new Payload(contentType, data.toByteArray());
    }

    private static DynamicMessage httpBody(Descriptor httpBodyType, String contentType, byte[] data) {
        return DynamicMessage.newBuilder(httpBodyType)
                .setField(httpBodyType.findFieldByNumber(CONTENT_TYPE), contentType == null ? "" : contentType)
                .setField(httpBodyType.findFieldByNumber(DATA), ByteString.copyFrom(data))
                .build();
    }

    private static boolean isMessage(FieldDescriptor field) {
        return field.getJavaType() == FieldDescriptor.JavaType.MESSAGE;
    }
}
