package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves enum values between a {@code REST_DOWNSTREAM} backend's own spelling and the synthetic
 * proto enum's value names.
 *
 * <p>The descriptor derived from an OpenAPI document names enum values proto-style, {@code
 * <ENUM>_<VALUE>} ({@code ItemStatus} + {@code active} → {@code ITEM_STATUS_ACTIVE}), because
 * proto enum value names share one scope per package and the GraphQL schema shows them as such.
 * The backend, however, sends and expects the strings its document declares. So on the way in a
 * backend value is replaced by the proto name the loader recorded for it, and on the way out a
 * proto name is replaced by the original string. Values with no recorded mapping pass unchanged,
 * which lets a backend add an enum value without breaking calls (it binds to the zero value, as an
 * unknown enum string does for a gRPC backend).
 */
public final class EnumValueTranslator {

    private EnumValueTranslator() {}

    /**
     * Backend → proto: rewrites enum-typed fields of {@code value} (a record {@code Map}, walked
     * recursively through messages, repeated fields and map values) to proto value names.
     *
     * @param originals enum full name → (proto value name → backend value), as recorded by the loader
     */
    public static Map<String, Object> toProto(
            Map<String, Object> value, Descriptor type, Map<String, Map<String, String>> originals) {
        return translate(value, type, originals, true);
    }

    /** Proto → backend: the inverse of {@link #toProto}, applied to arguments before a request is built. */
    public static Map<String, Object> toBackend(
            Map<String, Object> value, Descriptor type, Map<String, Map<String, String>> originals) {
        return translate(value, type, originals, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> translate(
            Map<String, Object> value, Descriptor type, Map<String, Map<String, String>> originals, boolean toProto) {
        if (value == null || originals == null || originals.isEmpty()) return value;
        Map<String, Object> out = null;
        for (Map.Entry<String, Object> e : value.entrySet()) {
            FieldDescriptor fd = type.findFieldByName(e.getKey());
            if (fd == null) fd = findByJsonName(type, e.getKey());
            if (fd == null || e.getValue() == null) continue;
            Object replaced = translateField(e.getValue(), fd, originals, toProto);
            if (replaced != e.getValue()) {
                if (out == null) out = new LinkedHashMap<>(value);
                out.put(e.getKey(), replaced);
            }
        }
        return out != null ? out : value;
    }

    @SuppressWarnings("unchecked")
    private static Object translateField(
            Object v, FieldDescriptor fd, Map<String, Map<String, String>> originals, boolean toProto) {
        if (fd.isMapField()) {
            FieldDescriptor valueField = fd.getMessageType().findFieldByName("value");
            if (!(v instanceof Map<?, ?> m) || valueField == null) return v;
            Map<String, Object> out = null;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object replaced = translateSingle(e.getValue(), valueField, originals, toProto);
                if (replaced != e.getValue()) {
                    if (out == null) out = new LinkedHashMap<>((Map<String, Object>) m);
                    out.put(String.valueOf(e.getKey()), replaced);
                }
            }
            return out != null ? out : v;
        }
        if (fd.isRepeated()) {
            if (!(v instanceof List<?> list)) return v;
            List<Object> out = null;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Object replaced = translateSingle(item, fd, originals, toProto);
                if (replaced != item) {
                    if (out == null) out = new ArrayList<>(list);
                    out.set(i, replaced);
                }
            }
            return out != null ? out : v;
        }
        return translateSingle(v, fd, originals, toProto);
    }

    @SuppressWarnings("unchecked")
    private static Object translateSingle(
            Object v, FieldDescriptor fd, Map<String, Map<String, String>> originals, boolean toProto) {
        if (v == null) return null;
        switch (fd.getType()) {
            case ENUM: {
                if (!(v instanceof String s)) return v;
                EnumDescriptor enumType = fd.getEnumType();
                Map<String, String> byProtoName = originals.get(enumType.getFullName());
                if (byProtoName == null) return v;
                if (toProto) {
                    if (enumType.findValueByName(s) != null) return s; // already a proto name
                    for (Map.Entry<String, String> e : byProtoName.entrySet()) {
                        if (e.getValue().equals(s)) return e.getKey();
                    }
                    return v;
                }
                String original = byProtoName.get(s);
                return original != null ? original : v;
            }
            case MESSAGE:
                return v instanceof Map<?, ?> m
                        ? translate((Map<String, Object>) m, fd.getMessageType(), originals, toProto)
                        : v;
            default:
                return v;
        }
    }

    private static FieldDescriptor findByJsonName(Descriptor type, String name) {
        for (FieldDescriptor fd : type.getFields()) if (fd.getJsonName().equals(name)) return fd;
        return null;
    }
}
