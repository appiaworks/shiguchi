package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * BDD tests for Tier 0 WKT text serialization: {@link ProtoWkt#timestampText(long, int)} and
 * {@link ProtoWkt#durationText(long, int)} must produce byte-identical output to the protobuf-util
 * library and raise the same exceptions for invalid inputs.
 */
class WktTextTest {

    @ParameterizedTest
    @CsvSource({
        "0,0", // epoch, no nanos
        "0,1", // epoch, 1 nano
        "0,1000000", // epoch, millis (multiple of 1_000_000)
        "0,1000", // epoch, micros (multiple of 1_000)
        "0,3", // epoch, 3-digit frac
        "0,123456", // epoch, 6-digit frac
        "0,123456789", // epoch, 9-digit frac
        "-62135596800,0", // year 0001-01-01, no nanos
        "253402300799,0", // year 9999-12-31T23:59:59, no nanos
        "-13000000000,0", // pre-1582 Julian calendar path
        "1708953600,0", // 2024-02-29 (leap day)
        "-315619200,0", // 1960-01-01
    })
    @DisplayName(
            "Given a timestamp with various seconds and nanos, when converted to text, should match Timestamps.toString")
    void given_timestampVariousCases_when_convertToText_should_matchTimestamps(long seconds, int nanos) {
        String expected = Timestamps.toString(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(seconds)
                .setNanos(nanos)
                .build());
        String actual = ProtoWkt.timestampText(seconds, nanos);
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] seconds={0}, nanos={1}")
    @MethodSource("timestampRandomSweep")
    @DisplayName(
            "Given 500 random timestamp values in the valid range, when converted to text, should match Timestamps.toString")
    void given_timestampRandom500Values_when_convertToText_should_matchTimestamps(long seconds, int nanos) {
        String expected = Timestamps.toString(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(seconds)
                .setNanos(nanos)
                .build());
        String actual = ProtoWkt.timestampText(seconds, nanos);
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> timestampRandomSweep() {
        Random rng = new Random(42L); // seeded for reproducibility
        return Stream.generate(() -> {
                    long seconds = -62135596800L + rng.nextLong(253402300799L - (-62135596800L) + 1L);
                    int nanos = rng.nextInt(1_000_000_000);
                    return org.junit.jupiter.params.provider.Arguments.of(seconds, nanos);
                })
                .limit(500);
    }

    @Test
    @DisplayName(
            "Given an invalid timestamp (seconds 253402300800), when converted to text, should throw IllegalArgumentException")
    void given_invalidTimestampTooLarge_when_convertToText_should_throw() {
        assertThatThrownBy(() -> ProtoWkt.timestampText(253402300800L, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "Given an invalid timestamp (negative nanos), when converted to text, should throw IllegalArgumentException")
    void given_invalidTimestampNegativeNanos_when_convertToText_should_throw() {
        assertThatThrownBy(() -> ProtoWkt.timestampText(0L, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "Given an invalid timestamp (nanos >= 1 billion), when converted to text, should throw IllegalArgumentException")
    void given_invalidTimestampNanosTooBig_when_convertToText_should_throw() {
        assertThatThrownBy(() -> ProtoWkt.timestampText(0L, 1_000_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "0,0", // zero duration
        "1,0", // 1 second
        "5,0", // 5 seconds
        "5,500000000", // 5.5 seconds
        "5,123456", // 5 seconds + 6-digit frac
        "5,123", // 5 seconds + 3-digit frac
        "5,123456789", // 5 seconds + 9-digit frac
        "-5,-500000000", // negative: -5.5 seconds
        "315576000000,0", // max duration
        "-1,-1", // negative duration at -1 nanos
    })
    @DisplayName(
            "Given a duration with various seconds and nanos, when converted to text, should match Durations.toString")
    void given_durationVariousCases_when_convertToText_should_matchDurations(long seconds, int nanos) {
        String expected = Durations.toString(com.google.protobuf.Duration.newBuilder()
                .setSeconds(seconds)
                .setNanos(nanos)
                .build());
        String actual = ProtoWkt.durationText(seconds, nanos);
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] seconds={0}, nanos={1}")
    @MethodSource("durationRandomSweep")
    @DisplayName(
            "Given 500 random duration values in the valid range, when converted to text, should match Durations.toString")
    void given_durationRandom500Values_when_convertToText_should_matchDurations(long seconds, int nanos) {
        String expected = Durations.toString(com.google.protobuf.Duration.newBuilder()
                .setSeconds(seconds)
                .setNanos(nanos)
                .build());
        String actual = ProtoWkt.durationText(seconds, nanos);
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> durationRandomSweep() {
        Random rng = new Random(42L); // seeded for reproducibility
        return Stream.generate(() -> {
                    long seconds = -315_576_000_000L + rng.nextLong(315_576_000_000L * 2L + 1L);
                    // A valid duration's nanos carry the sign of its seconds (or either, at zero).
                    int magnitude = rng.nextInt(1_000_000_000);
                    int nanos = seconds < 0 ? -magnitude : magnitude;
                    return org.junit.jupiter.params.provider.Arguments.of(seconds, nanos);
                })
                .limit(500);
    }

    @Test
    @DisplayName(
            "Given an invalid duration (seconds > max), when converted to text, should throw IllegalArgumentException")
    void given_invalidDurationTooLarge_when_convertToText_should_throw() {
        assertThatThrownBy(() -> ProtoWkt.durationText(315_576_000_001L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "Given an invalid duration (nanos and seconds opposite signs), when converted to text, should throw IllegalArgumentException")
    void given_invalidDurationSignMismatch_when_convertToText_should_throw() {
        assertThatThrownBy(() -> ProtoWkt.durationText(1, -500_000_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "Given an invalid duration (nanos = -1 billion), when converted to text, should throw IllegalArgumentException")
    void given_invalidDurationNanosBelowMin_when_convertToText_should_throw() {
        assertThatThrownBy(() -> ProtoWkt.durationText(0, -1_000_000_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
