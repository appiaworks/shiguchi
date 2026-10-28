package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The optional checks {@link GrpcRequestBuilder} applies while it merges body, query parameters
 * and path variables into one request map ({@code rest.request-validation.*}):
 *
 * <ul>
 *   <li>{@link #conflicts}: a query parameter or path variable that sets a field the body (or an
 *       earlier source) already set to a <em>different</em> value. The same value twice is fine —
 *       AIP-134 style updates repeat the resource name in the path and the body.
 *   <li>{@link #normalizeEnums}: enum names matched regardless of case, rewritten to the
 *       declared name before any serializer sees them.
 * </ul>
 *
 * Both work on the request map, before deserialization, so every serde tier sees the same input.
 */
final class RequestMapChecks {

    /** {@code BadRequest.FieldViolation.reason} of a value two sources disagree on. */
    static final String BINDING_CONFLICT = "BINDING_CONFLICT";

    private RequestMapChecks() {}

    /** One field two sources set differently. */
    record Conflict(String field, String source) {}

    /**
     * Whether writing {@code value} at {@code dottedKey} would contradict what {@code merged}
     * already holds there. Field names are resolved through the descriptor, so {@code ?displayName=}
     * and a body's {@code display_name} are the same field.
     */
    static boolean conflicts(Map<String, Object> merged, Descriptor descriptor, String dottedKey, Object value) {
        Object existing = existingValue(merged, descriptor, dottedKey);
        if (existing == null) return false;
        if (value instanceof List<?> values) {
            // Every value of a repeated query name against the list the body set.
            if (!(existing instanceof List<?> list))
                return values.size() != 1 || !sameValue(existing, String.valueOf(values.get(0)));
            if (list.size() != values.size()) return true;
            for (int i = 0; i < values.size(); i++) {
                if (list.get(i) == null || !sameValue(list.get(i), String.valueOf(values.get(i)))) return true;
            }
            return false;
        }
        return !sameValue(existing, String.valueOf(value));
    }

    /**
     * The field a dotted query or path name addresses, walking message fields by proto or JSON
     * name; null when any segment names no field.
     */
    static FieldDescriptor resolve(Descriptor descriptor, String dottedKey) {
        Descriptor type = descriptor;
        FieldDescriptor field = null;
        for (String segment : dottedKey.split("\\.", -1)) {
            if (type == null) return null;
            field = QueryParameterValidator.field(type, segment);
            if (field == null) return null;
            type = field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !field.isMapField()
                    ? field.getMessageType()
                    : null;
        }
        return field;
    }

    /** {@code INVALID_ARGUMENT} with one {@code BadRequest} violation per conflicting field. */
    static StatusRuntimeException conflictFailure(List<Conflict> conflicts) {
        BadRequest.Builder badRequest = BadRequest.newBuilder();
        List<String> fields = new ArrayList<>();
        for (Conflict c : conflicts) {
            fields.add(c.field());
            badRequest.addFieldViolations(BadRequest.FieldViolation.newBuilder()
                    .setField(c.field())
                    .setReason(BINDING_CONFLICT)
                    .setDescription("The " + c.source() + " '" + c.field()
                            + "' sets a different value than the request body for the same field"));
        }
        com.google.rpc.Status rich = com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage("Conflicting values for " + String.join(", ", fields))
                .addDetails(Any.pack(badRequest.build()))
                .build();
        return StatusProto.toStatusRuntimeException(rich);
    }

    private static Object existingValue(Map<String, Object> merged, Descriptor descriptor, String dottedKey) {
        Object cursor = merged;
        Descriptor type = descriptor;
        for (String segment : dottedKey.split("\\.", -1)) {
            if (!(cursor instanceof Map<?, ?> map)) return null;
            FieldDescriptor field = type == null ? null : QueryParameterValidator.field(type, segment);
            Object next = map.get(segment);
            if (next == null && field != null) {
                next = map.get(field.getName());
                if (next == null) next = map.get(field.getJsonName());
            }
            if (next == null) return null;
            cursor = next;
            type = field != null && field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !field.isMapField()
                    ? field.getMessageType()
                    : null;
        }
        return cursor;
    }

    /** A query/path string against a value parsed from JSON: {@code 7} and {@code "7"} agree. */
    private static boolean sameValue(Object existing, String value) {
        if (existing instanceof String s) return s.equals(value);
        if (existing instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).compareTo(new BigDecimal(value.trim())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (existing instanceof Boolean b) return b.toString().equalsIgnoreCase(value.trim());
        if (existing instanceof List<?> list) {
            return list.size() == 1 && list.get(0) != null && sameValue(list.get(0), value);
        }
        return false; // a message or map set wholesale by one source and as a scalar by another
    }

    /**
     * Rewrites every enum value in {@code map} that names a declared value in the wrong case to
     * the declared name ({@code "red"} → {@code "RED"}), descending into messages and lists of
     * messages. A string matching no value in any case is left alone for the serializer to reject.
     */
    @SuppressWarnings("unchecked")
    static void normalizeEnums(Map<String, Object> map, Descriptor descriptor) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            FieldDescriptor field = QueryParameterValidator.field(descriptor, entry.getKey());
            Object value = entry.getValue();
            if (field == null || value == null) continue;
            if (field.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                Object canonical = canonicalEnum(field, value);
                if (canonical != value) entry.setValue(canonical);
            } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !field.isMapField()) {
                if (value instanceof Map<?, ?> nested) {
                    normalizeEnums((Map<String, Object>) nested, field.getMessageType());
                } else if (value instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> nested) {
                            normalizeEnums((Map<String, Object>) nested, field.getMessageType());
                        }
                    }
                }
            }
        }
    }

    /** The same object when nothing changed, so callers only write what they must. */
    private static Object canonicalEnum(FieldDescriptor field, Object value) {
        EnumDescriptor type = field.getEnumType();
        if (value instanceof String s) {
            // A repeated enum from a query string may arrive comma-separated (?color=red,blue).
            if (field.isRepeated() && s.indexOf(',') >= 0) {
                List<String> parts = new ArrayList<>();
                boolean changed = false;
                for (String part : s.split(",", -1)) {
                    String c = canonicalName(type, part.trim());
                    changed |= !c.equals(part);
                    parts.add(c);
                }
                return changed ? String.join(",", parts) : value;
            }
            String c = canonicalName(type, s);
            return c.equals(s) ? value : c;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object item : list) {
                Object c = item instanceof String s ? canonicalName(type, s) : item;
                changed |= c != item && !c.equals(item);
                out.add(c);
            }
            return changed ? out : value;
        }
        return value;
    }

    private static String canonicalName(EnumDescriptor type, String name) {
        if (type.findValueByName(name) != null) return name;
        for (EnumValueDescriptor v : type.getValues()) {
            if (v.getName().equalsIgnoreCase(name)) return v.getName();
        }
        return name;
    }
}
