package dev.appiaworks.protobuf.extras.protoc;

import java.util.List;

/**
 * One Java view of a proto type: the Java type a field of that proto type can be read and set
 * as, and the two static methods that convert.
 *
 * <p>A proto type may have several mappings — {@code google.type.DateTime} reads as a
 * {@code LocalDateTime}, an {@code OffsetDateTime} or a {@code ZonedDateTime} — which is why the
 * accessor names carry {@link #suffix}.
 *
 * @param protoType full proto name without a leading dot, e.g. {@code google.type.Date}
 * @param javaType what a getter returns, e.g. {@code java.time.LocalDate}; boxed, never primitive
 * @param javaParamType what a setter takes: {@link #javaType}, or something wider such as
 *     {@code java.util.Map<String, ?>}
 * @param suffix the name the Java type goes by in accessor names: {@code getDayAsLocalDate}
 * @param toJava static method, proto value in, Java value out, e.g.
 *     {@code dev.appiaworks.protobuf.extras.ProtoTypes.toLocalDate}
 * @param fromJava static method, Java value in, proto value out
 * @param isEnum whether the proto type is an enum: no presence to test, and null is a value
 */
record TypeMapping(
        String protoType,
        String javaType,
        String javaParamType,
        String suffix,
        String toJava,
        String fromJava,
        boolean isEnum) {

    private static final String T = "dev.appiaworks.protobuf.extras.ProtoTypes.";

    /** The well-known types, converted by {@code ProtoTypes} in protobuf-java-extras. */
    static final List<TypeMapping> DEFAULTS = List.of(
            message("google.protobuf.Timestamp", "java.time.Instant", "toInstant", "timestamp"),
            message("google.protobuf.Timestamp", "java.time.OffsetDateTime", "toOffsetDateTime", "timestamp"),
            message("google.protobuf.Duration", "java.time.Duration", "toDuration", "duration"),
            message("google.type.Date", "java.time.LocalDate", "toLocalDate", "date"),
            message("google.type.TimeOfDay", "java.time.LocalTime", "toLocalTime", "timeOfDay"),
            message("google.type.DateTime", "java.time.LocalDateTime", "toLocalDateTime", "dateTime"),
            message("google.type.DateTime", "java.time.OffsetDateTime", "toOffsetDateTime", "dateTime"),
            message("google.type.DateTime", "java.time.ZonedDateTime", "toZonedDateTime", "dateTime"),
            message("google.type.Decimal", "java.math.BigDecimal", "toBigDecimal", "decimal"),
            message("google.type.Money", "dev.appiaworks.protobuf.extras.MoneyValue", "toMoneyValue", "money"),
            message("google.protobuf.Int32Value", "java.lang.Integer", "unwrap", "wrap"),
            message("google.protobuf.Int64Value", "java.lang.Long", "unwrap", "wrap"),
            message("google.protobuf.UInt32Value", "java.lang.Long", "unwrap", "uint32Value"),
            message("google.protobuf.UInt64Value", "java.math.BigInteger", "unwrap", "uint64Value"),
            message("google.protobuf.FloatValue", "java.lang.Float", "unwrap", "wrap"),
            message("google.protobuf.DoubleValue", "java.lang.Double", "unwrap", "wrap"),
            message("google.protobuf.BoolValue", "java.lang.Boolean", "unwrap", "wrap"),
            message("google.protobuf.StringValue", "java.lang.String", "unwrap", "wrap"),
            message("google.protobuf.BytesValue", "com.google.protobuf.ByteString", "unwrap", "wrap"),
            new TypeMapping(
                    "google.protobuf.Struct",
                    "java.util.Map<String, Object>",
                    "java.util.Map<String, ?>",
                    "Map",
                    T + "toMap",
                    T + "struct",
                    false),
            new TypeMapping(
                    "google.protobuf.FieldMask",
                    "java.util.List<String>",
                    "java.util.Collection<String>",
                    "Paths",
                    T + "toPaths",
                    T + "fieldMask",
                    false),
            new TypeMapping(
                    "google.type.DayOfWeek",
                    "java.time.DayOfWeek",
                    "java.time.DayOfWeek",
                    "JavaDayOfWeek",
                    T + "toDayOfWeek",
                    T + "dayOfWeek",
                    true),
            new TypeMapping(
                    "google.type.Month",
                    "java.time.Month",
                    "java.time.Month",
                    "JavaMonth",
                    T + "toMonth",
                    T + "month",
                    true));

    private static TypeMapping message(String protoType, String javaType, String to, String from) {
        return new TypeMapping(protoType, javaType, javaType, simpleName(javaType), T + to, T + from, false);
    }

    /** A mapping an application adds for a message type of its own. */
    static TypeMapping custom(String protoType, String javaType, String toJava, String fromJava) {
        return new TypeMapping(protoType, javaType, javaType, simpleName(javaType), toJava, fromJava, false);
    }

    /** {@code java.time.LocalDate} gives {@code LocalDate}; generics are dropped. */
    static String simpleName(String javaType) {
        String raw = javaType.contains("<") ? javaType.substring(0, javaType.indexOf('<')) : javaType;
        return raw.substring(raw.lastIndexOf('.') + 1);
    }
}
