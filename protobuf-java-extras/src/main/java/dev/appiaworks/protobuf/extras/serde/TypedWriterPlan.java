package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.ProtocolMessageEnum;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a {@link TypedMessageWriter} for one generated message class must do: for every field,
 * the accessor protoc generated for it and the shape of the write.
 *
 * <p>Every accessor is found by reflection on the class and checked against the Java type protoc
 * generates for the field's kind, rather than derived from naming rules alone. protoc renames a
 * field whose name it reserves — {@code class} reads as {@code getClass_()}, {@code
 * serialized_size} as {@code getSerializedSize_()} — and looked up without the underscore both
 * resolve to methods every message inherits, with the wrong meaning. A field that collides with
 * another after camel-casing gets its number appended. A class that lacks an accessor a field
 * needs is not served.
 *
 * <p>A pure function of the class and its descriptor; the {@link SerializerCodegen} turns it into
 * a class.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public final class TypedWriterPlan {

    /**
     * One field's accessors, each verified to exist on the class.
     *
     * @param descriptor the field
     * @param getter the value: {@code getX()} for a singular field, {@code getX(int)} for a
     *     repeated one, {@code getXMap()} (or {@code getXValueMap()} for enum values) for a map
     * @param has {@code hasX()} when the field has presence and is singular, else null
     * @param count {@code getXCount()} for a repeated field, else null
     * @param valueGetter {@code getXValue()} / {@code getXValue(int)} for a proto3 enum field,
     *     which keeps an unknown number; null for proto2 enums and every other kind
     * @param messageClass the generated class of a message-typed field (map values included),
     *     null for scalars and single-value types
     */
    public record Field(
            FieldDescriptor descriptor,
            Method getter,
            Method has,
            Method count,
            Method valueGetter,
            Class<? extends Message> messageClass) {

        public boolean isMap() {
            return descriptor.isMapField();
        }

        public boolean isRepeated() {
            return descriptor.isRepeated();
        }

        /** The message type the field holds (directly, repeated or as map values), null otherwise. */
        public Descriptor nestedMessageType() {
            return TypedWriterPlan.nestedMessageType(descriptor);
        }

        /** Whether the value (or map value) is a single-value type: written by {@link ProtoWkt}. */
        public boolean isWkt() {
            if (isMap()) return false;
            return descriptor.getType() == FieldDescriptor.Type.MESSAGE && ProtoWkt.isWkt(descriptor.getMessageType());
        }
    }

    private final Class<? extends Message> type;
    private final Descriptor descriptor;
    private final List<Field> fields;
    private final Map<String, Class<? extends Message>> nestedTypes;

    private TypedWriterPlan(
            Class<? extends Message> type,
            Descriptor descriptor,
            List<Field> fields,
            Map<String, Class<? extends Message>> nestedTypes) {
        this.type = type;
        this.descriptor = descriptor;
        this.fields = List.copyOf(fields);
        this.nestedTypes = Map.copyOf(nestedTypes);
    }

    /**
     * @param type the generated class to read
     * @param descriptor its descriptor, the one the writer's {@code FieldDescriptor}s bind to
     * @throws IllegalArgumentException when the class lacks an accessor a field needs
     */
    public static TypedWriterPlan of(Class<? extends Message> type, Descriptor descriptor) {
        List<Field> fields = new ArrayList<>();
        Map<String, Class<? extends Message>> nestedTypes = new LinkedHashMap<>();
        for (FieldDescriptor fd : descriptor.getFields()) {
            Field field = resolve(type, fd);
            fields.add(field);
            if (field.messageClass() != null) {
                nestedTypes.putIfAbsent(field.nestedMessageType().getFullName(), field.messageClass());
            }
        }
        return new TypedWriterPlan(type, descriptor, fields, nestedTypes);
    }

    public Class<? extends Message> type() {
        return type;
    }

    public Descriptor descriptor() {
        return descriptor;
    }

    public List<Field> fields() {
        return fields;
    }

    /** The generated classes the fields reach, by message type full name: what the writer wires to. */
    public Map<String, Class<? extends Message>> nestedTypes() {
        return nestedTypes;
    }

    /** The message type a field holds, directly, repeated or as map values; null for scalars and single-value types. */
    public static Descriptor nestedMessageType(FieldDescriptor fd) {
        if (fd.isMapField()) {
            FieldDescriptor value = fd.getMessageType().findFieldByNumber(2);
            return value.getType() == FieldDescriptor.Type.MESSAGE && !ProtoWkt.isWkt(value.getMessageType())
                    ? value.getMessageType()
                    : null;
        }
        if (fd.getType() != FieldDescriptor.Type.MESSAGE || ProtoWkt.isWkt(fd.getMessageType())) return null;
        return fd.getMessageType();
    }

    // ── Accessor resolution ───────────────────────────────────────────────────────────

    /**
     * The camel-cased names protoc reserves: every generated message already has
     * {@code getClass()}, {@code getSerializedSize()} and the rest, so a field of that name gets
     * a trailing underscore ({@code getClass_()}).
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "Class",
            "DefaultInstanceForType",
            "ParserForType",
            "SerializedSize",
            "AllFields",
            "DescriptorForType",
            "InitializationErrorString",
            "UnknownFields",
            "CachedSize");

    private static Field resolve(Class<? extends Message> type, FieldDescriptor fd) {
        String cap = capitalizedName(fd);
        if (fd.isMapField()) {
            FieldDescriptor value = fd.getMessageType().findFieldByNumber(2);
            boolean enumValues = value.getType() == FieldDescriptor.Type.ENUM;
            Method map = null;
            for (String candidate : candidates(cap, fd)) {
                // Enum values: the numbers (proto3) when the class offers them, else the constants
                map = enumValues ? method(type, "get" + candidate + "ValueMap") : null;
                if (map == null) map = method(type, "get" + candidate + "Map");
                if (map != null) break;
            }
            if (map == null || !Map.class.isAssignableFrom(map.getReturnType())) {
                throw missing(type, fd, "get" + cap + "Map()");
            }
            return new Field(fd, map, null, null, null, mapValueClass(map, value));
        }
        if (fd.isRepeated()) {
            for (String candidate : candidates(cap, fd)) {
                Method count = method(type, "get" + candidate + "Count");
                Method element = method(type, "get" + candidate, int.class);
                if (count == null || count.getReturnType() != int.class) continue;
                if (element == null || !returns(element, fd)) continue;
                Method valueGetter = null;
                if (fd.getType() == FieldDescriptor.Type.ENUM) {
                    Method number = method(type, "get" + candidate + "Value", int.class);
                    if (number != null && number.getReturnType() == int.class) valueGetter = number;
                }
                return new Field(fd, element, null, count, valueGetter, messageClass(element, fd));
            }
            throw missing(type, fd, "get" + cap + "Count() / get" + cap + "(int)");
        }
        for (String candidate : candidates(cap, fd)) {
            Method getter = method(type, "get" + candidate);
            if (getter == null || !returns(getter, fd)) continue;
            Method has = null;
            if (fd.hasPresence()) {
                has = method(type, "has" + candidate);
                if (has == null || has.getReturnType() != boolean.class) continue;
            }
            Method valueGetter = null;
            if (fd.getType() == FieldDescriptor.Type.ENUM) {
                Method number = method(type, "get" + candidate + "Value");
                if (number != null && number.getReturnType() == int.class) valueGetter = number;
            }
            return new Field(fd, getter, has, null, valueGetter, messageClass(getter, fd));
        }
        throw missing(type, fd, "get" + cap + "()");
    }

    /**
     * The names protoc may have given the field: the camel-cased name (with the trailing
     * underscore for a reserved one), and that name with the field number appended, which protoc
     * adds when two fields collide after camel-casing.
     */
    private static List<String> candidates(String cap, FieldDescriptor fd) {
        String base = FORBIDDEN.contains(cap) ? cap + "_" : cap;
        return List.of(base, base + fd.getNumber());
    }

    /** Whether the accessor returns the Java type protoc generates for the field's kind. */
    private static boolean returns(Method accessor, FieldDescriptor fd) {
        Class<?> r = accessor.getReturnType();
        return switch (fd.getType()) {
            case STRING -> r == String.class;
            case BYTES -> r == ByteString.class;
            case INT32, SINT32, SFIXED32, UINT32, FIXED32 -> r == int.class;
            case INT64, SINT64, SFIXED64, UINT64, FIXED64 -> r == long.class;
            case FLOAT -> r == float.class;
            case DOUBLE -> r == double.class;
            case BOOL -> r == boolean.class;
            case ENUM -> ProtocolMessageEnum.class.isAssignableFrom(r);
            case MESSAGE, GROUP -> Message.class.isAssignableFrom(r);
            default -> false;
        };
    }

    /** The generated class of a message-typed field, from the accessor's return type; null otherwise. */
    @SuppressWarnings("unchecked")
    private static Class<? extends Message> messageClass(Method accessor, FieldDescriptor fd) {
        if (nestedMessageType(fd) == null) return null;
        return (Class<? extends Message>) accessor.getReturnType();
    }

    /** The generated class of a map field's message values, from the map's generic value type. */
    @SuppressWarnings("unchecked")
    private static Class<? extends Message> mapValueClass(Method mapAccessor, FieldDescriptor value) {
        if (value.getType() != FieldDescriptor.Type.MESSAGE || ProtoWkt.isWkt(value.getMessageType())) return null;
        Type generic = mapAccessor.getGenericReturnType();
        if (generic instanceof ParameterizedType p && p.getActualTypeArguments().length == 2) {
            Type valueType = p.getActualTypeArguments()[1];
            if (valueType instanceof Class<?> c && Message.class.isAssignableFrom(c)) {
                return (Class<? extends Message>) c;
            }
        }
        throw new IllegalArgumentException(mapAccessor + " does not expose its message value type");
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static IllegalArgumentException missing(Class<?> type, FieldDescriptor fd, String expected) {
        return new IllegalArgumentException(
                type.getName() + " has no accessor " + expected + " for field " + fd.getFullName());
    }

    /**
     * protoc's {@code UnderscoresToCapitalizedCamelCase}: every letter after an underscore or a
     * digit is capitalised, the first always is, underscores vanish.
     */
    static String capitalizedName(FieldDescriptor fd) {
        String name =
                fd.getType() == FieldDescriptor.Type.GROUP ? fd.getMessageType().getName() : fd.getName();
        StringBuilder out = new StringBuilder(name.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z') {
                out.append(capitalizeNext ? (char) (c - ('a' - 'A')) : c);
                capitalizeNext = false;
            } else if (c >= 'A' && c <= 'Z') {
                out.append(c);
                capitalizeNext = false;
            } else if (c >= '0' && c <= '9') {
                out.append(c);
                capitalizeNext = true;
            } else {
                capitalizeNext = true;
            }
        }
        return out.toString();
    }
}
