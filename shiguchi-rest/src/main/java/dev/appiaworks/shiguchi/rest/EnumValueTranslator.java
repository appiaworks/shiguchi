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

    /** Map entries are {@code key = 1, value = 2} in every proto3 map. */
    private static final int MAP_ENTRY_VALUE = 2;

    /**
     * Per message type: every field's index by proto name, and by JSON name where that is free, so a
     * key resolves without the per-call string a {@code findFieldByName} builds. A descriptor's fields
     * never change, so the index is built once. It holds indexes, not {@code FieldDescriptor}s: those
     * reference their descriptor, which would pin the weak key and keep replaced schemas alive.
     */
    private static final com.github.benmanes.caffeine.cache.Cache<Descriptor, Map<String, Integer>> FIELDS_BY_NAME =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().weakKeys().build();

    private EnumValueTranslator() {}

    private static FieldDescriptor fieldByName(Descriptor type, String name) {
        Integer i = FIELDS_BY_NAME
                .get(type, t -> {
                    List<FieldDescriptor> fields = t.getFields();
                    Map<String, Integer> index = new java.util.HashMap<>();
                    for (int j = 0; j < fields.size(); j++)
                        index.putIfAbsent(fields.get(j).getJsonName(), j);
                    // A proto name wins over another field's JSON name, as findFieldByName did.
                    for (int j = 0; j < fields.size(); j++)
                        index.put(fields.get(j).getName(), j);
                    return index;
                })
                .get(name);
        return i == null ? null : type.getFields().get(i);
    }

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
            FieldDescriptor fd = fieldByName(type, e.getKey());
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
            FieldDescriptor valueField = fd.getMessageType().findFieldByNumber(MAP_ENTRY_VALUE);
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
}
