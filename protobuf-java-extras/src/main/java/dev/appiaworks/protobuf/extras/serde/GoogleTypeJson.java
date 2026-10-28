package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ISO text forms of the common {@code google.type} messages — Date, TimeOfDay, DateTime,
 * Decimal — and the flattened Money object, for {@link ProtoWkt} when {@link ProtoJsonMapping}
 * enables them.
 *
 * <p>Writing never loses information: a value the text form cannot carry (a DateTime without a
 * year, a Date with a day but no month, a zone id the JVM does not know) is written as the plain
 * object the proto3 mapping would print. Reading accepts the text form and that object form, so
 * a client that sends the old shape keeps working. Malformed input is a
 * {@link dev.appiaworks.protobuf.extras.ProtoJsonException} ({@code INVALID_ARGUMENT} in the gateway),
 * never a silently dropped field.
 *
 * <p>Fields are looked up by name on the instance descriptor, which may come from gRPC reflection
 * rather than the compiled {@code com.google.type} classes.
 */
final class GoogleTypeJson {

    private GoogleTypeJson() {}

    private static final Pattern FULL_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");
    private static final Pattern MONTH_DAY = Pattern.compile("--(\\d{2})-(\\d{2})");
    private static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?");

    // ── Write ────────────────────────────────────────────────────────────────────────

    /** The JSON value of a mapped message: a String, or a Map when the text form cannot carry it. */
    static Object toJson(DynamicMessage msg) {
        return switch (msg.getDescriptorForType().getFullName()) {
            case ProtoJsonMapping.DATE -> dateToJson(msg);
            case ProtoJsonMapping.TIME_OF_DAY -> timeOfDayToJson(msg);
            case ProtoJsonMapping.DATE_TIME -> dateTimeToJson(msg);
            case ProtoJsonMapping.DECIMAL -> string(msg, "value");
            case ProtoJsonMapping.MONEY -> moneyToJson(msg);
            default -> throw new IllegalArgumentException("Not a mapped google.type: " + msg.getDescriptorForType());
        };
    }

    private static Object dateToJson(DynamicMessage msg) {
        int y = integer(msg, "year");
        int m = integer(msg, "month");
        int d = integer(msg, "day");
        if (y > 0 && y <= 9999 && m > 0 && d > 0) return String.format("%04d-%02d-%02d", y, m, d);
        if (y > 0 && y <= 9999 && m > 0) return String.format("%04d-%02d", y, m);
        if (y > 0 && y <= 9999 && m == 0 && d == 0) return String.format("%04d", y);
        if (y == 0 && m > 0 && d > 0) return String.format("--%02d-%02d", m, d);
        return objectForm(msg);
    }

    private static Object timeOfDayToJson(DynamicMessage msg) {
        return time(integer(msg, "hours"), integer(msg, "minutes"), integer(msg, "seconds"), integer(msg, "nanos"));
    }

    private static Object dateTimeToJson(DynamicMessage msg) {
        Descriptor d = msg.getDescriptorForType();
        int y = integer(msg, "year");
        int mo = integer(msg, "month");
        int day = integer(msg, "day");
        if (y <= 0 || y > 9999 || mo <= 0 || day <= 0) return objectForm(msg);
        String local = String.format("%04d-%02d-%02dT", y, mo, day)
                + time(integer(msg, "hours"), integer(msg, "minutes"), integer(msg, "seconds"), integer(msg, "nanos"));

        FieldDescriptor offsetFd = d.findFieldByName("utc_offset");
        FieldDescriptor zoneFd = d.findFieldByName("time_zone");
        if (offsetFd != null && msg.hasField(offsetFd)) {
            DynamicMessage offset = (DynamicMessage) msg.getField(offsetFd);
            long seconds =
                    ((Number) offset.getField(offset.getDescriptorForType().findFieldByNumber(1))).longValue();
            try {
                return local
                        + ZoneOffset.ofTotalSeconds(Math.toIntExact(seconds)).getId();
            } catch (DateTimeException | ArithmeticException e) {
                return objectForm(msg);
            }
        }
        if (zoneFd != null && msg.hasField(zoneFd)) {
            DynamicMessage zone = (DynamicMessage) msg.getField(zoneFd);
            String id = (String) zone.getField(zone.getDescriptorForType().findFieldByName("id"));
            try {
                ZoneId zoneId = ZoneId.of(id);
                LocalDateTime ldt = LocalDateTime.of(
                        y,
                        mo,
                        day,
                        integer(msg, "hours"),
                        integer(msg, "minutes"),
                        Math.min(integer(msg, "seconds"), 59));
                ZoneOffset offset = zoneId.getRules().getOffset(ldt);
                return local + offset.getId() + "[" + zoneId.getId() + "]";
            } catch (DateTimeException e) {
                return objectForm(msg);
            }
        }
        return local;
    }

    private static Object moneyToJson(DynamicMessage msg) {
        long units = ((Number) field(msg, "units")).longValue();
        int nanos = integer(msg, "nanos");
        BigDecimal amount = BigDecimal.valueOf(units).add(BigDecimal.valueOf(nanos, 9));
        Map<String, Object> out = new LinkedHashMap<>(4);
        out.put("currency", string(msg, "currency_code"));
        out.put("amount", plain(amount));
        return out;
    }

    /** {@code HH:mm:ss} plus a 3, 6 or 9 digit fraction when there are nanos, as Timestamp text does. */
    private static String time(int h, int m, int s, int nanos) {
        String base = String.format("%02d:%02d:%02d", h, m, s);
        if (nanos == 0) return base;
        if (nanos % 1_000_000 == 0) return base + String.format(".%03d", nanos / 1_000_000);
        if (nanos % 1_000 == 0) return base + String.format(".%06d", nanos / 1_000);
        return base + String.format(".%09d", nanos);
    }

    private static String plain(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped).toPlainString();
    }

    /** The proto3 object form: every field by JSON name, zero values included. */
    private static Map<String, Object> objectForm(DynamicMessage msg) {
        return ProtoMapConverter.dynamicToMap(msg);
    }

    // ── Read ─────────────────────────────────────────────────────────────────────────

    /** Builds the mapped message from a JSON-shaped value; malformed input throws. */
    static DynamicMessage fromJava(Object value, Descriptor d) {
        if (value instanceof Map<?, ?> map) return fromObjectForm(map, d);
        return switch (d.getFullName()) {
            case ProtoJsonMapping.DATE -> dateFromJava(value, d);
            case ProtoJsonMapping.TIME_OF_DAY -> timeOfDayFromJava(value, d);
            case ProtoJsonMapping.DATE_TIME -> dateTimeFromJava(value, d);
            case ProtoJsonMapping.DECIMAL -> decimalFromJava(value, d);
            case ProtoJsonMapping.MONEY -> throw invalid(d, value, "expected an object {\"currency\", \"amount\"}");
            default -> throw new IllegalArgumentException("Not a mapped google.type: " + d.getFullName());
        };
    }

    private static DynamicMessage dateFromJava(Object value, Descriptor d) {
        int y;
        int m;
        int day;
        if (value instanceof LocalDate ld) {
            y = ld.getYear();
            m = ld.getMonthValue();
            day = ld.getDayOfMonth();
        } else {
            String s = text(value, d);
            Matcher mt;
            if ((mt = FULL_DATE.matcher(s)).matches()) {
                y = Integer.parseInt(mt.group(1));
                m = Integer.parseInt(mt.group(2));
                day = Integer.parseInt(mt.group(3));
                // Year 0 is "no year" in google.type.Date, so check the calendar in a leap year.
                check(d, value, y == 0 ? 2000 : y, m, day);
            } else if ((mt = YEAR_MONTH.matcher(s)).matches()) {
                y = Integer.parseInt(mt.group(1));
                m = Integer.parseInt(mt.group(2));
                day = 0;
                check(d, value, 2000, m, 1);
            } else if ((mt = YEAR.matcher(s)).matches()) {
                y = Integer.parseInt(mt.group(1));
                m = 0;
                day = 0;
            } else if ((mt = MONTH_DAY.matcher(s)).matches()) {
                y = 0;
                m = Integer.parseInt(mt.group(1));
                day = Integer.parseInt(mt.group(2));
                // 2000 is a leap year, so --02-29 (a birthday) is valid.
                check(d, value, 2000, m, day);
            } else {
                throw invalid(d, value, "expected YYYY-MM-DD, YYYY-MM, YYYY or --MM-DD");
            }
            if (y == 0 && !s.startsWith("--")) throw invalid(d, value, "year 0000 is not a valid year");
        }
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByName("year"), y)
                .setField(d.findFieldByName("month"), m)
                .setField(d.findFieldByName("day"), day)
                .build();
    }

    private static DynamicMessage timeOfDayFromJava(Object value, Descriptor d) {
        int[] t = value instanceof LocalTime lt
                ? new int[] {lt.getHour(), lt.getMinute(), lt.getSecond(), lt.getNano()}
                : parseTime(text(value, d), d, value);
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByName("hours"), t[0])
                .setField(d.findFieldByName("minutes"), t[1])
                .setField(d.findFieldByName("seconds"), t[2])
                .setField(d.findFieldByName("nanos"), t[3])
                .build();
    }

    /**
     * {@code HH:mm[:ss[.fraction]]}. Allows what TimeOfDay allows and {@code LocalTime} does not:
     * {@code 24:00:00} (end of day, e.g. closing time) and second 60 (a leap second).
     */
    private static int[] parseTime(String s, Descriptor d, Object raw) {
        Matcher mt = TIME.matcher(s);
        if (!mt.matches()) throw invalid(d, raw, "expected HH:mm[:ss[.fraction]]");
        int h = Integer.parseInt(mt.group(1));
        int m = Integer.parseInt(mt.group(2));
        int sec = mt.group(3) == null ? 0 : Integer.parseInt(mt.group(3));
        int nanos = mt.group(4) == null ? 0 : Integer.parseInt((mt.group(4) + "00000000").substring(0, 9));
        boolean endOfDay = h == 24 && m == 0 && sec == 0 && nanos == 0;
        if (!endOfDay && (h > 23 || m > 59 || sec > 60)) throw invalid(d, raw, "time out of range");
        return new int[] {h, m, sec, nanos};
    }

    private static DynamicMessage dateTimeFromJava(Object value, Descriptor d) {
        TemporalAccessor parsed;
        if (value instanceof TemporalAccessor ta) {
            parsed = ta;
        } else {
            try {
                parsed = DateTimeFormatter.ISO_DATE_TIME.parse(text(value, d));
            } catch (DateTimeParseException e) {
                throw invalid(d, value, "expected ISO-8601 date-time, e.g. 2026-09-22T14:03:11-04:00");
            }
        }
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d)
                .setField(d.findFieldByName("year"), parsed.get(ChronoField.YEAR))
                .setField(d.findFieldByName("month"), parsed.get(ChronoField.MONTH_OF_YEAR))
                .setField(d.findFieldByName("day"), parsed.get(ChronoField.DAY_OF_MONTH))
                .setField(d.findFieldByName("hours"), parsed.get(ChronoField.HOUR_OF_DAY))
                .setField(d.findFieldByName("minutes"), parsed.get(ChronoField.MINUTE_OF_HOUR))
                .setField(d.findFieldByName("seconds"), parsed.get(ChronoField.SECOND_OF_MINUTE))
                .setField(d.findFieldByName("nanos"), parsed.get(ChronoField.NANO_OF_SECOND));

        ZoneId zone = parsed.query(TemporalQueries.zoneId());
        ZoneOffset offset = parsed.query(TemporalQueries.offset());
        if (zone != null && !(zone instanceof ZoneOffset)) {
            FieldDescriptor zoneFd = d.findFieldByName("time_zone");
            Descriptor zd = zoneFd.getMessageType();
            b.setField(
                    zoneFd,
                    DynamicMessage.newBuilder(zd)
                            .setField(zd.findFieldByName("id"), zone.getId())
                            .build());
        } else if (offset != null) {
            FieldDescriptor offsetFd = d.findFieldByName("utc_offset");
            Descriptor od = offsetFd.getMessageType();
            b.setField(
                    offsetFd,
                    DynamicMessage.newBuilder(od)
                            .setField(od.findFieldByNumber(1), (long) offset.getTotalSeconds())
                            .build());
        }
        return b.build();
    }

    private static DynamicMessage decimalFromJava(Object value, Descriptor d) {
        String s;
        if (value instanceof BigDecimal bd) {
            s = bd.toPlainString();
        } else if (value instanceof Number n) {
            s = n.toString();
        } else {
            s = text(value, d);
        }
        try {
            new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw invalid(d, value, "expected a decimal number, e.g. \"12.34\"");
        }
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByName("value"), s)
                .build();
    }

    /**
     * The object form: proto3 field names (JSON or original spelling), which keeps clients that
     * send {@code {"year":2026,...}} working; for Money also the flattened {@code currency} /
     * {@code amount} this mapping writes.
     */
    @SuppressWarnings("unchecked")
    private static DynamicMessage fromObjectForm(Map<?, ?> map, Descriptor d) {
        if (ProtoJsonMapping.MONEY.equals(d.getFullName())
                && (map.containsKey("amount") || map.containsKey("currency"))) {
            return moneyFromFlat(map, d);
        }
        try {
            return ProtoMapConverter.dynamicFromMap((Map<String, Object>) map, d);
        } catch (RuntimeException e) {
            throw invalid(d, map, e.getMessage());
        }
    }

    private static DynamicMessage moneyFromFlat(Map<?, ?> map, Descriptor d) {
        Object currency = map.get("currency");
        Object amount = map.get("amount");
        BigDecimal value;
        try {
            value = amount instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(amount));
        } catch (NumberFormatException e) {
            throw invalid(d, map, "amount must be a decimal number, e.g. \"12.34\"");
        }
        long units;
        int nanos;
        try {
            // Truncating toward zero gives units and nanos the same sign, as Money requires.
            units = value.setScale(0, RoundingMode.DOWN).longValueExact();
            nanos = value.subtract(BigDecimal.valueOf(units)).movePointRight(9).intValueExact();
        } catch (ArithmeticException e) {
            throw invalid(d, map, "amount has more than 9 fractional digits or does not fit int64");
        }
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByName("currency_code"), currency == null ? "" : String.valueOf(currency))
                .setField(d.findFieldByName("units"), units)
                .setField(d.findFieldByName("nanos"), nanos)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    private static Object field(DynamicMessage msg, String name) {
        return msg.getField(msg.getDescriptorForType().findFieldByName(name));
    }

    private static int integer(DynamicMessage msg, String name) {
        return ((Number) field(msg, name)).intValue();
    }

    private static String string(DynamicMessage msg, String name) {
        return (String) field(msg, name);
    }

    private static String text(Object value, Descriptor d) {
        if (value instanceof String s) return s.trim();
        if (value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof LocalDateTime) {
            return value.toString();
        }
        if (value instanceof List<?>) throw invalid(d, value, "expected a string, got an array");
        throw invalid(d, value, "expected a string");
    }

    private static void check(Descriptor d, Object raw, int year, int month, int day) {
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw invalid(d, raw, e.getMessage());
        }
    }

    private static RuntimeException invalid(Descriptor d, Object value, String reason) {
        return MalformedValues.malformed(d, value, reason, null);
    }
}
