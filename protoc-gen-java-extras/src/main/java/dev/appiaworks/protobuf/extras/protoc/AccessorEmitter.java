package dev.appiaworks.protobuf.extras.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;

/**
 * Writes the Java methods inserted into a generated message class and its {@code Builder}.
 *
 * <p>Every method delegates: reads go through the mapping's {@code toJava} method, writes through
 * its {@code fromJava} method, and both end in the accessors protoc generated. Nothing here
 * re-implements a conversion.
 */
final class AccessorEmitter {

    private AccessorEmitter() {}

    /** How a field holds its values. */
    enum Shape {
        SINGULAR,
        REPEATED,
        MAP
    }

    /**
     * A field one mapping applies to.
     *
     * @param accessor the field's name in its generated accessors ({@code SessionDate} in
     *     {@code getSessionDate()}), as {@link JavaNames#accessorName(DescriptorProto, FieldDescriptorProto)}
     *     resolves it
     * @param mapKey for {@link Shape#MAP}, the type of the map's key; null otherwise
     */
    record MappedField(FieldDescriptorProto field, String accessor, TypeMapping mapping, Shape shape, Type mapKey) {}

    /** Read methods, for the message class and again for its Builder: both have the getters used. */
    static String getters(MappedField f) {
        String name = f.accessor();
        TypeMapping m = f.mapping();
        String java = m.javaType();
        String as = name + "As" + m.suffix();
        String proto = f.field().getName();
        return switch (f.shape()) {
            case SINGULAR ->
                m.isEnum()
                        ? """

                          /** {@code %s} as a {@code %s}; null when it is unspecified. */
                          public %s get%s() {
                            return %s(get%s());
                          }
                        """.formatted(proto, m.suffix(), java, as, m.toJava(), name)
                        : """

                          /** {@code %s} as a {@code %s}; null when the field is not set. */
                          public %s get%s() {
                            return has%s() ? %s(get%s()) : null;
                          }
                        """.formatted(proto, m.suffix(), java, as, name, m.toJava(), name);
            case REPEATED -> """

                  /** {@code %s} as an unmodifiable list of {@code %s}. */
                  public java.util.List<%s> get%sList() {
                    int count = get%sCount();
                    java.util.List<%s> values = new java.util.ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                      values.add(%s(get%s(i)));
                    }
                    return java.util.Collections.unmodifiableList(values);
                  }
                """.formatted(proto, m.suffix(), java, as, name, java, m.toJava(), name);
            case MAP ->
                """

                  /** {@code %s} as an unmodifiable map with {@code %s} values, in the field's order. */
                  public java.util.Map<%s, %s> get%sMap() {
                    java.util.Map<%s, %s> values = new java.util.LinkedHashMap<>();
                    get%sMap().forEach((key, value) -> values.put(key, %s(value)));
                    return java.util.Collections.unmodifiableMap(values);
                  }
                """.formatted(
                        proto, m.suffix(), boxed(f.mapKey()), java, as, boxed(f.mapKey()), java, name, m.toJava());
        };
    }

    /** Write methods, for the Builder. */
    static String setters(MappedField f) {
        String name = f.accessor();
        TypeMapping m = f.mapping();
        String param = m.javaParamType();
        String proto = f.field().getName();
        // An enum has a value for "nothing" and the converter maps null to it; a message does not.
        String converted = m.isEnum()
                ? m.fromJava() + "(value)"
                : m.fromJava() + "(java.util.Objects.requireNonNull(value, \"value\"))";
        return switch (f.shape()) {
            case SINGULAR -> """

                  /** Sets {@code %s} from a {@code %s}. */
                  public Builder set%s(%s value) {
                    return set%s(%s);
                  }
                """.formatted(proto, m.suffix(), name, param, name, converted);
            case REPEATED ->
                """

                  /** Adds to {@code %s} from a {@code %s}. */
                  public Builder add%s(%s value) {
                    return add%s(%s);
                  }

                  /** Adds every {@code %s} to {@code %s}. */
                  public Builder addAll%sAs%s(java.lang.Iterable<? extends %s> values) {
                    for (%s value : values) {
                      add%s(%s);
                    }
                    return this;
                  }
                """.formatted(
                                proto,
                                m.suffix(),
                                name,
                                param,
                                name,
                                converted,
                                m.suffix(),
                                proto,
                                name,
                                m.suffix(),
                                param,
                                param,
                                name,
                                converted);
            case MAP -> """

                  /** Puts an entry into {@code %s} from a {@code %s}. */
                  public Builder put%s(%s key, %s value) {
                    return put%s(key, %s);
                  }
                """.formatted(proto, m.suffix(), name, primitive(f.mapKey()), param, name, converted);
        };
    }

    /** The Java type of a map key as a type argument. */
    private static String boxed(Type key) {
        return switch (key) {
            case TYPE_STRING -> "java.lang.String";
            case TYPE_BOOL -> "java.lang.Boolean";
            case TYPE_INT64, TYPE_UINT64, TYPE_SINT64, TYPE_FIXED64, TYPE_SFIXED64 -> "java.lang.Long";
            default -> "java.lang.Integer";
        };
    }

    /** The Java type of a map key as protoc declares it on {@code putXxx}. */
    private static String primitive(Type key) {
        return switch (key) {
            case TYPE_STRING -> "java.lang.String";
            case TYPE_BOOL -> "boolean";
            case TYPE_INT64, TYPE_UINT64, TYPE_SINT64, TYPE_FIXED64, TYPE_SFIXED64 -> "long";
            default -> "int";
        };
    }
}
