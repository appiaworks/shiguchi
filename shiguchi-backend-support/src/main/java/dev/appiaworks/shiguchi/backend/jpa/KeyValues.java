package dev.appiaworks.shiguchi.backend.jpa;

import io.grpc.Status;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

/** Round-trips a sort-key value through the string a keyset cursor stores. */
final class KeyValues {

    private KeyValues() {}

    /** The value as a string that {@link #decode} turns back into an equal value. */
    static String encode(Object value) {
        return switch (value) {
            case Date d -> Long.toString(d.getTime()); // before the Temporal cases: java.sql.* extend Date
            case Enum<?> e -> e.name();
            default -> value.toString();
        };
    }

    /** The value of {@code type} that {@code text} encodes; bad text is a bad token. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Comparable<?> decode(Class<?> type, String text) {
        try {
            Class<?> t = box(type);
            if (t == String.class) {
                return text;
            } else if (t == Integer.class) {
                return Integer.valueOf(text);
            } else if (t == Long.class) {
                return Long.valueOf(text);
            } else if (t == Short.class) {
                return Short.valueOf(text);
            } else if (t == Byte.class) {
                return Byte.valueOf(text);
            } else if (t == Double.class) {
                return Double.valueOf(text);
            } else if (t == Float.class) {
                return Float.valueOf(text);
            } else if (t == Boolean.class) {
                return Boolean.valueOf(text);
            } else if (t == BigDecimal.class) {
                return new BigDecimal(text);
            } else if (t == BigInteger.class) {
                return new BigInteger(text);
            } else if (t == UUID.class) {
                return UUID.fromString(text);
            } else if (t == Instant.class) {
                return Instant.parse(text);
            } else if (t == LocalDate.class) {
                return LocalDate.parse(text);
            } else if (t == LocalDateTime.class) {
                return LocalDateTime.parse(text);
            } else if (t == LocalTime.class) {
                return LocalTime.parse(text);
            } else if (t == OffsetDateTime.class) {
                return OffsetDateTime.parse(text);
            } else if (t == Timestamp.class) {
                return new Timestamp(Long.parseLong(text));
            } else if (t == Date.class) {
                return new Date(Long.parseLong(text));
            } else if (t.isEnum()) {
                return Enum.valueOf((Class<Enum>) t, text);
            }
        } catch (RuntimeException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Malformed page_token")
                    .asRuntimeException();
        }
        throw new IllegalStateException(
                "Keyset paging cannot sort by a " + type.getName() + " column; use offset paging");
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "double" -> Double.class;
            case "float" -> Float.class;
            case "boolean" -> Boolean.class;
            default -> type;
        };
    }
}
