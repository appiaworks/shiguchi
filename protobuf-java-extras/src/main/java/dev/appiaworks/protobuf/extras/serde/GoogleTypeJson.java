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
 * <p>Fields are looked up by number ({@code findFieldByNumber} is an allocation-free search; a
 * by-name lookup builds a string per call) on the instance descriptor, which may come from gRPC
 * reflection rather than the compiled {@code com.google.type} classes. The numbers are those of
 * {@code google/type/*.proto}, which are frozen.
 *
 * <p>Text is built with {@link #appendPadded} (ASCII digits, independent of the default locale)
 * and read with index-based parsers: no {@code String.format}, no regular expressions on the
 * Date, TimeOfDay and common DateTime shapes.
 */
final class GoogleTypeJson {

    private GoogleTypeJson() {}

    // Field numbers from google/type/{date,timeofday,datetime,timezone,decimal,money}.proto.
    private static final int DATE_YEAR = 1;
    private static final int DATE_MONTH = 2;
    private static final int DATE_DAY = 3;

    private static final int TIME_HOURS = 1;
    private static final int TIME_MINUTES = 2;
    private static final int TIME_SECONDS = 3;
    private static final int TIME_NANOS = 4;

    private static final int DATETIME_YEAR = 1;
    private static final int DATETIME_MONTH = 2;
    private static final int DATETIME_DAY = 3;
    private static final int DATETIME_HOURS = 4;
    private static final int DATETIME_MINUTES = 5;
    private static final int DATETIME_SECONDS = 6;
    private static final int DATETIME_NANOS = 7;
    private static final int DATETIME_UTC_OFFSET = 8;
    private static final int DATETIME_TIME_ZONE = 9;

    private static final int TIMEZONE_ID = 1;

    private static final int DECIMAL_VALUE = 1;

    private static final int MONEY_CURRENCY_CODE = 1;
    private static final int MONEY_UNITS = 2;
    private static final int MONEY_NANOS = 3;

    // ── Write ────────────────────────────────────────────────────────────────────────

    /** The JSON value of a mapped message: a String, or a Map when the text form cannot carry it. */
    static Object toJson(DynamicMessage msg) {
        return switch (msg.getDescriptorForType().getFullName()) {
            case ProtoJsonMapping.DATE -> dateToJson(msg);
            case ProtoJsonMapping.TIME_OF_DAY -> timeOfDayToJson(msg);
            case ProtoJsonMapping.DATE_TIME -> dateTimeToJson(msg);
            case ProtoJsonMapping.DECIMAL -> string(msg, DECIMAL_VALUE);
            case ProtoJsonMapping.MONEY -> moneyToJson(msg);
            default -> throw new IllegalArgumentException("Not a mapped google.type: " + msg.getDescriptorForType());
        };
    }

    private static Object dateToJson(DynamicMessage msg) {
        int y = integer(msg, DATE_YEAR);
        int m = integer(msg, DATE_MONTH);
        int d = integer(msg, DATE_DAY);
        if (y > 0 && y <= 9999 && m > 0 && d > 0) {
            StringBuilder sb = new StringBuilder(10);
            appendDate(sb, y, m, d);
            return sb.toString();
        }
        if (y > 0 && y <= 9999 && m > 0) {
            StringBuilder sb = new StringBuilder(7);
            appendPadded(sb, y, 4).append('-');
            appendPadded(sb, m, 2);
            return sb.toString();
        }
        if (y > 0 && y <= 9999 && m == 0 && d == 0)
            return appendPadded(new StringBuilder(4), y, 4).toString();
        if (y == 0 && m > 0 && d > 0) {
            StringBuilder sb = new StringBuilder(7).append("--");
            appendPadded(sb, m, 2).append('-');
            appendPadded(sb, d, 2);
            return sb.toString();
        }
        return objectForm(msg);
    }

    private static Object timeOfDayToJson(DynamicMessage msg) {
        StringBuilder sb = new StringBuilder(18);
        appendTime(
                sb,
                integer(msg, TIME_HOURS),
                integer(msg, TIME_MINUTES),
                integer(msg, TIME_SECONDS),
                integer(msg, TIME_NANOS));
        return sb.toString();
    }

    private static Object dateTimeToJson(DynamicMessage msg) {
        Descriptor d = msg.getDescriptorForType();
        int y = integer(msg, DATETIME_YEAR);
        int mo = integer(msg, DATETIME_MONTH);
        int day = integer(msg, DATETIME_DAY);
        if (y <= 0 || y > 9999 || mo <= 0 || day <= 0) return objectForm(msg);
        StringBuilder local = new StringBuilder(48);
        appendDate(local, y, mo, day).append('T');
        appendTime(
                local,
                integer(msg, DATETIME_HOURS),
                integer(msg, DATETIME_MINUTES),
                integer(msg, DATETIME_SECONDS),
                integer(msg, DATETIME_NANOS));

        FieldDescriptor offsetFd = d.findFieldByNumber(DATETIME_UTC_OFFSET);
        FieldDescriptor zoneFd = d.findFieldByNumber(DATETIME_TIME_ZONE);
        if (offsetFd != null && msg.hasField(offsetFd)) {
            DynamicMessage offset = (DynamicMessage) msg.getField(offsetFd);
            long seconds =
                    ((Number) offset.getField(offset.getDescriptorForType().findFieldByNumber(1))).longValue();
            try {
                return local.append(ZoneOffset.ofTotalSeconds(Math.toIntExact(seconds))
                                .getId())
                        .toString();
            } catch (DateTimeException | ArithmeticException e) {
                return objectForm(msg);
            }
        }
        if (zoneFd != null && msg.hasField(zoneFd)) {
            DynamicMessage zone = (DynamicMessage) msg.getField(zoneFd);
            String id = (String) zone.getField(zone.getDescriptorForType().findFieldByNumber(TIMEZONE_ID));
            try {
                ZoneId zoneId = ZoneId.of(id);
                LocalDateTime ldt = LocalDateTime.of(
                        y,
                        mo,
                        day,
                        integer(msg, DATETIME_HOURS),
                        integer(msg, DATETIME_MINUTES),
                        Math.min(integer(msg, DATETIME_SECONDS), 59));
                ZoneOffset offset = zoneId.getRules().getOffset(ldt);
                return local.append(offset.getId())
                        .append('[')
                        .append(zoneId.getId())
                        .append(']')
                        .toString();
            } catch (DateTimeException e) {
                return objectForm(msg);
            }
        }
        return local.toString();
    }

    private static Object moneyToJson(DynamicMessage msg) {
        long units = ((Number) field(msg, MONEY_UNITS)).longValue();
        int nanos = integer(msg, MONEY_NANOS);
        BigDecimal amount = BigDecimal.valueOf(units).add(BigDecimal.valueOf(nanos, 9));
        Map<String, Object> out = new LinkedHashMap<>(4);
        out.put("currency", string(msg, MONEY_CURRENCY_CODE));
        out.put("amount", plain(amount));
        return out;
    }

    /** {@code YYYY-MM-DD}, the year padded to four digits. */
    private static StringBuilder appendDate(StringBuilder sb, int y, int m, int d) {
        appendPadded(sb, y, 4).append('-');
        appendPadded(sb, m, 2).append('-');
        return appendPadded(sb, d, 2);
    }

    /** {@code HH:mm:ss} plus a 3, 6 or 9 digit fraction when there are nanos, as Timestamp text does. */
    private static StringBuilder appendTime(StringBuilder sb, int h, int m, int s, int nanos) {
        appendPadded(sb, h, 2).append(':');
        appendPadded(sb, m, 2).append(':');
        appendPadded(sb, s, 2);
        if (nanos == 0) return sb;
        sb.append('.');
        if (nanos % 1_000_000 == 0) return appendPadded(sb, nanos / 1_000_000, 3);
        if (nanos % 1_000 == 0) return appendPadded(sb, nanos / 1_000, 6);
        return appendPadded(sb, nanos, 9);
    }

    /**
     * Appends {@code v} in decimal ASCII, left-padded with zeros to {@code width}: what
     * {@code %0<width>d} prints, minus the pattern parsing, the Formatter and the default
     * locale (which may print native digits). A negative or wider value keeps {@code %0Nd}'s
     * shape: the sign counts toward the width, digits are never cut.
     */
    static StringBuilder appendPadded(StringBuilder sb, int v, int width) {
        if (v < 0 || v >= POW10[width]) {
            String digits = Integer.toString(v);
            boolean negative = v < 0;
            if (negative) {
                sb.append('-');
                digits = digits.substring(1);
            }
            for (int i = digits.length() + (negative ? 1 : 0); i < width; i++) sb.append('0');
            return sb.append(digits);
        }
        for (int p = width - 1; p >= 0; p--) {
            int q = POW10[p];
            int digit = v / q;
            sb.append((char) ('0' + digit));
            v -= digit * q;
        }
        return sb;
    }

    private static final int[] POW10 = {
        1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000
    };

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
            int n = s.length();
            if (n == 10
                    && digits(s, 0, 4)
                    && s.charAt(4) == '-'
                    && digits(s, 5, 2)
                    && s.charAt(7) == '-'
                    && digits(s, 8, 2)) {
                y = number(s, 0, 4);
                m = number(s, 5, 2);
                day = number(s, 8, 2);
                // Year 0 is "no year" in google.type.Date, so check the calendar in a leap year.
                check(d, value, y == 0 ? 2000 : y, m, day);
            } else if (n == 7 && digits(s, 0, 4) && s.charAt(4) == '-' && digits(s, 5, 2)) {
                y = number(s, 0, 4);
                m = number(s, 5, 2);
                day = 0;
                check(d, value, 2000, m, 1);
            } else if (n == 4 && digits(s, 0, 4)) {
                y = number(s, 0, 4);
                m = 0;
                day = 0;
            } else if (n == 7
                    && s.charAt(0) == '-'
                    && s.charAt(1) == '-'
                    && digits(s, 2, 2)
                    && s.charAt(4) == '-'
                    && digits(s, 5, 2)) {
                y = 0;
                m = number(s, 2, 2);
                day = number(s, 5, 2);
                // 2000 is a leap year, so --02-29 (a birthday) is valid.
                check(d, value, 2000, m, day);
            } else {
                throw invalid(d, value, "expected YYYY-MM-DD, YYYY-MM, YYYY or --MM-DD");
            }
            if (y == 0 && !s.startsWith("--")) throw invalid(d, value, "year 0000 is not a valid year");
        }
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByNumber(DATE_YEAR), y)
                .setField(d.findFieldByNumber(DATE_MONTH), m)
                .setField(d.findFieldByNumber(DATE_DAY), day)
                .build();
    }

    private static DynamicMessage timeOfDayFromJava(Object value, Descriptor d) {
        int[] t = value instanceof LocalTime lt
                ? new int[] {lt.getHour(), lt.getMinute(), lt.getSecond(), lt.getNano()}
                : parseTime(text(value, d), d, value);
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByNumber(TIME_HOURS), t[0])
                .setField(d.findFieldByNumber(TIME_MINUTES), t[1])
                .setField(d.findFieldByNumber(TIME_SECONDS), t[2])
                .setField(d.findFieldByNumber(TIME_NANOS), t[3])
                .build();
    }

    /**
     * {@code HH:mm[:ss[.fraction]]}. Allows what TimeOfDay allows and {@code LocalTime} does not:
     * {@code 24:00:00} (end of day, e.g. closing time) and second 60 (a leap second).
     */
    private static int[] parseTime(String s, Descriptor d, Object raw) {
        int n = s.length();
        // HH:mm, then optionally :ss, then optionally . and 1-9 digits.
        boolean shape = n >= 5 && digits(s, 0, 2) && s.charAt(2) == ':' && digits(s, 3, 2);
        if (shape && n > 5) {
            shape = n >= 8 && s.charAt(5) == ':' && digits(s, 6, 2);
            if (shape && n > 8) shape = n >= 10 && n <= 18 && s.charAt(8) == '.' && digits(s, 9, n - 9);
        }
        if (!shape) throw invalid(d, raw, "expected HH:mm[:ss[.fraction]]");
        int h = number(s, 0, 2);
        int m = number(s, 3, 2);
        int sec = n >= 8 ? number(s, 6, 2) : 0;
        int nanos = 0;
        if (n > 9) {
            nanos = number(s, 9, n - 9);
            for (int i = n - 9; i < 9; i++) nanos *= 10;
        }
        boolean endOfDay = h == 24 && m == 0 && sec == 0 && nanos == 0;
        if (!endOfDay && (h > 23 || m > 59 || sec > 60)) throw invalid(d, raw, "time out of range");
        return new int[] {h, m, sec, nanos};
    }

    /** True when {@code len} chars from {@code from} are all ASCII digits (and len is at least 1). */
    private static boolean digits(String s, int from, int len) {
        if (len < 1) return false;
        for (int i = from, end = from + len; i < end; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** The value of {@code len} ASCII digits at {@code from}; {@link #digits} has checked them. */
    private static int number(String s, int from, int len) {
        int v = 0;
        for (int i = from, end = from + len; i < end; i++) v = v * 10 + (s.charAt(i) - '0');
        return v;
    }

    private static DynamicMessage dateTimeFromJava(Object value, Descriptor d) {
        TemporalAccessor parsed;
        if (value instanceof TemporalAccessor ta) {
            parsed = ta;
        } else {
            String s = text(value, d);
            DynamicMessage fast = fastDateTime(s, d);
            if (fast != null) return fast;
            try {
                parsed = DateTimeFormatter.ISO_DATE_TIME.parse(s);
            } catch (DateTimeParseException e) {
                throw invalid(d, value, "expected ISO-8601 date-time, e.g. 2026-09-22T14:03:11-04:00");
            }
        }
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d)
                .setField(d.findFieldByNumber(DATETIME_YEAR), parsed.get(ChronoField.YEAR))
                .setField(d.findFieldByNumber(DATETIME_MONTH), parsed.get(ChronoField.MONTH_OF_YEAR))
                .setField(d.findFieldByNumber(DATETIME_DAY), parsed.get(ChronoField.DAY_OF_MONTH))
                .setField(d.findFieldByNumber(DATETIME_HOURS), parsed.get(ChronoField.HOUR_OF_DAY))
                .setField(d.findFieldByNumber(DATETIME_MINUTES), parsed.get(ChronoField.MINUTE_OF_HOUR))
                .setField(d.findFieldByNumber(DATETIME_SECONDS), parsed.get(ChronoField.SECOND_OF_MINUTE))
                .setField(d.findFieldByNumber(DATETIME_NANOS), parsed.get(ChronoField.NANO_OF_SECOND));

        ZoneId zone = parsed.query(TemporalQueries.zoneId());
        ZoneOffset offset = parsed.query(TemporalQueries.offset());
        if (zone != null && !(zone instanceof ZoneOffset)) {
            FieldDescriptor zoneFd = d.findFieldByNumber(DATETIME_TIME_ZONE);
            Descriptor zd = zoneFd.getMessageType();
            b.setField(
                    zoneFd,
                    DynamicMessage.newBuilder(zd)
                            .setField(zd.findFieldByNumber(TIMEZONE_ID), zone.getId())
                            .build());
        } else if (offset != null) {
            FieldDescriptor offsetFd = d.findFieldByNumber(DATETIME_UTC_OFFSET);
            Descriptor od = offsetFd.getMessageType();
            b.setField(
                    offsetFd,
                    DynamicMessage.newBuilder(od)
                            .setField(od.findFieldByNumber(1), (long) offset.getTotalSeconds())
                            .build());
        }
        return b.build();
    }

    /**
     * The strict common shape {@code YYYY-MM-DDTHH:mm[:ss[.f{1,9}]]} followed by nothing,
     * {@code Z} or {@code ±HH:MM}, parsed without {@link DateTimeFormatter}. Returns null,
     * leaving the verdict (accept or reject) to the formatter, for anything else and for any
     * field the fast path is not certain about: year 0000, an impossible calendar date, second
     * 60, an offset beyond ±17:59 or with minutes over 59.
     */
    private static DynamicMessage fastDateTime(String s, Descriptor d) {
        int n = s.length();
        if (n < 16 || !digits(s, 0, 4) || s.charAt(4) != '-' || !digits(s, 5, 2) || s.charAt(7) != '-') return null;
        if (!digits(s, 8, 2) || s.charAt(10) != 'T' || !digits(s, 11, 2) || s.charAt(13) != ':' || !digits(s, 14, 2)) {
            return null;
        }
        int y = number(s, 0, 4);
        int mo = number(s, 5, 2);
        int day = number(s, 8, 2);
        int h = number(s, 11, 2);
        int mi = number(s, 14, 2);
        if (y == 0 || mo < 1 || mo > 12 || day < 1 || h > 23 || mi > 59) return null;
        if (day > 28 && day > java.time.Month.of(mo).length(java.time.Year.isLeap(y))) return null;
        int i = 16;
        int sec = 0;
        int nanos = 0;
        if (i < n && s.charAt(i) == ':') {
            if (!digits(s, i + 1, 2)) return null;
            sec = number(s, i + 1, 2);
            if (sec > 59) return null;
            i += 3;
            if (i < n && s.charAt(i) == '.') {
                int fractionDigits = 0;
                while (i + 1 + fractionDigits < n
                        && s.charAt(i + 1 + fractionDigits) >= '0'
                        && s.charAt(i + 1 + fractionDigits) <= '9') {
                    fractionDigits++;
                }
                if (fractionDigits < 1 || fractionDigits > 9) return null;
                nanos = number(s, i + 1, fractionDigits);
                for (int k = fractionDigits; k < 9; k++) nanos *= 10;
                i += 1 + fractionDigits;
            }
        }
        Long offsetSeconds = null;
        if (i == n - 1 && s.charAt(i) == 'Z') {
            offsetSeconds = 0L;
        } else if (i == n - 6 && (s.charAt(i) == '+' || s.charAt(i) == '-') && s.charAt(i + 3) == ':') {
            if (!digits(s, i + 1, 2) || !digits(s, i + 4, 2)) return null;
            int oh = number(s, i + 1, 2);
            int om = number(s, i + 4, 2);
            if (oh > 17 || om > 59) return null;
            int total = oh * 3600 + om * 60;
            offsetSeconds = (long) (s.charAt(i) == '-' ? -total : total);
        } else if (i != n) {
            return null;
        }
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d)
                .setField(d.findFieldByNumber(DATETIME_YEAR), y)
                .setField(d.findFieldByNumber(DATETIME_MONTH), mo)
                .setField(d.findFieldByNumber(DATETIME_DAY), day)
                .setField(d.findFieldByNumber(DATETIME_HOURS), h)
                .setField(d.findFieldByNumber(DATETIME_MINUTES), mi)
                .setField(d.findFieldByNumber(DATETIME_SECONDS), sec)
                .setField(d.findFieldByNumber(DATETIME_NANOS), nanos);
        if (offsetSeconds != null) {
            FieldDescriptor offsetFd = d.findFieldByNumber(DATETIME_UTC_OFFSET);
            Descriptor od = offsetFd.getMessageType();
            b.setField(
                    offsetFd,
                    DynamicMessage.newBuilder(od)
                            .setField(od.findFieldByNumber(1), offsetSeconds)
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
                .setField(d.findFieldByNumber(DECIMAL_VALUE), s)
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
                .setField(d.findFieldByNumber(MONEY_CURRENCY_CODE), currency == null ? "" : String.valueOf(currency))
                .setField(d.findFieldByNumber(MONEY_UNITS), units)
                .setField(d.findFieldByNumber(MONEY_NANOS), nanos)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    private static Object field(DynamicMessage msg, int number) {
        return msg.getField(msg.getDescriptorForType().findFieldByNumber(number));
    }

    private static int integer(DynamicMessage msg, int number) {
        return ((Number) field(msg, number)).intValue();
    }

    private static String string(DynamicMessage msg, int number) {
        return (String) field(msg, number);
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
