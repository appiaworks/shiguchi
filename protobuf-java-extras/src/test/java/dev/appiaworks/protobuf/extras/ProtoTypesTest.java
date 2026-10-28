package dev.appiaworks.protobuf.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.type.Date;
import com.google.type.DateTime;
import com.google.type.DayOfWeek;
import com.google.type.Decimal;
import com.google.type.Money;
import com.google.type.TimeOfDay;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProtoTypesTest {

    // ────────────────────────────────────────────────────────────────────────────
    // Timestamp conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Timestamp with seconds and nanos, when toInstant, should round-trip with nanos preserved")
    void given_timestampWithNanos_when_toInstant_should_roundTripWithNanos() {
        Instant instant = Instant.ofEpochSecond(1000000000L, 500000000);
        Timestamp timestamp = ProtoTypes.timestamp(instant);

        Instant result = ProtoTypes.toInstant(timestamp);

        assertThat(result).isEqualTo(instant);
        assertThat(result.getNano()).isEqualTo(500000000);
    }

    @Test
    @DisplayName("Given Timestamp, when toOffsetDateTime, should return UTC datetime")
    void given_timestamp_when_toOffsetDateTime_should_returnUtc() {
        Instant instant = Instant.ofEpochSecond(1000000000L, 123456789);
        Timestamp timestamp = Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();

        OffsetDateTime result = ProtoTypes.toOffsetDateTime(timestamp);

        assertThat(result.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(result.toInstant()).isEqualTo(instant);
    }

    @Test
    @DisplayName("Given OffsetDateTime with non-UTC offset, when timestamp, should preserve instant")
    void given_offsetDateTimeWithNonUtcOffset_when_timestamp_should_preserveInstant() {
        OffsetDateTime dt = OffsetDateTime.of(2026, 10, 2, 14, 30, 45, 123456789, ZoneOffset.ofHours(-4));
        Instant instant = dt.toInstant();

        Timestamp timestamp = ProtoTypes.timestamp(dt);
        Instant result = ProtoTypes.toInstant(timestamp);

        assertThat(result).isEqualTo(instant);
    }

    @Test
    @DisplayName("Given Instant.MAX, when timestamp, should throw IllegalArgumentException")
    void given_instantMax_when_timestamp_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ProtoTypes.timestamp(Instant.MAX)).isInstanceOf(IllegalArgumentException.class);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Duration conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given positive Duration, when roundtrip, should preserve value")
    void given_positiveDuration_when_roundtrip_should_preserveValue() {
        Duration javaDuration = Duration.ofSeconds(1000, 500000000);
        com.google.protobuf.Duration protoDuration = ProtoTypes.duration(javaDuration);

        Duration result = ProtoTypes.toDuration(protoDuration);

        assertThat(result).isEqualTo(javaDuration);
    }

    @Test
    @DisplayName("Given negative Duration with fraction, when duration, should split seconds and nanos correctly")
    void given_negativeDurationWithFraction_when_duration_should_splitSecondsAndNanos() {
        Duration javaDuration = Duration.ofMillis(-1500);
        com.google.protobuf.Duration protoDuration = ProtoTypes.duration(javaDuration);

        assertThat(protoDuration.getSeconds()).isEqualTo(-1);
        assertThat(protoDuration.getNanos()).isEqualTo(-500000000);
    }

    @Test
    @DisplayName("Given negative Duration roundtrip, when toDuration, should restore value")
    void given_negativeDurationRoundtrip_when_toDuration_should_restoreValue() {
        Duration javaDuration = Duration.ofMillis(-1500);
        com.google.protobuf.Duration protoDuration = ProtoTypes.duration(javaDuration);

        Duration result = ProtoTypes.toDuration(protoDuration);

        assertThat(result).isEqualTo(javaDuration);
    }

    @Test
    @DisplayName(
            "Given invalid proto Duration with negative nanos, when toDuration, should throw IllegalArgumentException")
    void given_invalidProtoDurationNegativeNanos_when_toDuration_should_throwIllegalArgumentException() {
        com.google.protobuf.Duration invalid = com.google.protobuf.Duration.newBuilder()
                .setSeconds(1)
                .setNanos(-1)
                .build();

        assertThatThrownBy(() -> ProtoTypes.toDuration(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Date conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given LocalDate, when date roundtrip, should preserve full date")
    void given_localDate_when_dateRoundtrip_should_preserveFullDate() {
        LocalDate local = LocalDate.of(2026, 9, 22);
        Date protoDate = ProtoTypes.date(local);

        LocalDate result = ProtoTypes.toLocalDate(protoDate);

        assertThat(result).isEqualTo(local);
    }

    @Test
    @DisplayName("Given Date with day=0, when toLocalDate, should throw IllegalArgumentException")
    void given_dateWithZeroDay_when_toLocalDate_should_throwIllegalArgumentException() {
        Date date = Date.newBuilder().setYear(2026).setMonth(9).build();

        assertThatThrownBy(() -> ProtoTypes.toLocalDate(date))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a full date");
    }

    @Test
    @DisplayName("Given invalid calendar date 2026-02-30, when toLocalDate, should throw IllegalArgumentException")
    void given_invalidCalendarDate_when_toLocalDate_should_throwIllegalArgumentException() {
        Date date = Date.newBuilder().setYear(2026).setMonth(2).setDay(30).build();

        assertThatThrownBy(() -> ProtoTypes.toLocalDate(date))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a calendar date");
    }

    @Test
    @DisplayName("Given LocalDate, when toTemporal, should return LocalDate instance with correct value")
    void given_localDate_when_toTemporal_should_returnLocalDate() {
        Date date = Date.newBuilder().setYear(2026).setMonth(9).setDay(22).build();

        TemporalAccessor result = ProtoTypes.toTemporal(date);

        assertThat(result).isInstanceOf(LocalDate.class).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    @DisplayName("Given Date with year and month only, when toTemporal, should return YearMonth")
    void given_dateYearMonthOnly_when_toTemporal_should_returnYearMonth() {
        Date date = Date.newBuilder().setYear(2026).setMonth(9).build();

        TemporalAccessor result = ProtoTypes.toTemporal(date);

        assertThat(result).isInstanceOf(YearMonth.class).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    @DisplayName("Given Date with year only, when toTemporal, should return Year")
    void given_dateYearOnly_when_toTemporal_should_returnYear() {
        Date date = Date.newBuilder().setYear(2026).build();

        TemporalAccessor result = ProtoTypes.toTemporal(date);

        assertThat(result).isInstanceOf(Year.class).isEqualTo(Year.of(2026));
    }

    @Test
    @DisplayName("Given Date with month and day only, when toTemporal, should return MonthDay")
    void given_dateMonthDayOnly_when_toTemporal_should_returnMonthDay() {
        Date date = Date.newBuilder().setMonth(9).setDay(22).build();

        TemporalAccessor result = ProtoTypes.toTemporal(date);

        assertThat(result).isInstanceOf(MonthDay.class).isEqualTo(MonthDay.of(9, 22));
    }

    @Test
    @DisplayName("Given Date with year and day without month, when toTemporal, should throw IllegalArgumentException")
    void given_dateYearDayWithoutMonth_when_toTemporal_should_throwIllegalArgumentException() {
        Date date = Date.newBuilder().setYear(2026).setDay(22).build();

        assertThatThrownBy(() -> ProtoTypes.toTemporal(date))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no usable form");
    }

    @Test
    @DisplayName("Given YearMonth, when date, should leave day as 0")
    void given_yearMonth_when_date_should_leaveDayAsZero() {
        YearMonth ym = YearMonth.of(2026, 9);

        Date result = ProtoTypes.date(ym);

        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getMonth()).isEqualTo(9);
        assertThat(result.getDay()).isEqualTo(0);
    }

    @Test
    @DisplayName("Given Year, when date, should leave month and day as 0")
    void given_year_when_date_should_leaveMonthAndDayAsZero() {
        Year year = Year.of(2026);

        Date result = ProtoTypes.date(year);

        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getMonth()).isEqualTo(0);
        assertThat(result.getDay()).isEqualTo(0);
    }

    @Test
    @DisplayName("Given MonthDay, when date, should leave year as 0")
    void given_monthDay_when_date_should_leaveYearAsZero() {
        MonthDay md = MonthDay.of(9, 22);

        Date result = ProtoTypes.date(md);

        assertThat(result.getYear()).isEqualTo(0);
        assertThat(result.getMonth()).isEqualTo(9);
        assertThat(result.getDay()).isEqualTo(22);
    }

    @Test
    @DisplayName("Given LocalDate with year 10000, when date, should throw IllegalArgumentException")
    void given_localDateYear10000_when_date_should_throwIllegalArgumentException() {
        LocalDate local = LocalDate.of(10000, 1, 1);

        assertThatThrownBy(() -> ProtoTypes.date(local))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside 1 to 9999");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // TimeOfDay conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given LocalTime, when timeOfDay roundtrip, should preserve value with nanos")
    void given_localTime_when_timeOfDayRoundtrip_should_preserveWithNanos() {
        LocalTime local = LocalTime.of(14, 30, 45, 123456789);
        TimeOfDay tod = ProtoTypes.timeOfDay(local);

        LocalTime result = ProtoTypes.toLocalTime(tod);

        assertThat(result).isEqualTo(local);
    }

    @Test
    @DisplayName("Given TimeOfDay with hours=24, when toLocalTime, should throw IllegalArgumentException")
    void given_timeOfDayHours24_when_toLocalTime_should_throwIllegalArgumentException() {
        TimeOfDay tod = TimeOfDay.newBuilder().setHours(24).build();

        assertThatThrownBy(() -> ProtoTypes.toLocalTime(tod))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit a LocalTime");
    }

    @Test
    @DisplayName("Given TimeOfDay with seconds=60, when toLocalTime, should throw IllegalArgumentException")
    void given_timeOfDaySeconds60_when_toLocalTime_should_throwIllegalArgumentException() {
        TimeOfDay tod = TimeOfDay.newBuilder().setSeconds(60).build();

        assertThatThrownBy(() -> ProtoTypes.toLocalTime(tod))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit a LocalTime");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // DateTime conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given LocalDateTime, when dateTime roundtrip, should have TIMEOFFSET_NOT_SET and match value")
    void given_localDateTime_when_dateTimeRoundtrip_should_haveNoOffsetAndRoundtrip() {
        LocalDateTime local = LocalDateTime.of(2026, 9, 22, 14, 30, 45, 123456789);
        DateTime dateTime = ProtoTypes.dateTime(local);

        assertThat(dateTime.getTimeOffsetCase().getNumber()).isEqualTo(0); // TIMEOFFSET_NOT_SET

        LocalDateTime result = ProtoTypes.toLocalDateTime(dateTime);

        assertThat(result).isEqualTo(local);
    }

    @Test
    @DisplayName("Given OffsetDateTime with -04:00, when dateTime, should set utc_offset seconds -14400")
    void given_offsetDateTimeWithOffset_when_dateTime_should_setUtcOffset() {
        OffsetDateTime odt = OffsetDateTime.of(2026, 9, 22, 14, 30, 45, 123456789, ZoneOffset.ofHours(-4));
        DateTime dateTime = ProtoTypes.dateTime(odt);

        assertThat(dateTime.getUtcOffset().getSeconds()).isEqualTo(-14400);
    }

    @Test
    @DisplayName("Given OffsetDateTime with offset, when dateTime roundtrip, should preserve as OffsetDateTime")
    void given_offsetDateTimeWithOffset_when_dateTimeRoundtrip_should_preserveValue() {
        OffsetDateTime odt = OffsetDateTime.of(2026, 9, 22, 14, 30, 45, 123456789, ZoneOffset.ofHours(-4));
        DateTime dateTime = ProtoTypes.dateTime(odt);

        OffsetDateTime result = ProtoTypes.toOffsetDateTime(dateTime);

        assertThat(result).isEqualTo(odt);
    }

    @Test
    @DisplayName("Given ZonedDateTime in America/New_York, when dateTime, should set time_zone id")
    void given_zonedDateTimeWithZone_when_dateTime_should_setTimeZoneId() {
        ZonedDateTime zdt = ZonedDateTime.of(2026, 9, 22, 14, 30, 45, 123456789, ZoneId.of("America/New_York"));
        DateTime dateTime = ProtoTypes.dateTime(zdt);

        assertThat(dateTime.getTimeZone().getId()).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("Given ZonedDateTime in America/New_York, when dateTime roundtrip, should preserve as ZonedDateTime")
    void given_zonedDateTimeWithZone_when_dateTimeRoundtrip_should_preserveValue() {
        ZonedDateTime zdt = ZonedDateTime.of(2026, 9, 22, 14, 30, 45, 123456789, ZoneId.of("America/New_York"));
        DateTime dateTime = ProtoTypes.dateTime(zdt);

        ZonedDateTime result = ProtoTypes.toZonedDateTime(dateTime);

        assertThat(result).isEqualTo(zdt);
    }

    @Test
    @DisplayName("Given ZonedDateTime with ZoneOffset, when dateTime, should use utc_offset")
    void given_zonedDateTimeWithZoneOffset_when_dateTime_should_useUtcOffset() {
        ZonedDateTime zdt = ZonedDateTime.of(2026, 9, 22, 14, 30, 45, 123456789, ZoneOffset.ofHours(2));
        DateTime dateTime = ProtoTypes.dateTime(zdt);

        assertThat(dateTime.getUtcOffset().getSeconds()).isEqualTo(7200);
    }

    @Test
    @DisplayName("Given DateTime with no offset or zone, when toOffsetDateTime, should throw IllegalArgumentException")
    void given_dateTimeNoOffsetOrZone_when_toOffsetDateTime_should_throwIllegalArgumentException() {
        DateTime dateTime = DateTime.newBuilder()
                .setYear(2026)
                .setMonth(9)
                .setDay(22)
                .setHours(14)
                .setMinutes(30)
                .build();

        assertThatThrownBy(() -> ProtoTypes.toOffsetDateTime(dateTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no UTC offset or time zone");
    }

    @Test
    @DisplayName(
            "Given DateTime with unknown zone id Mars/Olympus, when toZonedDateTime, should throw IllegalArgumentException")
    void given_dateTimeUnknownZoneId_when_toZonedDateTime_should_throwIllegalArgumentException() {
        DateTime dateTime = DateTime.newBuilder()
                .setYear(2026)
                .setMonth(9)
                .setDay(22)
                .setHours(14)
                .setMinutes(30)
                .setTimeZone(com.google.type.TimeZone.newBuilder().setId("Mars/Olympus"))
                .build();

        assertThatThrownBy(() -> ProtoTypes.toZonedDateTime(dateTime)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given DateTime with year 0, when toLocalDateTime, should throw IllegalArgumentException")
    void given_dateTimeYearZero_when_toLocalDateTime_should_throwIllegalArgumentException() {
        DateTime dateTime = DateTime.newBuilder()
                .setMonth(9)
                .setDay(22)
                .setHours(14)
                .setMinutes(30)
                .build();

        assertThatThrownBy(() -> ProtoTypes.toLocalDateTime(dateTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no year");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Decimal conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Decimal \"12.30\", when toBigDecimal, should return BigDecimal preserving scale")
    void given_decimalString_when_toBigDecimal_should_preserveScale() {
        Decimal decimal = Decimal.newBuilder().setValue("12.30").build();

        BigDecimal result = ProtoTypes.toBigDecimal(decimal);

        assertThat(result).isEqualByComparingTo(new BigDecimal("12.30"));
        assertThat(result.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Given Decimal \"12.30\" roundtrip, when decimal, should preserve string form")
    void given_bigDecimal12_30_when_decimalRoundtrip_should_preserveStringForm() {
        BigDecimal bd = new BigDecimal("12.30");
        Decimal decimal = ProtoTypes.decimal(bd);

        String result = decimal.getValue();

        assertThat(result).isEqualTo("12.30");
    }

    @Test
    @DisplayName("Given empty Decimal value, when toBigDecimal, should return zero")
    void given_emptyDecimal_when_toBigDecimal_should_returnZero() {
        Decimal decimal = Decimal.getDefaultInstance();

        BigDecimal result = ProtoTypes.toBigDecimal(decimal);

        assertThat(result).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Given Decimal \"abc\", when toBigDecimal, should throw IllegalArgumentException")
    void given_decimalNonNumeric_when_toBigDecimal_should_throwIllegalArgumentException() {
        Decimal decimal = Decimal.newBuilder().setValue("abc").build();

        assertThatThrownBy(() -> ProtoTypes.toBigDecimal(decimal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a decimal number");
    }

    @Test
    @DisplayName("Given BigDecimal \"1E+3\", when decimal, should write \"1000\"")
    void given_bigDecimalScientificNotation_when_decimal_should_writePlainNotation() {
        BigDecimal bd = new BigDecimal("1E+3");
        Decimal decimal = ProtoTypes.decimal(bd);

        assertThat(decimal.getValue()).isEqualTo("1000");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Money conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Money units=12 nanos=340000000 USD, when toMoneyValue, should return 12.34")
    void given_moneyPositive_when_toMoneyValue_should_returnCorrectAmount() {
        Money money = Money.newBuilder()
                .setCurrencyCode("USD")
                .setUnits(12)
                .setNanos(340000000)
                .build();

        MoneyValue result = ProtoTypes.toMoneyValue(money);

        assertThat(result.currencyCode()).isEqualTo("USD");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("12.34"));
    }

    @Test
    @DisplayName("Given MoneyValue with 12.34 USD, when money roundtrip, should preserve amount")
    void given_moneyValuePositive_when_moneyRoundtrip_should_preserveAmount() {
        MoneyValue mv = new MoneyValue("USD", new BigDecimal("12.34"));
        Money money = ProtoTypes.money(mv);

        MoneyValue result = ProtoTypes.toMoneyValue(money);

        assertThat(result.currencyCode()).isEqualTo("USD");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("12.34"));
    }

    @Test
    @DisplayName("Given Money units=-12 nanos=-340000000, when money roundtrip, should preserve negative amount")
    void given_moneyNegative_when_moneyRoundtrip_should_preserveValue() {
        MoneyValue mv = new MoneyValue("USD", new BigDecimal("-12.34"));
        Money money = ProtoTypes.money(mv);

        assertThat(money.getUnits()).isEqualTo(-12);
        assertThat(money.getNanos()).isEqualTo(-340000000);

        MoneyValue result = ProtoTypes.toMoneyValue(money);

        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("-12.34"));
    }

    @Test
    @DisplayName("Given Money units=1 nanos=-1, when toMoneyValue, should throw IllegalArgumentException")
    void given_moneyMismatchedSign_when_toMoneyValue_should_throwIllegalArgumentException() {
        Money money = Money.newBuilder().setUnits(1).setNanos(-1).build();

        assertThatThrownBy(() -> ProtoTypes.toMoneyValue(money))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ in sign");
    }

    @Test
    @DisplayName("Given Money nanos=1000000000, when toMoneyValue, should throw IllegalArgumentException")
    void given_moneyNanosOutOfRange_when_toMoneyValue_should_throwIllegalArgumentException() {
        Money money = Money.newBuilder().setNanos(1000000000).build();

        assertThatThrownBy(() -> ProtoTypes.toMoneyValue(money))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanos out of range");
    }

    @Test
    @DisplayName("Given MoneyValue exceeding 64-bit range, when money, should throw IllegalArgumentException")
    void given_moneyValueExceedingRange_when_money_should_throwIllegalArgumentException() {
        MoneyValue mv = new MoneyValue("USD", new BigDecimal("9223372036854775808.123456789"));

        assertThatThrownBy(() -> ProtoTypes.money(mv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not fit google.type.Money");
    }

    @Test
    @DisplayName(
            "Given an amount with a tenth fractional digit, when money, should throw IllegalArgumentException rather than round")
    void given_amountWithTenFractionalDigits_when_money_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ProtoTypes.money(new MoneyValue("USD", new BigDecimal("12.3456789012"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 9 fractional digits");
        assertThatThrownBy(() -> ProtoTypes.money(new MoneyValue("USD", new BigDecimal("0.0000000001"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given an amount with nine fractional digits, when money, should keep every digit in nanos")
    void given_amountWithNineFractionalDigits_when_money_should_keepEveryDigit() {
        Money money = ProtoTypes.money(new MoneyValue("USD", new BigDecimal("-0.000000001")));

        assertThat(money.getUnits()).isZero();
        assertThat(money.getNanos()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Given whole amount 5, when money roundtrip, should have scale 0")
    void given_wholeAmount_when_moneyRoundtrip_should_haveScaleZero() {
        MoneyValue mv = new MoneyValue("USD", new BigDecimal("5"));
        Money money = ProtoTypes.money(mv);
        MoneyValue result = ProtoTypes.toMoneyValue(money);

        assertThat(result.amount().scale()).isEqualTo(0);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // DayOfWeek and Month conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given MONDAY DayOfWeek, when dayOfWeek roundtrip, should preserve value")
    void given_dayOfWeekMonday_when_dayOfWeekRoundtrip_should_preserveValue() {
        java.time.DayOfWeek dow = java.time.DayOfWeek.MONDAY;
        DayOfWeek protoDow = ProtoTypes.dayOfWeek(dow);

        java.time.DayOfWeek result = ProtoTypes.toDayOfWeek(protoDow);

        assertThat(result).isEqualTo(java.time.DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("Given SUNDAY DayOfWeek, when dayOfWeek roundtrip, should preserve value")
    void given_dayOfWeekSunday_when_dayOfWeekRoundtrip_should_preserveValue() {
        java.time.DayOfWeek dow = java.time.DayOfWeek.SUNDAY;
        DayOfWeek protoDow = ProtoTypes.dayOfWeek(dow);

        java.time.DayOfWeek result = ProtoTypes.toDayOfWeek(protoDow);

        assertThat(result).isEqualTo(java.time.DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("Given DAY_OF_WEEK_UNSPECIFIED, when toDayOfWeek, should return null")
    void given_dayOfWeekUnspecified_when_toDayOfWeek_should_returnNull() {
        DayOfWeek proto = DayOfWeek.DAY_OF_WEEK_UNSPECIFIED;

        java.time.DayOfWeek result = ProtoTypes.toDayOfWeek(proto);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Given null DayOfWeek, when dayOfWeek, should return DAY_OF_WEEK_UNSPECIFIED")
    void given_nullDayOfWeek_when_dayOfWeek_should_returnUnspecified() {
        DayOfWeek result = ProtoTypes.dayOfWeek(null);

        assertThat(result).isEqualTo(DayOfWeek.DAY_OF_WEEK_UNSPECIFIED);
    }

    @Test
    @DisplayName("Given JANUARY Month, when month roundtrip, should preserve value")
    void given_monthJanuary_when_monthRoundtrip_should_preserveValue() {
        Month month = Month.JANUARY;
        com.google.type.Month protoMonth = ProtoTypes.month(month);

        Month result = ProtoTypes.toMonth(protoMonth);

        assertThat(result).isEqualTo(Month.JANUARY);
    }

    @Test
    @DisplayName("Given DECEMBER Month, when month roundtrip, should preserve value")
    void given_monthDecember_when_monthRoundtrip_should_preserveValue() {
        Month month = Month.DECEMBER;
        com.google.type.Month protoMonth = ProtoTypes.month(month);

        Month result = ProtoTypes.toMonth(protoMonth);

        assertThat(result).isEqualTo(Month.DECEMBER);
    }

    @Test
    @DisplayName("Given MONTH_UNSPECIFIED, when toMonth, should return null")
    void given_monthUnspecified_when_toMonth_should_returnNull() {
        com.google.type.Month proto = com.google.type.Month.MONTH_UNSPECIFIED;

        Month result = ProtoTypes.toMonth(proto);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Given null Month, when month, should return MONTH_UNSPECIFIED")
    void given_nullMonth_when_month_should_returnUnspecified() {
        com.google.type.Month result = ProtoTypes.month(null);

        assertThat(result).isEqualTo(com.google.type.Month.MONTH_UNSPECIFIED);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Wrapper conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given null Int32Value, when unwrap, should return null")
    void given_nullInt32Value_when_unwrap_should_returnNull() {
        Integer result = ProtoTypes.unwrap((Int32Value) null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Given Int32Value.of(7), when unwrap, should return 7")
    void given_int32Value7_when_unwrap_should_return7() {
        Integer result = ProtoTypes.unwrap(Int32Value.of(7));

        assertThat(result).isEqualTo(7);
    }

    @Test
    @DisplayName("Given UInt32Value.of(-1), when unwrap, should return 4294967295L")
    void given_uint32ValueMaxUnsigned_when_unwrap_should_returnMaxUnsignedValue() {
        Long result = ProtoTypes.unwrap(UInt32Value.of(-1));

        assertThat(result).isEqualTo(4294967295L);
    }

    @Test
    @DisplayName("Given UInt64Value.of(-1L), when unwrap, should return BigInteger 18446744073709551615")
    void given_uint64ValueMaxUnsigned_when_unwrap_should_returnMaxUnsignedBigInteger() {
        BigInteger result = ProtoTypes.unwrap(UInt64Value.of(-1L));

        assertThat(result).isEqualTo(new BigInteger("18446744073709551615"));
    }

    @Test
    @DisplayName("Given null Integer, when wrap, should return null")
    void given_nullInteger_when_wrap_should_returnNull() {
        Int32Value result = ProtoTypes.wrap((Integer) null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Given string \"x\", when wrap, should return StringValue \"x\"")
    void given_stringX_when_wrap_should_returnStringValueX() {
        StringValue result = ProtoTypes.wrap("x");

        assertThat(result.getValue()).isEqualTo("x");
    }

    @Test
    @DisplayName("Given uint32Value(4294967295L), when roundtrip, should preserve value")
    void given_uint32ValueMax_when_roundtrip_should_preserveValue() {
        UInt32Value wrapper = ProtoTypes.uint32Value(4294967295L);

        Long result = ProtoTypes.unwrap(wrapper);

        assertThat(result).isEqualTo(4294967295L);
    }

    @Test
    @DisplayName("Given uint32Value(-1), when call, should throw IllegalArgumentException")
    void given_uint32ValueNegative_when_call_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ProtoTypes.uint32Value(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned 32-bit integer");
    }

    @Test
    @DisplayName("Given uint64Value(2^64), when call, should throw IllegalArgumentException")
    void given_uint64ValueOverflow_when_call_should_throwIllegalArgumentException() {
        BigInteger twoTo64 = BigInteger.ONE.shiftLeft(64);

        assertThatThrownBy(() -> ProtoTypes.uint64Value(twoTo64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned 64-bit integer");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Struct, Value, ListValue conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given nested Map with mixed types, when struct roundtrip, should return equivalent map with Double numbers")
    void given_nestedMap_when_structRoundtrip_should_returnEquivalentMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", "x");
        map.put("c", true);
        map.put("d", null);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("f", Arrays.asList(1, "y"));
        map.put("e", nested);

        Struct struct = ProtoTypes.struct(map);
        Map<String, Object> result = ProtoTypes.toMap(struct);

        assertThat(result).containsEntry("a", 1.0);
        assertThat(result).containsEntry("b", "x");
        assertThat(result).containsEntry("c", true);
        assertThat(result).containsEntry("d", null);
        assertThat((Map<String, Object>) result.get("e")).containsEntry("f", Arrays.asList(1.0, "y"));
    }

    @Test
    @DisplayName("Given new Object(), when value, should throw IllegalArgumentException")
    void given_unknownObjectType_when_value_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> ProtoTypes.value(new Object()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No google.protobuf.Value form");
    }

    @Test
    @DisplayName("Given nested Map with non-String key in a value, when value, should throw IllegalArgumentException")
    void given_nestedMapWithNonStringKey_when_value_should_throwIllegalArgumentException() {
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedMap = (Map<String, Object>) (Map<?, ?>) new HashMap<Integer, Object>() {
            {
                put(1, "value");
            }
        };
        Map<String, Object> outerMap = new LinkedHashMap<>();
        outerMap.put("nested", nestedMap);

        assertThatThrownBy(() -> ProtoTypes.value(outerMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key must be a String");
    }

    @Test
    @DisplayName("Given Value.getDefaultInstance(), when toObject, should return null")
    void given_defaultValue_when_toObject_should_returnNull() {
        Object result = ProtoTypes.toObject(Value.getDefaultInstance());

        assertThat(result).isNull();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // FieldMask conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given FieldMask with paths, when roundtrip via toPaths, should preserve paths")
    void given_fieldMaskWithPaths_when_roundtrip_should_preservePaths() {
        List<String> paths = Arrays.asList("field1", "nested.field2", "field3");
        FieldMask mask = ProtoTypes.fieldMask(paths);

        List<String> result = ProtoTypes.toPaths(mask);

        assertThat(result).isEqualTo(paths);
    }

    @Test
    @DisplayName("Given toPaths result, when modify, should throw UnsupportedOperationException")
    void given_toPathsResult_when_modify_should_throwUnsupportedOperationException() {
        FieldMask mask = ProtoTypes.fieldMask(Arrays.asList("field1"));

        List<String> result = ProtoTypes.toPaths(mask);

        assertThatThrownBy(() -> result.add("field2")).isInstanceOf(UnsupportedOperationException.class);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Dynamic message and as/dynamic conversions
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given DynamicMessage built on Date descriptor, when as with Date prototype, should return generated Date")
    void given_dynamicDate_when_asWithDatePrototype_should_returnGeneratedDate()
            throws com.google.protobuf.InvalidProtocolBufferException {
        Date original = Date.newBuilder().setYear(2026).setMonth(9).setDay(22).build();
        DynamicMessage dynamic = DynamicMessage.parseFrom(Date.getDescriptor(), original.toByteString());

        Date result = ProtoTypes.as(dynamic, Date.getDefaultInstance());

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("Given generated Date instance, when as with Date prototype, should return same instance")
    void given_generatedDate_when_asWithDatePrototype_should_returnSameInstance() {
        Date original = Date.newBuilder().setYear(2026).setMonth(9).setDay(22).build();

        Date result = ProtoTypes.as(original, Date.getDefaultInstance());

        assertThat(result).isSameAs(original);
    }

    @Test
    @DisplayName("Given Money message with Date prototype, when as, should throw IllegalArgumentException")
    void given_moneyMessage_when_asWithDatePrototype_should_throwIllegalArgumentException() {
        Money money = Money.newBuilder().setCurrencyCode("USD").build();

        assertThatThrownBy(() -> ProtoTypes.as(money, Date.getDefaultInstance()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected a");
    }

    @Test
    @DisplayName("Given Date message and Date descriptor, when dynamic, should return DynamicMessage with same bytes")
    void given_dateAndDateDescriptor_when_dynamic_should_returnDynamicMessageWithSameBytes() {
        Date date = Date.newBuilder().setYear(2026).setMonth(9).setDay(22).build();

        DynamicMessage result = ProtoTypes.dynamic(date, Date.getDescriptor());

        assertThat(result.toByteString()).isEqualTo(date.toByteString());
    }

    @Test
    @DisplayName("Given Date message with Money descriptor, when dynamic, should throw IllegalArgumentException")
    void given_dateWithMoneyDescriptor_when_dynamic_should_throwIllegalArgumentException() {
        Date date = Date.newBuilder().setYear(2026).setMonth(9).setDay(22).build();

        assertThatThrownBy(() -> ProtoTypes.dynamic(date, Money.getDescriptor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected a");
    }
}
