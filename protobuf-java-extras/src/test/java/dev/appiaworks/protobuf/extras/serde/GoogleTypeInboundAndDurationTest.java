package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import com.google.type.Date;
import com.google.type.DateTime;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.DurationFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Inbound google.type.DateTime and partial dates, and the Duration / Timestamp leniency switches,
 * through the public {@link ProtoWkt} API.
 */
class GoogleTypeInboundAndDurationTest {

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    private static ProtoJsonMapping mapping(
            DurationFormat output, boolean acceptIsoDuration, boolean acceptEpochMillis) {
        return new ProtoJsonMapping(true, true, true, true, false, output, acceptIsoDuration, acceptEpochMillis);
    }

    private static Object field(DynamicMessage msg, String name) {
        return msg.getField(msg.getDescriptorForType().findFieldByName(name));
    }

    // ── DateTime inbound ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN DateTime text with an offset WHEN read SHOULD set utc_offset and leave time_zone unset")
    void given_dateTimeWithOffset_when_read_should_setUtcOffset() {
        DynamicMessage dt = ProtoWkt.fromJava("2026-09-22T14:03:11.5-04:00", DateTime.getDescriptor());

        assertThat(field(dt, "year")).isEqualTo(2026);
        assertThat(field(dt, "hours")).isEqualTo(14);
        assertThat(field(dt, "nanos")).isEqualTo(500_000_000);
        DynamicMessage offset = (DynamicMessage) field(dt, "utc_offset");
        assertThat(offset.getField(offset.getDescriptorForType().findFieldByName("seconds")))
                .isEqualTo(-14_400L);
        assertThat(dt.hasField(DateTime.getDescriptor().findFieldByName("time_zone")))
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN DateTime text with a bracketed zone WHEN read SHOULD set time_zone.id")
    void given_dateTimeWithZone_when_read_should_setTimeZoneId() {
        DynamicMessage dt = ProtoWkt.fromJava("2026-09-22T14:03:11-04:00[America/New_York]", DateTime.getDescriptor());

        DynamicMessage zone = (DynamicMessage) field(dt, "time_zone");
        assertThat(zone.getField(zone.getDescriptorForType().findFieldByName("id")))
                .isEqualTo("America/New_York");
        assertThat(dt.hasField(DateTime.getDescriptor().findFieldByName("utc_offset")))
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN local DateTime text WHEN read and written back SHOULD round-trip with neither offset nor zone")
    void given_localDateTime_when_roundTripped_should_keepLocalForm() {
        DynamicMessage dt = ProtoWkt.fromJava("2026-09-22T14:03:11", DateTime.getDescriptor());

        assertThat(dt.hasField(DateTime.getDescriptor().findFieldByName("utc_offset")))
                .isFalse();
        assertThat(dt.hasField(DateTime.getDescriptor().findFieldByName("time_zone")))
                .isFalse();
        assertThat(ProtoWkt.toJsonValue(dt)).isEqualTo("2026-09-22T14:03:11");
    }

    @Test
    @DisplayName("GIVEN the zoned text the gateway writes WHEN read back SHOULD print the same text")
    void given_zonedDateTimeText_when_roundTripped_should_printSameText() {
        String text = "2026-01-15T08:00:00-05:00[America/New_York]";

        assertThat(ProtoWkt.toJsonValue(ProtoWkt.fromJava(text, DateTime.getDescriptor())))
                .isEqualTo(text);
    }

    // ── Partial dates ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN --02-29 (a birthday without a year) WHEN read SHOULD accept it with year 0")
    void given_leapDayWithoutYear_when_read_should_accept() {
        DynamicMessage d = ProtoWkt.fromJava("--02-29", Date.getDescriptor());

        assertThat(field(d, "year")).isEqualTo(0);
        assertThat(field(d, "month")).isEqualTo(2);
        assertThat(field(d, "day")).isEqualTo(29);
        assertThat(ProtoWkt.toJsonValue(d)).isEqualTo("--02-29");
    }

    @Test
    @DisplayName("GIVEN a year-only date WHEN read and written back SHOULD round-trip")
    void given_yearOnlyDate_when_roundTripped_should_keepYearForm() {
        assertThat(ProtoWkt.toJsonValue(ProtoWkt.fromJava("2026", Date.getDescriptor())))
                .isEqualTo("2026");
    }

    // ── Duration ─────────────────────────────────────────────────────────────────────

    private static final Descriptor DURATION = Duration.getDescriptor();

    private static DynamicMessage duration(long seconds, int nanos) {
        return DynamicMessage.newBuilder(Duration.newBuilder()
                        .setSeconds(seconds)
                        .setNanos(nanos)
                        .build())
                .build();
    }

    @Test
    @DisplayName("GIVEN the default mapping WHEN a Duration is written SHOULD use the proto form 5400s")
    void given_defaults_when_durationWritten_should_useProtoForm() {
        assertThat(ProtoWkt.toJsonValue(duration(5400, 0))).isEqualTo("5400s");
    }

    @Test
    @DisplayName("GIVEN duration output ISO_8601 WHEN a Duration is written SHOULD print PT1H30M")
    void given_isoOutput_when_durationWritten_should_printIso() {
        ProtoJsonMapping.install(mapping(DurationFormat.ISO_8601, true, false));

        assertThat(ProtoWkt.toJsonValue(duration(5400, 0))).isEqualTo("PT1H30M");
        assertThat(ProtoWkt.toJsonValue(duration(-1, -500_000_000))).isEqualTo("PT-1.5S");
    }

    @Test
    @DisplayName("GIVEN the default mapping WHEN ISO-8601 duration text is read SHOULD accept it")
    void given_defaults_when_isoDurationRead_should_accept() {
        DynamicMessage d = ProtoWkt.fromJava("PT1H30M", DURATION);

        assertThat(field(d, "seconds")).isEqualTo(5400L);
        assertThat(field(d, "nanos")).isEqualTo(0);
    }

    @Test
    @DisplayName("GIVEN a negative fractional ISO duration WHEN read SHOULD give seconds and nanos the same sign")
    void given_negativeFractionalIsoDuration_when_read_should_shareSign() {
        DynamicMessage d = ProtoWkt.fromJava("PT-1.5S", DURATION);

        assertThat(field(d, "seconds")).isEqualTo(-1L);
        assertThat(field(d, "nanos")).isEqualTo(-500_000_000);
    }

    @Test
    @DisplayName("GIVEN accept-iso8601 off WHEN ISO text is read SHOULD reject it while 5400s still parses")
    void given_isoInputOff_when_isoDurationRead_should_reject() {
        ProtoJsonMapping.install(mapping(DurationFormat.PROTO, false, false));

        assertThatThrownBy(() -> ProtoWkt.fromJava("PT1H30M", DURATION)).isInstanceOf(ProtoJsonException.class);
        assertThat(field(ProtoWkt.fromJava("5400s", DURATION), "seconds")).isEqualTo(5400L);
    }

    // ── Timestamp ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN the default mapping WHEN a Timestamp arrives as a JSON number SHOULD reject it")
    void given_defaults_when_epochMillisRead_should_notAccept() {
        assertThatThrownBy(() -> ProtoWkt.fromJava(1_758_549_791_123L, Timestamp.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class);
    }

    @Test
    @DisplayName(
            "GIVEN accept-epoch-millis on WHEN a Timestamp arrives as a JSON number SHOULD read epoch milliseconds")
    void given_epochMillisOn_when_read_should_convert() {
        ProtoJsonMapping.install(mapping(DurationFormat.PROTO, true, true));

        DynamicMessage ts = ProtoWkt.fromJava(1_758_549_791_123L, Timestamp.getDescriptor());

        assertThat(field(ts, "seconds")).isEqualTo(1_758_549_791L);
        assertThat(field(ts, "nanos")).isEqualTo(123_000_000);
    }
}
