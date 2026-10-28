package dev.appiaworks.protobuf.extras;

import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.NullValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.google.type.Date;
import com.google.type.DateTime;
import com.google.type.Decimal;
import com.google.type.Money;
import com.google.type.TimeOfDay;
import com.google.type.TimeZone;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conversions between the well-known proto types and the Java types an application would rather
 * hold: {@code google.protobuf.Timestamp} and {@link Instant}, {@code google.type.Date} and
 * {@link LocalDate}, {@code google.type.Money} and a {@link BigDecimal} amount, and so on.
 *
 * <p>Naming: {@code toXxx(proto)} returns the Java value; the method named after the proto type
 * ({@code timestamp(..)}, {@code date(..)}) builds the message.
 *
 * <p>Nothing here guesses. A proto value the Java type cannot hold — a Date with no day, a
 * TimeOfDay of {@code 24:00:00}, a DateTime with no offset asked for as an
 * {@link OffsetDateTime} — throws {@link IllegalArgumentException} saying what is missing, as
 * does a Java value outside the proto type's range.
 *
 * <p>The methods take generated classes. For a {@code DynamicMessage}, convert first with
 * {@link #as}: {@code toLocalDate(as(dynamic, Date.getDefaultInstance()))}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.8.0")
public final class ProtoTypes {

    private ProtoTypes() {}

    private static final int NANOS_PER_SECOND = 1_000_000_000;
    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    // ── Generated ↔ dynamic ──────────────────────────────────────────────────────────

    /**
     * The message as the generated class of {@code prototype}. A message that already is one is
     * returned as it is; anything else of the same proto type — a {@code DynamicMessage}, including
     * one built on a descriptor from gRPC reflection — is re-read from its bytes.
     *
     * @throws IllegalArgumentException when the message is of another proto type
     */
    @SuppressWarnings("unchecked")
    public static <M extends Message> M as(Message message, M prototype) {
        if (prototype.getClass().isInstance(message)) return (M) message;
        String expected = prototype.getDescriptorForType().getFullName();
        String actual = message.getDescriptorForType().getFullName();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Expected a " + expected + ", got a " + actual);
        }
        try {
            return (M) prototype.getParserForType().parseFrom(message.toByteString());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Cannot read " + actual + " as " + expected, e);
        }
    }

    /**
     * The message as a {@code DynamicMessage} on the given descriptor, for code that works from
     * descriptors. The descriptor must describe the message's own proto type.
     *
     * @throws IllegalArgumentException when the descriptor is of another proto type
     */
    public static DynamicMessage dynamic(Message message, Descriptor descriptor) {
        if (message instanceof DynamicMessage dm && dm.getDescriptorForType() == descriptor) return dm;
        String expected = descriptor.getFullName();
        String actual = message.getDescriptorForType().getFullName();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Expected a " + expected + ", got a " + actual);
        }
        try {
            return DynamicMessage.parseFrom(descriptor, message.toByteString());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Cannot read " + actual + " on the given descriptor", e);
        }
    }

    // ── google.protobuf.Timestamp ────────────────────────────────────────────────────

    public static Instant toInstant(Timestamp timestamp) {
        Timestamps.checkValid(timestamp);
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /** The timestamp as a date-time at UTC. */
    public static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return OffsetDateTime.ofInstant(toInstant(timestamp), ZoneOffset.UTC);
    }

    /** @throws IllegalArgumentException outside 0001-01-01 to 9999-12-31, the range a Timestamp holds */
    public static Timestamp timestamp(Instant instant) {
        return Timestamps.checkValid(Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build());
    }

    public static Timestamp timestamp(OffsetDateTime dateTime) {
        return timestamp(dateTime.toInstant());
    }

    // ── google.protobuf.Duration ─────────────────────────────────────────────────────

    public static java.time.Duration toDuration(com.google.protobuf.Duration duration) {
        Durations.checkValid(duration);
        return java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
    }

    /** @throws IllegalArgumentException beyond roughly ±10 000 years, the range a proto Duration holds */
    public static com.google.protobuf.Duration duration(java.time.Duration duration) {
        long seconds = duration.getSeconds();
        int nanos = duration.getNano();
        // java.time keeps nanos non-negative; proto wants seconds and nanos to share a sign.
        if (seconds < 0 && nanos > 0) {
            seconds += 1;
            nanos -= NANOS_PER_SECOND;
        }
        return Durations.checkValid(com.google.protobuf.Duration.newBuilder()
                .setSeconds(seconds)
                .setNanos(nanos)
                .build());
    }

    // ── google.type.Date ─────────────────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException when the date is partial (no year, month or day) or not a
     *     calendar date; use {@link #toTemporal} for the partial forms
     */
    public static LocalDate toLocalDate(Date date) {
        if (date.getYear() == 0 || date.getMonth() == 0 || date.getDay() == 0) {
            throw new IllegalArgumentException("google.type.Date is not a full date: " + describe(date));
        }
        try {
            return LocalDate.of(date.getYear(), date.getMonth(), date.getDay());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("google.type.Date is not a calendar date: " + describe(date), e);
        }
    }

    /**
     * The date in whichever form it carries: a {@link LocalDate}, a {@link YearMonth} (no day), a
     * {@link Year} (year only) or a {@link MonthDay} (no year, e.g. an anniversary).
     *
     * @throws IllegalArgumentException for any other combination, such as a year and a day with no month
     */
    public static TemporalAccessor toTemporal(Date date) {
        int y = date.getYear();
        int m = date.getMonth();
        int d = date.getDay();
        try {
            if (y != 0 && m != 0 && d != 0) return LocalDate.of(y, m, d);
            if (y != 0 && m != 0) return YearMonth.of(y, m);
            if (y != 0 && d == 0) return Year.of(y);
            if (y == 0 && m != 0 && d != 0) return MonthDay.of(m, d);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("google.type.Date is not a calendar date: " + describe(date), e);
        }
        throw new IllegalArgumentException("google.type.Date has no usable form: " + describe(date));
    }

    /** @throws IllegalArgumentException when the year is outside 1 to 9999 */
    public static Date date(LocalDate date) {
        return Date.newBuilder()
                .setYear(checkYear(date.getYear()))
                .setMonth(date.getMonthValue())
                .setDay(date.getDayOfMonth())
                .build();
    }

    /** A Date with no day. */
    public static Date date(YearMonth yearMonth) {
        return Date.newBuilder()
                .setYear(checkYear(yearMonth.getYear()))
                .setMonth(yearMonth.getMonthValue())
                .build();
    }

    /** A Date with a year only. */
    public static Date date(Year year) {
        return Date.newBuilder().setYear(checkYear(year.getValue())).build();
    }

    /** A Date with no year. */
    public static Date date(MonthDay monthDay) {
        return Date.newBuilder()
                .setMonth(monthDay.getMonthValue())
                .setDay(monthDay.getDayOfMonth())
                .build();
    }

    // ── google.type.TimeOfDay ────────────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException for the values a TimeOfDay allows and a {@link LocalTime} does
     *     not: {@code 24:00:00} and a leap second
     */
    public static LocalTime toLocalTime(TimeOfDay time) {
        try {
            return LocalTime.of(time.getHours(), time.getMinutes(), time.getSeconds(), time.getNanos());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "google.type.TimeOfDay does not fit a LocalTime: " + time.getHours() + ":" + time.getMinutes() + ":"
                            + time.getSeconds() + " nanos=" + time.getNanos(),
                    e);
        }
    }

    public static TimeOfDay timeOfDay(LocalTime time) {
        return TimeOfDay.newBuilder()
                .setHours(time.getHour())
                .setMinutes(time.getMinute())
                .setSeconds(time.getSecond())
                .setNanos(time.getNano())
                .build();
    }

    // ── google.type.DateTime ─────────────────────────────────────────────────────────

    /**
     * The civil date and time, without the offset or zone the message may also carry.
     *
     * @throws IllegalArgumentException when the year is missing or the fields are not a date-time
     */
    public static LocalDateTime toLocalDateTime(DateTime dateTime) {
        if (dateTime.getYear() == 0) {
            throw new IllegalArgumentException("google.type.DateTime has no year");
        }
        try {
            return LocalDateTime.of(
                    dateTime.getYear(),
                    dateTime.getMonth(),
                    dateTime.getDay(),
                    dateTime.getHours(),
                    dateTime.getMinutes(),
                    dateTime.getSeconds(),
                    dateTime.getNanos());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("google.type.DateTime is not a date-time: " + e.getMessage(), e);
        }
    }

    /**
     * @throws IllegalArgumentException when the message carries neither a UTC offset nor a time
     *     zone — it is then a local date-time; use {@link #toLocalDateTime}
     */
    public static OffsetDateTime toOffsetDateTime(DateTime dateTime) {
        return toZonedDateTime(dateTime).toOffsetDateTime();
    }

    /**
     * The date-time in its zone, or at its fixed offset when it carries one instead.
     *
     * @throws IllegalArgumentException when the message carries neither, or a zone id the JVM does
     *     not know
     */
    public static ZonedDateTime toZonedDateTime(DateTime dateTime) {
        LocalDateTime local = toLocalDateTime(dateTime);
        try {
            return switch (dateTime.getTimeOffsetCase()) {
                case UTC_OFFSET ->
                    ZonedDateTime.of(
                            local,
                            ZoneOffset.ofTotalSeconds(
                                    Math.toIntExact(dateTime.getUtcOffset().getSeconds())));
                case TIME_ZONE ->
                    ZonedDateTime.of(local, ZoneId.of(dateTime.getTimeZone().getId()));
                case TIMEOFFSET_NOT_SET ->
                    throw new IllegalArgumentException("google.type.DateTime has no UTC offset or time zone");
            };
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException("google.type.DateTime has an unusable offset or zone", e);
        }
    }

    /** A DateTime with neither offset nor zone: a local date-time. */
    public static DateTime dateTime(LocalDateTime dateTime) {
        return local(dateTime).build();
    }

    /** A DateTime with a UTC offset. */
    public static DateTime dateTime(OffsetDateTime dateTime) {
        return local(dateTime.toLocalDateTime())
                .setUtcOffset(com.google.protobuf.Duration.newBuilder()
                        .setSeconds(dateTime.getOffset().getTotalSeconds()))
                .build();
    }

    /** A DateTime with a time zone, or with a UTC offset when the zone is a fixed offset. */
    public static DateTime dateTime(ZonedDateTime dateTime) {
        if (dateTime.getZone() instanceof ZoneOffset) return dateTime(dateTime.toOffsetDateTime());
        return local(dateTime.toLocalDateTime())
                .setTimeZone(TimeZone.newBuilder().setId(dateTime.getZone().getId()))
                .build();
    }

    private static DateTime.Builder local(LocalDateTime dateTime) {
        return DateTime.newBuilder()
                .setYear(checkYear(dateTime.getYear()))
                .setMonth(dateTime.getMonthValue())
                .setDay(dateTime.getDayOfMonth())
                .setHours(dateTime.getHour())
                .setMinutes(dateTime.getMinute())
                .setSeconds(dateTime.getSecond())
                .setNanos(dateTime.getNano());
    }

    // ── google.type.Decimal ──────────────────────────────────────────────────────────

    /**
     * An unset Decimal (empty text) is zero.
     *
     * @throws IllegalArgumentException when the text is not a decimal number
     */
    public static BigDecimal toBigDecimal(Decimal decimal) {
        String text = decimal.getValue();
        if (text.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("google.type.Decimal is not a decimal number: '" + text + "'", e);
        }
    }

    /** The value in plain notation, scale kept: {@code 12.30} stays {@code "12.30"}. */
    public static Decimal decimal(BigDecimal value) {
        return Decimal.newBuilder().setValue(value.toPlainString()).build();
    }

    // ── google.type.Money ────────────────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException when units and nanos disagree in sign or nanos is out of
     *     range, which the message forbids
     */
    public static MoneyValue toMoneyValue(Money money) {
        long units = money.getUnits();
        int nanos = money.getNanos();
        if (nanos <= -NANOS_PER_SECOND || nanos >= NANOS_PER_SECOND) {
            throw new IllegalArgumentException("google.type.Money nanos out of range: " + nanos);
        }
        if ((units > 0 && nanos < 0) || (units < 0 && nanos > 0)) {
            throw new IllegalArgumentException(
                    "google.type.Money units and nanos differ in sign: units=" + units + " nanos=" + nanos);
        }
        BigDecimal amount = BigDecimal.valueOf(units).add(BigDecimal.valueOf(nanos, 9));
        BigDecimal stripped = amount.stripTrailingZeros();
        return new MoneyValue(
                money.getCurrencyCode(),
                stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped);
    }

    /**
     * @throws IllegalArgumentException when the amount has more than 9 fractional digits or its
     *     whole part does not fit a 64-bit integer; nothing is rounded
     */
    public static Money money(MoneyValue value) {
        BigDecimal amount = value.amount();
        try {
            // Truncating toward zero gives units and nanos the same sign, as Money requires.
            long units = amount.setScale(0, RoundingMode.DOWN).longValueExact();
            int nanos =
                    amount.subtract(BigDecimal.valueOf(units)).movePointRight(9).intValueExact();
            return Money.newBuilder()
                    .setCurrencyCode(value.currencyCode())
                    .setUnits(units)
                    .setNanos(nanos)
                    .build();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString()
                            + " has more than 9 fractional digits or does not fit google.type.Money",
                    e);
        }
    }

    // ── google.type.DayOfWeek, google.type.Month ─────────────────────────────────────

    /** Null for {@code DAY_OF_WEEK_UNSPECIFIED} and for a value this build does not know. */
    public static java.time.DayOfWeek toDayOfWeek(com.google.type.DayOfWeek day) {
        if (day == com.google.type.DayOfWeek.UNRECOGNIZED || day == com.google.type.DayOfWeek.DAY_OF_WEEK_UNSPECIFIED) {
            return null;
        }
        return java.time.DayOfWeek.of(day.getNumber());
    }

    /** {@code DAY_OF_WEEK_UNSPECIFIED} for null. */
    public static com.google.type.DayOfWeek dayOfWeek(java.time.DayOfWeek day) {
        return day == null
                ? com.google.type.DayOfWeek.DAY_OF_WEEK_UNSPECIFIED
                : com.google.type.DayOfWeek.forNumber(day.getValue());
    }

    /** Null for {@code MONTH_UNSPECIFIED} and for a value this build does not know. */
    public static java.time.Month toMonth(com.google.type.Month month) {
        if (month == com.google.type.Month.UNRECOGNIZED || month == com.google.type.Month.MONTH_UNSPECIFIED) {
            return null;
        }
        return java.time.Month.of(month.getNumber());
    }

    /** {@code MONTH_UNSPECIFIED} for null. */
    public static com.google.type.Month month(java.time.Month month) {
        return month == null
                ? com.google.type.Month.MONTH_UNSPECIFIED
                : com.google.type.Month.forNumber(month.getValue());
    }

    // ── Wrappers ─────────────────────────────────────────────────────────────────────
    // A wrapper exists to tell "unset" from the zero value, so null maps to null both ways:
    // unwrap(message.hasCount() ? message.getCount() : null), and a null wrap(..) result is
    // a field to leave unset.

    public static Integer unwrap(Int32Value value) {
        return value == null ? null : value.getValue();
    }

    public static Long unwrap(Int64Value value) {
        return value == null ? null : value.getValue();
    }

    /** The unsigned value, which may exceed {@code Integer.MAX_VALUE}. */
    public static Long unwrap(UInt32Value value) {
        return value == null ? null : Integer.toUnsignedLong(value.getValue());
    }

    /** The unsigned value, which may exceed {@code Long.MAX_VALUE}. */
    public static BigInteger unwrap(UInt64Value value) {
        return value == null ? null : new BigInteger(Long.toUnsignedString(value.getValue()));
    }

    public static Float unwrap(FloatValue value) {
        return value == null ? null : value.getValue();
    }

    public static Double unwrap(DoubleValue value) {
        return value == null ? null : value.getValue();
    }

    public static Boolean unwrap(BoolValue value) {
        return value == null ? null : value.getValue();
    }

    public static String unwrap(StringValue value) {
        return value == null ? null : value.getValue();
    }

    public static ByteString unwrap(BytesValue value) {
        return value == null ? null : value.getValue();
    }

    public static Int32Value wrap(Integer value) {
        return value == null ? null : Int32Value.of(value);
    }

    public static Int64Value wrap(Long value) {
        return value == null ? null : Int64Value.of(value);
    }

    public static FloatValue wrap(Float value) {
        return value == null ? null : FloatValue.of(value);
    }

    public static DoubleValue wrap(Double value) {
        return value == null ? null : DoubleValue.of(value);
    }

    public static BoolValue wrap(Boolean value) {
        return value == null ? null : BoolValue.of(value);
    }

    public static StringValue wrap(String value) {
        return value == null ? null : StringValue.of(value);
    }

    public static BytesValue wrap(ByteString value) {
        return value == null ? null : BytesValue.of(value);
    }

    /** @throws IllegalArgumentException outside 0 to 2^32 - 1 */
    public static UInt32Value uint32Value(long value) {
        if (value < 0 || value > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Not an unsigned 32-bit integer: " + value);
        }
        return UInt32Value.of((int) value);
    }

    /** @throws IllegalArgumentException outside 0 to 2^64 - 1 */
    public static UInt64Value uint64Value(BigInteger value) {
        if (value.signum() < 0 || value.compareTo(UINT64_MAX) > 0) {
            throw new IllegalArgumentException("Not an unsigned 64-bit integer: " + value);
        }
        return UInt64Value.of(value.longValue());
    }

    // ── google.protobuf.Struct, Value, ListValue ─────────────────────────────────────

    /** The struct as a map in field order; values as {@link #toObject} gives them. */
    public static Map<String, Object> toMap(Struct struct) {
        Map<String, Object> result = new LinkedHashMap<>();
        struct.getFieldsMap().forEach((key, value) -> result.put(key, toObject(value)));
        return result;
    }

    /** Null, a {@code Double}, a {@code String}, a {@code Boolean}, a {@code Map} or a {@code List}. */
    public static Object toObject(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> toMap(value.getStructValue());
            case LIST_VALUE -> toList(value.getListValue());
        };
    }

    public static List<Object> toList(ListValue list) {
        List<Object> result = new ArrayList<>(list.getValuesCount());
        for (Value value : list.getValuesList()) result.add(toObject(value));
        return result;
    }

    /** @throws IllegalArgumentException when a value is not one {@link #value} accepts */
    public static Struct struct(Map<String, ?> map) {
        Struct.Builder builder = Struct.newBuilder();
        map.forEach((key, value) -> builder.putFields(key, value(value)));
        return builder.build();
    }

    /**
     * A Value for null, a {@code Number} (stored as a double, as JSON numbers are), text, a
     * {@code Boolean}, a {@code Map} with string keys, or a {@code Collection}.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Value value(Object object) {
        Value.Builder builder = Value.newBuilder();
        if (object == null) {
            builder.setNullValue(NullValue.NULL_VALUE);
        } else if (object instanceof Number number) {
            builder.setNumberValue(number.doubleValue());
        } else if (object instanceof CharSequence text) {
            builder.setStringValue(text.toString());
        } else if (object instanceof Boolean bool) {
            builder.setBoolValue(bool);
        } else if (object instanceof Map<?, ?> map) {
            Struct.Builder struct = Struct.newBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("A Struct key must be a String, got: " + entry.getKey());
                }
                struct.putFields(key, value(entry.getValue()));
            }
            builder.setStructValue(struct);
        } else if (object instanceof Collection<?> collection) {
            builder.setListValue(listValue(collection));
        } else {
            throw new IllegalArgumentException(
                    "No google.protobuf.Value form for " + object.getClass().getName());
        }
        return builder.build();
    }

    /** @throws IllegalArgumentException when an element is not one {@link #value} accepts */
    public static ListValue listValue(Collection<?> values) {
        ListValue.Builder builder = ListValue.newBuilder();
        for (Object value : values) builder.addValues(value(value));
        return builder.build();
    }

    // ── google.protobuf.FieldMask ────────────────────────────────────────────────────

    public static List<String> toPaths(FieldMask mask) {
        return Collections.unmodifiableList(new ArrayList<>(mask.getPathsList()));
    }

    public static FieldMask fieldMask(Collection<String> paths) {
        return FieldMask.newBuilder().addAllPaths(paths).build();
    }

    // ── Internals ────────────────────────────────────────────────────────────────────

    private static int checkYear(int year) {
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("Year " + year + " is outside 1 to 9999, the range google.type holds");
        }
        return year;
    }

    private static String describe(Date date) {
        return "year=" + date.getYear() + " month=" + date.getMonth() + " day=" + date.getDay();
    }
}
