package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DynamicMessage;
import com.google.type.Date;
import com.google.type.DateTime;
import com.google.type.Decimal;
import com.google.type.Money;
import com.google.type.TimeOfDay;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for Google Type scalar mapping through the public ProtoWkt API.
 * Tests verify that Date, TimeOfDay, DateTime, Decimal, Money, Duration, and Timestamp
 * serialize as ISO strings when mapped, and deserialize correctly from those strings or maps.
 */
class GoogleTypeScalarMappingTest {

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Date tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Date with year=2026 month=9 day=22, when toJsonValue, should return ISO date string")
    void given_dateFullValue_when_toJsonValue_should_returnIsoDateString() {
        DynamicMessage date = DynamicMessage.newBuilder(Date.getDescriptor())
                .setField(Date.getDescriptor().findFieldByName("year"), 2026)
                .setField(Date.getDescriptor().findFieldByName("month"), 9)
                .setField(Date.getDescriptor().findFieldByName("day"), 22)
                .build();

        Object result = ProtoWkt.toJsonValue(date);

        assertThat(result).isEqualTo("2026-09-22");
    }

    @Test
    @DisplayName("Given Date with year=2026 month=9 day=0, when toJsonValue, should return year-month string")
    void given_dateYearMonthValue_when_toJsonValue_should_returnYearMonthString() {
        DynamicMessage date = DynamicMessage.newBuilder(Date.getDescriptor())
                .setField(Date.getDescriptor().findFieldByName("year"), 2026)
                .setField(Date.getDescriptor().findFieldByName("month"), 9)
                .setField(Date.getDescriptor().findFieldByName("day"), 0)
                .build();

        Object result = ProtoWkt.toJsonValue(date);

        assertThat(result).isEqualTo("2026-09");
    }

    @Test
    @DisplayName("Given Date with year=2026 month=0 day=0, when toJsonValue, should return year-only string")
    void given_dateYearOnlyValue_when_toJsonValue_should_returnYearString() {
        DynamicMessage date = DynamicMessage.newBuilder(Date.getDescriptor())
                .setField(Date.getDescriptor().findFieldByName("year"), 2026)
                .setField(Date.getDescriptor().findFieldByName("month"), 0)
                .setField(Date.getDescriptor().findFieldByName("day"), 0)
                .build();

        Object result = ProtoWkt.toJsonValue(date);

        assertThat(result).isEqualTo("2026");
    }

    @Test
    @DisplayName("Given Date with year=0 month=2 day=29 (leap day), when toJsonValue, should return month-day string")
    void given_dateMonthDayValue_when_toJsonValue_should_returnMonthDayString() {
        DynamicMessage date = DynamicMessage.newBuilder(Date.getDescriptor())
                .setField(Date.getDescriptor().findFieldByName("year"), 0)
                .setField(Date.getDescriptor().findFieldByName("month"), 2)
                .setField(Date.getDescriptor().findFieldByName("day"), 29)
                .build();

        Object result = ProtoWkt.toJsonValue(date);

        assertThat(result).isEqualTo("--02-29");
    }

    @Test
    @DisplayName(
            "Given Date with year set month=0 day set (invalid combo), when toJsonValue, should return object form")
    void given_dateInvalidCombo_when_toJsonValue_should_returnObjectForm() {
        DynamicMessage date = DynamicMessage.newBuilder(Date.getDescriptor())
                .setField(Date.getDescriptor().findFieldByName("year"), 2026)
                .setField(Date.getDescriptor().findFieldByName("month"), 0)
                .setField(Date.getDescriptor().findFieldByName("day"), 22)
                .build();

        Object result = ProtoWkt.toJsonValue(date);

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("year", 2026);
        assertThat(map).containsEntry("day", 22);
    }

    @Test
    @DisplayName("Given ISO date string '2026-09-22', when fromJava on Date descriptor, should parse to correct fields")
    void given_isoDateString_when_fromJava_should_parseCorrectly() {
        DynamicMessage result = ProtoWkt.fromJava("2026-09-22", Date.getDescriptor());

        assertThat(result)
                .extracting(
                        msg -> ((Number) msg.getField(msg.getDescriptorForType().findFieldByName("year"))).intValue())
                .isEqualTo(2026);
        assertThat(result)
                .extracting(
                        msg -> ((Number) msg.getField(msg.getDescriptorForType().findFieldByName("month"))).intValue())
                .isEqualTo(9);
        assertThat(result)
                .extracting(
                        msg -> ((Number) msg.getField(msg.getDescriptorForType().findFieldByName("day"))).intValue())
                .isEqualTo(22);
    }

    @Test
    @DisplayName(
            "Given partial date string '2026-09', when fromJava on Date descriptor, should parse to year and month")
    void given_partialDateYearMonth_when_fromJava_should_parseCorrectly() {
        DynamicMessage result = ProtoWkt.fromJava("2026-09", Date.getDescriptor());

        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("year"))).intValue())
                .isEqualTo(2026);
        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("month"))).intValue())
                .isEqualTo(9);
        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("day"))).intValue())
                .isEqualTo(0);
    }

    @Test
    @DisplayName(
            "Given invalid date string '2026-02-30', when fromJava on Date descriptor, should throw ProtoJsonException")
    void given_invalidDate_when_fromJava_should_throwProtoJsonException() {
        assertThatThrownBy(() -> ProtoWkt.fromJava("2026-02-30", Date.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class);
    }

    @Test
    @DisplayName(
            "Given malformed date string 'not-a-date', when fromJava on Date descriptor, should throw ProtoJsonException")
    void given_malformedDateString_when_fromJava_should_throwProtoJsonException() {
        assertThatThrownBy(() -> ProtoWkt.fromJava("not-a-date", Date.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class);
    }

    @Test
    @DisplayName(
            "Given object map form {'year':2026, 'month':9, 'day':22}, when fromJava on Date descriptor, should build message")
    void given_dateObjectForm_when_fromJava_should_buildMessage() {
        Map<String, Object> map = Map.of("year", 2026, "month", 9, "day", 22);

        DynamicMessage result = ProtoWkt.fromJava(map, Date.getDescriptor());

        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("year"))).intValue())
                .isEqualTo(2026);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TimeOfDay tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given TimeOfDay with hours=14 minutes=3 seconds=11 nanos=0, when toJsonValue, should return time string without fraction")
    void given_timeOfDayNoNanos_when_toJsonValue_should_returnTimeString() {
        DynamicMessage time = DynamicMessage.newBuilder(TimeOfDay.getDescriptor())
                .setField(TimeOfDay.getDescriptor().findFieldByName("hours"), 14)
                .setField(TimeOfDay.getDescriptor().findFieldByName("minutes"), 3)
                .setField(TimeOfDay.getDescriptor().findFieldByName("seconds"), 11)
                .setField(TimeOfDay.getDescriptor().findFieldByName("nanos"), 0)
                .build();

        Object result = ProtoWkt.toJsonValue(time);

        assertThat(result).isEqualTo("14:03:11");
    }

    @Test
    @DisplayName("Given TimeOfDay with nanos=123000000, when toJsonValue, should return time with 3-digit fraction")
    void given_timeOfDayMillis_when_toJsonValue_should_return3DigitFraction() {
        DynamicMessage time = DynamicMessage.newBuilder(TimeOfDay.getDescriptor())
                .setField(TimeOfDay.getDescriptor().findFieldByName("hours"), 14)
                .setField(TimeOfDay.getDescriptor().findFieldByName("minutes"), 3)
                .setField(TimeOfDay.getDescriptor().findFieldByName("seconds"), 11)
                .setField(TimeOfDay.getDescriptor().findFieldByName("nanos"), 123_000_000)
                .build();

        Object result = ProtoWkt.toJsonValue(time);

        assertThat(result).isEqualTo("14:03:11.123");
    }

    @Test
    @DisplayName("Given TimeOfDay with nanos=123456000, when toJsonValue, should return time with 6-digit fraction")
    void given_timeOfDayMicros_when_toJsonValue_should_return6DigitFraction() {
        DynamicMessage time = DynamicMessage.newBuilder(TimeOfDay.getDescriptor())
                .setField(TimeOfDay.getDescriptor().findFieldByName("hours"), 14)
                .setField(TimeOfDay.getDescriptor().findFieldByName("minutes"), 3)
                .setField(TimeOfDay.getDescriptor().findFieldByName("seconds"), 11)
                .setField(TimeOfDay.getDescriptor().findFieldByName("nanos"), 123_456_000)
                .build();

        Object result = ProtoWkt.toJsonValue(time);

        assertThat(result).isEqualTo("14:03:11.123456");
    }

    @Test
    @DisplayName("Given TimeOfDay with nanos=1, when toJsonValue, should return time with 9-digit fraction")
    void given_timeOfDayNanos_when_toJsonValue_should_return9DigitFraction() {
        DynamicMessage time = DynamicMessage.newBuilder(TimeOfDay.getDescriptor())
                .setField(TimeOfDay.getDescriptor().findFieldByName("hours"), 14)
                .setField(TimeOfDay.getDescriptor().findFieldByName("minutes"), 3)
                .setField(TimeOfDay.getDescriptor().findFieldByName("seconds"), 11)
                .setField(TimeOfDay.getDescriptor().findFieldByName("nanos"), 1)
                .build();

        Object result = ProtoWkt.toJsonValue(time);

        assertThat(result).isEqualTo("14:03:11.000000001");
    }

    @Test
    @DisplayName(
            "Given time string '24:00:00' (end of day), when fromJava on TimeOfDay descriptor, should parse as valid")
    void given_endOfDayTime_when_fromJava_should_parseAsValid() {
        DynamicMessage result = ProtoWkt.fromJava("24:00:00", TimeOfDay.getDescriptor());

        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("hours"))).intValue())
                .isEqualTo(24);
    }

    @Test
    @DisplayName(
            "Given time string '14:03' (no seconds), when fromJava on TimeOfDay descriptor, should parse with seconds=0")
    void given_timeNoSeconds_when_fromJava_should_parseWithZeroSeconds() {
        DynamicMessage result = ProtoWkt.fromJava("14:03", TimeOfDay.getDescriptor());

        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("seconds"))).intValue())
                .isEqualTo(0);
    }

    @Test
    @DisplayName(
            "Given invalid time string '25:00:00', when fromJava on TimeOfDay descriptor, should throw ProtoJsonException")
    void given_invalidHour_when_fromJava_should_throwProtoJsonException() {
        assertThatThrownBy(() -> ProtoWkt.fromJava("25:00:00", TimeOfDay.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DateTime tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given DateTime with offset -4h, when toJsonValue, should return ISO string with offset")
    void given_dateTimeWithUtcOffset_when_toJsonValue_should_returnStringWithOffset() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, true, false, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage offset = DynamicMessage.newBuilder(
                        DateTime.getDescriptor().findFieldByName("utc_offset").getMessageType())
                .setField(
                        DateTime.getDescriptor()
                                .findFieldByName("utc_offset")
                                .getMessageType()
                                .findFieldByNumber(1),
                        -14400L)
                .build();

        DynamicMessage dateTime = DynamicMessage.newBuilder(DateTime.getDescriptor())
                .setField(DateTime.getDescriptor().findFieldByName("year"), 2026)
                .setField(DateTime.getDescriptor().findFieldByName("month"), 9)
                .setField(DateTime.getDescriptor().findFieldByName("day"), 22)
                .setField(DateTime.getDescriptor().findFieldByName("hours"), 14)
                .setField(DateTime.getDescriptor().findFieldByName("minutes"), 3)
                .setField(DateTime.getDescriptor().findFieldByName("seconds"), 11)
                .setField(DateTime.getDescriptor().findFieldByName("nanos"), 0)
                .setField(DateTime.getDescriptor().findFieldByName("utc_offset"), offset)
                .build();

        Object result = ProtoWkt.toJsonValue(dateTime);

        assertThat(result).asString().contains("2026-09-22T14:03:11").contains("-04:00");
    }

    @Test
    @DisplayName("Given DateTime with zero offset (UTC), when toJsonValue, should end with Z")
    void given_dateTimeZeroOffset_when_toJsonValue_should_endWithZ() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, true, false, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage offset = DynamicMessage.newBuilder(
                        DateTime.getDescriptor().findFieldByName("utc_offset").getMessageType())
                .setField(
                        DateTime.getDescriptor()
                                .findFieldByName("utc_offset")
                                .getMessageType()
                                .findFieldByNumber(1),
                        0L)
                .build();

        DynamicMessage dateTime = DynamicMessage.newBuilder(DateTime.getDescriptor())
                .setField(DateTime.getDescriptor().findFieldByName("year"), 2026)
                .setField(DateTime.getDescriptor().findFieldByName("month"), 9)
                .setField(DateTime.getDescriptor().findFieldByName("day"), 22)
                .setField(DateTime.getDescriptor().findFieldByName("hours"), 14)
                .setField(DateTime.getDescriptor().findFieldByName("minutes"), 3)
                .setField(DateTime.getDescriptor().findFieldByName("seconds"), 11)
                .setField(DateTime.getDescriptor().findFieldByName("nanos"), 0)
                .setField(DateTime.getDescriptor().findFieldByName("utc_offset"), offset)
                .build();

        Object result = ProtoWkt.toJsonValue(dateTime);

        assertThat(result).asString().endsWith("Z");
    }

    @Test
    @DisplayName(
            "Given DateTime with time_zone id='America/New_York', when toJsonValue, should include zone id in brackets")
    void given_dateTimeWithTimeZone_when_toJsonValue_should_includeBracketedZoneId() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, true, false, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage zone = DynamicMessage.newBuilder(
                        DateTime.getDescriptor().findFieldByName("time_zone").getMessageType())
                .setField(
                        DateTime.getDescriptor()
                                .findFieldByName("time_zone")
                                .getMessageType()
                                .findFieldByName("id"),
                        "America/New_York")
                .build();

        DynamicMessage dateTime = DynamicMessage.newBuilder(DateTime.getDescriptor())
                .setField(DateTime.getDescriptor().findFieldByName("year"), 2026)
                .setField(DateTime.getDescriptor().findFieldByName("month"), 9)
                .setField(DateTime.getDescriptor().findFieldByName("day"), 22)
                .setField(DateTime.getDescriptor().findFieldByName("hours"), 14)
                .setField(DateTime.getDescriptor().findFieldByName("minutes"), 3)
                .setField(DateTime.getDescriptor().findFieldByName("seconds"), 11)
                .setField(DateTime.getDescriptor().findFieldByName("nanos"), 0)
                .setField(DateTime.getDescriptor().findFieldByName("time_zone"), zone)
                .build();

        Object result = ProtoWkt.toJsonValue(dateTime);

        assertThat(result).asString().contains("[America/New_York]");
    }

    @Test
    @DisplayName("Given DateTime with no offset nor zone, when toJsonValue, should return local ISO string")
    void given_dateTimeLocalNoOffset_when_toJsonValue_should_returnLocalString() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, true, false, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage dateTime = DynamicMessage.newBuilder(DateTime.getDescriptor())
                .setField(DateTime.getDescriptor().findFieldByName("year"), 2026)
                .setField(DateTime.getDescriptor().findFieldByName("month"), 9)
                .setField(DateTime.getDescriptor().findFieldByName("day"), 22)
                .setField(DateTime.getDescriptor().findFieldByName("hours"), 14)
                .setField(DateTime.getDescriptor().findFieldByName("minutes"), 3)
                .setField(DateTime.getDescriptor().findFieldByName("seconds"), 11)
                .setField(DateTime.getDescriptor().findFieldByName("nanos"), 0)
                .build();

        Object result = ProtoWkt.toJsonValue(dateTime);

        assertThat(result).isEqualTo("2026-09-22T14:03:11");
    }

    @Test
    @DisplayName("Given DateTime with year=0, when toJsonValue, should return object form")
    void given_dateTimeNoYear_when_toJsonValue_should_returnObjectForm() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, true, false, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage dateTime = DynamicMessage.newBuilder(DateTime.getDescriptor())
                .setField(DateTime.getDescriptor().findFieldByName("year"), 0)
                .setField(DateTime.getDescriptor().findFieldByName("month"), 9)
                .setField(DateTime.getDescriptor().findFieldByName("day"), 22)
                .setField(DateTime.getDescriptor().findFieldByName("hours"), 14)
                .setField(DateTime.getDescriptor().findFieldByName("minutes"), 3)
                .setField(DateTime.getDescriptor().findFieldByName("seconds"), 11)
                .setField(DateTime.getDescriptor().findFieldByName("nanos"), 0)
                .build();

        Object result = ProtoWkt.toJsonValue(dateTime);

        assertThat(result).isInstanceOf(Map.class);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Decimal tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Decimal with value='12.34', when toJsonValue, should return decimal string")
    void given_decimalValue_when_toJsonValue_should_returnDecimalString() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, false, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage decimal = DynamicMessage.newBuilder(Decimal.getDescriptor())
                .setField(Decimal.getDescriptor().findFieldByName("value"), "12.34")
                .build();

        Object result = ProtoWkt.toJsonValue(decimal);

        assertThat(result).isEqualTo("12.34");
    }

    @Test
    @DisplayName("Given decimal string '12.34', when fromJava on Decimal descriptor, should build message")
    void given_decimalString_when_fromJava_should_buildMessage() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, false, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage result = ProtoWkt.fromJava("12.34", Decimal.getDescriptor());

        assertThat((String) result.getField(result.getDescriptorForType().findFieldByName("value")))
                .isEqualTo("12.34");
    }

    @Test
    @DisplayName("Given decimal number 12.5, when fromJava on Decimal descriptor, should accept as numeric")
    void given_decimalNumber_when_fromJava_should_acceptNumeric() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, false, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage result = ProtoWkt.fromJava(12.5, Decimal.getDescriptor());

        assertThat((String) result.getField(result.getDescriptorForType().findFieldByName("value")))
                .contains("12");
    }

    @Test
    @DisplayName(
            "Given invalid decimal string 'abc', when fromJava on Decimal descriptor, should throw ProtoJsonException")
    void given_invalidDecimalString_when_fromJava_should_throwProtoJsonException() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, false, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        assertThatThrownBy(() -> ProtoWkt.fromJava("abc", Decimal.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Money tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Money with DEFAULTS mapping, when isWkt, should return false (money off by default)")
    void given_moneyWithDefaults_when_isWkt_should_returnFalse() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);

        boolean result = ProtoWkt.isWkt(Money.getDescriptor());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName(
            "Given Money with mapping money=true, units=12 nanos=340000000 currency='USD', when toJsonValue, should return flattened object")
    void given_mappedMoney_when_toJsonValue_should_returnFlattenedObject() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, false, false, true, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage money = DynamicMessage.newBuilder(Money.getDescriptor())
                .setField(Money.getDescriptor().findFieldByName("currency_code"), "USD")
                .setField(Money.getDescriptor().findFieldByName("units"), 12L)
                .setField(Money.getDescriptor().findFieldByName("nanos"), 340_000_000)
                .build();

        Object result = ProtoWkt.toJsonValue(money);

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("currency", "USD").containsEntry("amount", "12.34");
    }

    @Test
    @DisplayName(
            "Given Money object {'currency':'USD','amount':'-1.75'}, when fromJava with money=true, should parse to units and nanos")
    void given_moneyObject_when_fromJavaWithMoneyMapped_should_parseCorrectly() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, false, false, true, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        Map<String, Object> map = Map.of("currency", "USD", "amount", "-1.75");
        DynamicMessage result = ProtoWkt.fromJava(map, Money.getDescriptor());

        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("units"))).longValue())
                .isEqualTo(-1L);
        assertThat(((Number) result.getField(result.getDescriptorForType().findFieldByName("nanos"))).intValue())
                .isEqualTo(-750_000_000);
    }

    @Test
    @DisplayName(
            "Given Money amount with 10 fractional digits, when fromJava with money=true, should throw ProtoJsonException")
    void given_moneyTooManyFractionalDigits_when_fromJavaWithMoneyMapped_should_throwProtoJsonException() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                false, false, false, false, true, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        Map<String, Object> map = Map.of("currency", "USD", "amount", "1.0123456789");
        assertThatThrownBy(() -> ProtoWkt.fromJava(map, Money.getDescriptor())).isInstanceOf(ProtoJsonException.class);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Mapping configuration tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given PROTO3 mapping installed, when isWkt on Date descriptor, should return false")
    void given_proto3MappingInstalled_when_isWktOnDate_should_returnFalse() {
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        boolean result = ProtoWkt.isWkt(Date.getDescriptor());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Given custom mapping with date=true, when installed then isWkt on Date, should return true")
    void given_customMappingWithDateTrue_when_installed_isWktShouldReturnTrue() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, false, false, false, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        boolean result = ProtoWkt.isWkt(Date.getDescriptor());

        assertThat(result).isTrue();
    }
}
