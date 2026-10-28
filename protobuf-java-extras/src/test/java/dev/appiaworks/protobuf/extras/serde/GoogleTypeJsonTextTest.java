package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.type.Date;
import com.google.type.DateTime;
import com.google.type.TimeOfDay;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the google.type text conversion: round trips and rejections come from
 * {@code serde/google-type-text.json}; a differential test pins the DateTime fast path to the
 * {@link DateTimeFormatter#ISO_DATE_TIME} verdict.
 */
class GoogleTypeJsonTextTest {

    private static final Locale DEFAULT_LOCALE = Locale.getDefault();
    private static JsonNode fixture;

    @BeforeAll
    static void loadFixture() throws IOException {
        try (InputStream in = GoogleTypeJsonTextTest.class.getResourceAsStream("/serde/google-type-text.json")) {
            fixture = new ObjectMapper().readTree(in);
        }
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(DEFAULT_LOCALE);
    }

    private static List<String[]> roundTrips(String type) {
        List<String[]> out = new ArrayList<>();
        fixture.get(type)
                .get("roundTrip")
                .forEach(n ->
                        out.add(new String[] {n.get("in").asText(), n.get("out").asText()}));
        return out;
    }

    private static List<String> rejects(String type) {
        List<String> out = new ArrayList<>();
        fixture.get(type).get("reject").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String write(DynamicMessage msg) {
        return (String) GoogleTypeJson.toJson(msg);
    }

    @Test
    @DisplayName("GIVEN date texts from the fixture WHEN read and written back SHOULD print the canonical text")
    void GIVEN_dateTexts_WHEN_roundTripped_SHOULD_printCanonicalText() {
        Descriptor d = Date.getDescriptor();
        for (String[] c : roundTrips("date")) {
            assertThat(write(GoogleTypeJson.fromJava(c[0], d))).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    @DisplayName("GIVEN malformed date texts from the fixture WHEN read SHOULD be rejected as invalid")
    void GIVEN_malformedDateTexts_WHEN_read_SHOULD_beRejected() {
        for (String s : rejects("date")) {
            assertThatThrownBy(() -> GoogleTypeJson.fromJava(s, Date.getDescriptor()))
                    .as("[" + s + "]")
                    .isInstanceOf(ProtoJsonException.class);
        }
    }

    @Test
    @DisplayName("GIVEN a year-0000 date and an impossible day WHEN read SHOULD explain each reason")
    void GIVEN_zeroYearAndImpossibleDay_WHEN_read_SHOULD_explainEachReason() {
        Descriptor d = Date.getDescriptor();
        assertThatThrownBy(() -> GoogleTypeJson.fromJava("0000-01-01", d))
                .hasMessageContaining("year 0000 is not a valid year");
        assertThatThrownBy(() -> GoogleTypeJson.fromJava("2026-02-30", d)).hasMessageContaining("FEBRUARY 30");
        assertThatThrownBy(() -> GoogleTypeJson.fromJava("2026-1-01", d))
                .hasMessageContaining("expected YYYY-MM-DD, YYYY-MM, YYYY or --MM-DD");
    }

    @Test
    @DisplayName("GIVEN time texts from the fixture WHEN read and written back SHOULD print the canonical text")
    void GIVEN_timeTexts_WHEN_roundTripped_SHOULD_printCanonicalText() {
        Descriptor d = TimeOfDay.getDescriptor();
        for (String[] c : roundTrips("timeOfDay")) {
            assertThat(write(GoogleTypeJson.fromJava(c[0], d))).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    @DisplayName("GIVEN malformed time texts from the fixture WHEN read SHOULD be rejected as invalid")
    void GIVEN_malformedTimeTexts_WHEN_read_SHOULD_beRejected() {
        for (String s : rejects("timeOfDay")) {
            assertThatThrownBy(() -> GoogleTypeJson.fromJava(s, TimeOfDay.getDescriptor()))
                    .as("[" + s + "]")
                    .isInstanceOf(ProtoJsonException.class);
        }
    }

    @Test
    @DisplayName("GIVEN a nine digit fraction WHEN read SHOULD keep every nanosecond")
    void GIVEN_nineDigitFraction_WHEN_read_SHOULD_keepEveryNanosecond() {
        DynamicMessage t = GoogleTypeJson.fromJava("01:02:03.000000007", TimeOfDay.getDescriptor());
        assertThat(t.getField(TimeOfDay.getDescriptor().findFieldByName("nanos")))
                .isEqualTo(7);
    }

    @Test
    @DisplayName("GIVEN date-time texts from the fixture WHEN read and written back SHOULD print the canonical text")
    void GIVEN_dateTimeTexts_WHEN_roundTripped_SHOULD_printCanonicalText() {
        Descriptor d = DateTime.getDescriptor();
        for (String[] c : roundTrips("dateTime")) {
            assertThat(write(GoogleTypeJson.fromJava(c[0], d))).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    @DisplayName("GIVEN malformed date-time texts from the fixture WHEN read SHOULD be rejected as invalid")
    void GIVEN_malformedDateTimeTexts_WHEN_read_SHOULD_beRejected() {
        for (String s : rejects("dateTime")) {
            assertThatThrownBy(() -> GoogleTypeJson.fromJava(s, DateTime.getDescriptor()))
                    .as("[" + s + "]")
                    .isInstanceOf(ProtoJsonException.class)
                    .hasMessageContaining("expected ISO-8601 date-time");
        }
    }

    @Test
    @DisplayName("GIVEN every fixture date-time WHEN read SHOULD match the ISO_DATE_TIME verdict and fields")
    void GIVEN_fixtureDateTimes_WHEN_read_SHOULD_matchFormatterVerdict() {
        List<String> all = new ArrayList<>(rejects("dateTime"));
        roundTrips("dateTime").forEach(c -> all.add(c[0]));
        all.forEach(GoogleTypeJsonTextTest::assertMatchesFormatter);
    }

    @Test
    @DisplayName("GIVEN thousands of generated date-times WHEN read SHOULD match the ISO_DATE_TIME verdict and fields")
    void GIVEN_generatedDateTimes_WHEN_read_SHOULD_matchFormatterVerdict() {
        Random r = new Random(42);
        String[] zones = {"", "Z", "+05:30", "-04:00", "+00:00", "-00:00", "+17:59", "+18:00", "+19:00", "+05:60"};
        String[] fractions = {"", ".5", ".123", ".123456", ".123456789", ".1234567890", ".", ".0"};
        for (int i = 0; i < 20_000; i++) {
            String s = String.format(
                    Locale.ROOT,
                    "%04d-%02d-%02dT%02d:%02d%s%s",
                    r.nextInt(3) == 0 ? r.nextInt(10) : 1900 + r.nextInt(300),
                    r.nextInt(15),
                    r.nextInt(34),
                    r.nextInt(27),
                    r.nextInt(63),
                    r.nextBoolean() ? ":" + String.format(Locale.ROOT, "%02d", r.nextInt(63)) : "",
                    "");
            if (s.length() > 16) s += fractions[r.nextInt(fractions.length)];
            s += zones[r.nextInt(zones.length)];
            assertMatchesFormatter(s);
        }
    }

    @Test
    @DisplayName("GIVEN the Arabic default locale WHEN dates and times are written SHOULD print ASCII digits")
    void GIVEN_arabicLocale_WHEN_written_SHOULD_printAsciiDigits() {
        Locale.setDefault(Locale.forLanguageTag("ar"));
        Descriptor dd = Date.getDescriptor();
        Descriptor td = TimeOfDay.getDescriptor();
        Descriptor dtd = DateTime.getDescriptor();
        assertThat(write(GoogleTypeJson.fromJava("2026-09-22", dd))).isEqualTo("2026-09-22");
        assertThat(write(GoogleTypeJson.fromJava("12:34:56.789", td))).isEqualTo("12:34:56.789");
        assertThat(write(GoogleTypeJson.fromJava("2026-09-22T14:03:11.123456+05:30", dtd)))
                .isEqualTo("2026-09-22T14:03:11.123456+05:30");
    }

    @Test
    @DisplayName("GIVEN a negative or oversized field WHEN written SHOULD print what %0Nd printed")
    void GIVEN_outOfRangeField_WHEN_appendPadded_SHOULD_matchPrintfShape() {
        for (int[] c : new int[][] {
            {-1, 2},
            {-5, 4},
            {-123, 2},
            {123, 2},
            {1_000_000, 3},
            {Integer.MIN_VALUE, 9},
            {Integer.MAX_VALUE, 2},
            {0, 4}
        }) {
            StringBuilder sb = GoogleTypeJson.appendPadded(new StringBuilder(), c[0], c[1]);
            assertThat(sb.toString()).isEqualTo(String.format(Locale.ROOT, "%0" + c[1] + "d", c[0]));
        }
    }

    // The pre-fast-path reading: ISO_DATE_TIME decides everything.
    private static void assertMatchesFormatter(String s) {
        Descriptor d = DateTime.getDescriptor();
        TemporalAccessor parsed = null;
        try {
            parsed = DateTimeFormatter.ISO_DATE_TIME.parse(s.trim());
            // Field extraction can throw too (e.g. a missing field); that is a rejection as well.
            parsed.get(ChronoField.YEAR);
        } catch (DateTimeException e) {
            parsed = null;
        }
        if (parsed == null) {
            assertThatThrownBy(() -> GoogleTypeJson.fromJava(s, d))
                    .as("[" + s + "] rejected by ISO_DATE_TIME")
                    .isInstanceOf(ProtoJsonException.class)
                    .hasMessageContaining("expected ISO-8601 date-time");
            return;
        }
        DynamicMessage got = GoogleTypeJson.fromJava(s, d);
        String[][] fields = {
            {"year", "YEAR"},
            {"month", "MONTH_OF_YEAR"},
            {"day", "DAY_OF_MONTH"},
            {"hours", "HOUR_OF_DAY"},
            {"minutes", "MINUTE_OF_HOUR"},
            {"seconds", "SECOND_OF_MINUTE"},
            {"nanos", "NANO_OF_SECOND"}
        };
        for (String[] f : fields) {
            assertThat(got.getField(d.findFieldByName(f[0])))
                    .as("[" + s + "] " + f[0])
                    .isEqualTo(parsed.get(ChronoField.valueOf(f[1])));
        }
        var zone = parsed.query(TemporalQueries.zoneId());
        var offset = parsed.query(TemporalQueries.offset());
        var zoneFd = d.findFieldByName("time_zone");
        var offsetFd = d.findFieldByName("utc_offset");
        if (zone != null && !(zone instanceof java.time.ZoneOffset)) {
            assertThat(got.hasField(zoneFd)).as("[" + s + "] zone").isTrue();
            assertThat(got.hasField(offsetFd)).isFalse();
        } else if (offset != null) {
            assertThat(got.hasField(zoneFd)).isFalse();
            DynamicMessage o = (DynamicMessage) got.getField(offsetFd);
            assertThat(o.getField(o.getDescriptorForType().findFieldByNumber(1)))
                    .as("[" + s + "] offset")
                    .isEqualTo((long) offset.getTotalSeconds());
        } else {
            assertThat(got.hasField(zoneFd)).isFalse();
            assertThat(got.hasField(offsetFd)).isFalse();
        }
    }
}
