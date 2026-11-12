package dev.appiaworks.shiguchi.backend;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

/** The Java-to-protobuf conversions {@link ProtoProjector} applies. */
final class ProtoValues {

    private ProtoValues() {}

    static void set(Message.Builder builder, FieldDescriptor field, Object value) {
        if (field.isMapField()) {
            throw unsupported(field, value, "a map field");
        }
        if (field.isRepeated()) {
            if (!(value instanceof Iterable<?> items)) {
                throw unsupported(field, value, "a repeated field needs an Iterable");
            }
            for (Object item : items) {
                builder.addRepeatedField(field, convert(field, item));
            }
        } else {
            builder.setField(field, convert(field, value));
        }
    }

    private static Object convert(FieldDescriptor field, Object value) {
        return switch (field.getJavaType()) {
            case INT -> number(field, value).intValue();
            case LONG -> number(field, value).longValue();
            case FLOAT -> number(field, value).floatValue();
            case DOUBLE -> number(field, value).doubleValue();
            case BOOLEAN -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                throw unsupported(field, value, "expected a Boolean");
            }
            case STRING -> value instanceof Enum<?> e ? e.name() : value.toString();
            case BYTE_STRING -> {
                if (value instanceof byte[] bytes) {
                    yield ByteString.copyFrom(bytes);
                }
                if (value instanceof ByteString bs) {
                    yield bs;
                }
                throw unsupported(field, value, "expected byte[] or ByteString");
            }
            case ENUM -> enumValue(field, value);
            case MESSAGE -> message(field, value);
        };
    }

    private static Number number(FieldDescriptor field, Object value) {
        if (value instanceof Number n) {
            return n;
        }
        throw unsupported(field, value, "expected a Number");
    }

    private static EnumValueDescriptor enumValue(FieldDescriptor field, Object value) {
        var type = field.getEnumType();
        EnumValueDescriptor found = switch (value) {
            case EnumValueDescriptor d -> d;
            case Enum<?> e -> type.findValueByName(e.name());
            case Number n -> type.findValueByNumberCreatingIfUnknown(n.intValue());
            case CharSequence s -> type.findValueByName(s.toString());
            default -> null;
        };
        if (found == null) {
            throw unsupported(field, value, "no such value on enum " + type.getFullName());
        }
        return found;
    }

    private static Object message(FieldDescriptor field, Object value) {
        String type = field.getMessageType().getFullName();
        if (value instanceof Message m && m.getDescriptorForType() == field.getMessageType()) {
            return m;
        }
        if (type.equals("google.protobuf.Timestamp")) {
            Instant instant = switch (value) {
                case Instant i -> i;
                case OffsetDateTime o -> o.toInstant();
                case ZonedDateTime z -> z.toInstant();
                case LocalDateTime l -> l.toInstant(ZoneOffset.UTC);
                case Date d -> d.toInstant();
                default -> throw unsupported(field, value, "not convertible to a Timestamp");
            };
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        }
        if (type.equals("google.protobuf.Duration") && value instanceof Duration d) {
            return com.google.protobuf.Duration.newBuilder()
                    .setSeconds(d.getSeconds())
                    .setNanos(d.getNano())
                    .build();
        }
        throw unsupported(field, value, "register a mapper for " + type);
    }

    private static IllegalArgumentException unsupported(FieldDescriptor field, Object value, String why) {
        return new IllegalArgumentException("Cannot set " + field.getFullName() + " from "
                + value.getClass().getName() + ": " + why);
    }
}
