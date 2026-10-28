package dev.appiaworks.shiguchi.gateway;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;

/**
 * The AIP-154 {@code etag} field on the HTTP side ({@code rest.resource-etags}).
 *
 * <p>A resource carrying a string field {@code etag} has a version its backend already tracks, so
 * it is a better validator than a hash of the response: it survives a change in how the response
 * is printed, and a write can be made conditional on it. The field holds the opaque tag without
 * quotes; the gateway adds them for the {@code ETag} header and strips them from {@code If-Match}.
 * A weak tag keeps its {@code W/} prefix both ways.
 */
final class ResourceEtags {

    static final String FIELD = "etag";

    private ResourceEtags() {}

    /** Whether the message type has a singular string field {@code etag}. */
    static boolean hasEtag(Descriptor type) {
        FieldDescriptor field = type.findFieldByName(FIELD);
        return field != null && !field.isRepeated() && field.getJavaType() == FieldDescriptor.JavaType.STRING;
    }

    /** The {@code ETag} header for a response message, or null when its {@code etag} is empty or absent. */
    static String responseTag(Message message) {
        if (!hasEtag(message.getDescriptorForType())) return null;
        Object value = message.getField(message.getDescriptorForType().findFieldByName(FIELD));
        return value instanceof String s && !s.isEmpty() ? toHeader(s) : null;
    }

    /** {@code abc} → {@code "abc"}, {@code W/abc} → {@code W/"abc"}; a value already quoted is kept. */
    static String toHeader(String value) {
        boolean weak = value.startsWith("W/");
        String opaque = weak ? value.substring(2) : value;
        if (!(opaque.length() >= 2 && opaque.startsWith("\"") && opaque.endsWith("\""))) {
            // A quote inside an entity tag would end it early; the header cannot carry one.
            opaque = "\"" + opaque.replace("\"", "") + "\"";
        }
        return weak ? "W/" + opaque : opaque;
    }

    /** {@code "abc"} → {@code abc}, {@code W/"abc"} → {@code W/abc}: the field value for a header tag. */
    static String fromHeader(String tag) {
        String t = tag.trim();
        boolean weak = t.startsWith("W/");
        String opaque = weak ? t.substring(2) : t;
        if (opaque.length() >= 2 && opaque.startsWith("\"") && opaque.endsWith("\"")) {
            opaque = opaque.substring(1, opaque.length() - 1);
        }
        return weak ? "W/" + opaque : opaque;
    }

    /**
     * The request field an {@code If-Match} tag goes into, as a dotted path the request builder
     * binds like a query parameter: {@code etag} when the request message has one (AIP-135
     * deletes), else {@code <body>.etag} when the route's {@code body} names a message field that
     * has one (AIP-134 updates). Null when neither does.
     */
    static String requestField(Descriptor input, String body) {
        if (hasEtag(input)) return FIELD;
        if (body == null || body.isBlank() || "*".equals(body)) return null;
        FieldDescriptor field = input.findFieldByName(body);
        if (field != null
                && !field.isRepeated()
                && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && hasEtag(field.getMessageType())) {
            return body + "." + FIELD;
        }
        return null;
    }

    /**
     * Puts the {@code If-Match} tag into the request at {@code path} ({@code etag} or
     * {@code <body>.etag}). A body that already carries a different etag contradicts the header;
     * that is a 400 rather than a guess at which one the caller meant.
     */
    @SuppressWarnings("unchecked")
    static void applyIfMatch(java.util.Map<String, Object> request, String path, String tag) {
        java.util.Map<String, Object> target = request;
        int dot = path.indexOf('.');
        if (dot > 0) {
            String parent = path.substring(0, dot);
            Object nested = request.get(parent);
            if (!(nested instanceof java.util.Map<?, ?>)) {
                nested = new java.util.HashMap<String, Object>();
                request.put(parent, nested);
            }
            target = (java.util.Map<String, Object>) nested;
        }
        Object existing = target.get(FIELD);
        if (existing instanceof String s && !s.isEmpty() && !s.equals(tag)) {
            throw io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("If-Match names etag '" + tag + "' but the request body carries '" + s + "'")
                    .asRuntimeException();
        }
        target.put(FIELD, tag);
    }

    /**
     * A parsed {@code If-Match}. {@code tag} is the field value to send, null for {@code *} (any
     * current version, which says nothing the backend could check); {@code count} is how many
     * tags the header named.
     */
    record IfMatch(String tag, int count) {
        boolean multiple() {
            return count > 1;
        }
    }

    /** The request's {@code If-Match}, or null when it has none. */
    static IfMatch ifMatch(HttpHeaders headers) {
        List<String> values = headers.getIfMatch();
        if (values == null || values.isEmpty()) return null;
        List<String> tags = new ArrayList<>();
        for (String value : values) {
            for (String tag : value.split(",")) {
                if (!tag.isBlank()) tags.add(tag.trim());
            }
        }
        if (tags.isEmpty()) return null;
        if (tags.size() == 1 && "*".equals(tags.get(0))) return new IfMatch(null, 1);
        return new IfMatch(fromHeader(tags.get(0)), tags.size());
    }
}
