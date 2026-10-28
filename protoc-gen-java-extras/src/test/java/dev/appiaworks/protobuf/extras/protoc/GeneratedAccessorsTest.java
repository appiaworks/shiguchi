package dev.appiaworks.protobuf.extras.protoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.type.Date;
import dev.appiaworks.protobuf.extras.MoneyValue;
import dev.appiaworks.protobuf.extras.protoc.fixture.SkuCode;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.EditionEvent;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.LegacyOrderOuterClass.LegacyOrder;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Trip;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accessors as an application uses them. The classes under test are generated in this
 * module's own build: protoc's Java generator writes them and the plugin inserts into them. That
 * this file compiles is half the test; a wrong name, type or insertion point fails the build.
 */
class GeneratedAccessorsTest {

    private static final Instant AT = Instant.parse("2026-09-22T14:03:11.5Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    // ── Singular fields ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given a message built with Java-typed setters, when read with Java-typed getters, should return the same values")
    void given_javaTypedSetters_when_readWithJavaTypedGetters_should_returnSameValues() {
        Trip trip = Trip.newBuilder()
                .setBookedAt(AT)
                .setLength(Duration.ofMinutes(90))
                .setDepartsOn(DAY)
                .setCheckIn(LocalTime.of(9, 30))
                .setDistance(new BigDecimal("12.30"))
                .setPrice(new MoneyValue("USD", new BigDecimal("12.34")))
                .build();

        assertThat(trip.getBookedAtAsInstant()).isEqualTo(AT);
        assertThat(trip.getBookedAtAsOffsetDateTime()).isEqualTo(OffsetDateTime.ofInstant(AT, ZoneOffset.UTC));
        assertThat(trip.getLengthAsDuration()).isEqualTo(Duration.ofMinutes(90));
        assertThat(trip.getDepartsOnAsLocalDate()).isEqualTo(DAY);
        assertThat(trip.getCheckInAsLocalTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(trip.getDistanceAsBigDecimal()).isEqualTo(new BigDecimal("12.30"));
        assertThat(trip.getPriceAsMoneyValue().amount()).isEqualByComparingTo("12.34");
        assertThat(trip.getPriceAsMoneyValue().currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("Given a Java-typed setter, when the proto-typed getter is read, should hold the converted message")
    void given_javaTypedSetter_when_protoGetterRead_should_holdConvertedMessage() {
        Trip trip = Trip.newBuilder().setDepartsOn(DAY).build();

        assertThat(trip.getDepartsOn())
                .isEqualTo(
                        Date.newBuilder().setYear(2026).setMonth(9).setDay(22).build());
    }

    @Test
    @DisplayName("Given unset fields, when read with Java-typed getters, should return null rather than a zero value")
    void given_unsetFields_when_read_should_returnNull() {
        Trip trip = Trip.getDefaultInstance();

        assertThat(trip.getBookedAtAsInstant()).isNull();
        assertThat(trip.getDepartsOnAsLocalDate()).isNull();
        assertThat(trip.getSeatsAsInteger()).isNull();
        assertThat(trip.getPriceAsMoneyValue()).isNull();
        assertThat(trip.getAttributesAsMap()).isNull();
    }

    @Test
    @DisplayName(
            "Given null passed to a Java-typed setter of a message field, when called, should throw NullPointerException")
    void given_nullToSetter_when_called_should_throw() {
        assertThatThrownBy(() -> Trip.newBuilder().setDepartsOn((LocalDate) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName(
            "Given a partial date on the wire, when read as a LocalDate, should throw as ProtoTypes does rather than guess a day")
    void given_partialDate_when_readAsLocalDate_should_throw() {
        Trip trip = Trip.newBuilder()
                .setDepartsOn(Date.newBuilder().setYear(2026).setMonth(9))
                .build();

        assertThatThrownBy(trip::getDepartsOnAsLocalDate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a full date");
    }

    @Test
    @DisplayName(
            "Given a DateTime field, when set and read in each of its three Java forms, should keep the form it was given")
    void given_dateTimeField_when_setInEachForm_should_keepForm() {
        LocalDateTime local = LocalDateTime.of(2026, 9, 22, 14, 3, 11);
        OffsetDateTime offset = local.atOffset(ZoneOffset.ofHours(-4));
        ZonedDateTime zoned = local.atZone(ZoneId.of("America/New_York"));

        assertThat(Trip.newBuilder().setMeeting(local).build().getMeetingAsLocalDateTime())
                .isEqualTo(local);
        assertThat(Trip.newBuilder().setMeeting(offset).build().getMeetingAsOffsetDateTime())
                .isEqualTo(offset);
        assertThat(Trip.newBuilder().setMeeting(zoned).build().getMeetingAsZonedDateTime())
                .isEqualTo(zoned);
    }

    @Test
    @DisplayName("Given wrapper fields, when set and read as boxed values, should round-trip, unsigned ones included")
    void given_wrapperFields_when_setAndRead_should_roundTrip() {
        Trip trip = Trip.newBuilder()
                .setSeats(3)
                .setPoints(9_000_000_000L)
                .setLegs(4_294_967_295L)
                .setSerial(new BigInteger("18446744073709551615"))
                .setRating(4.5d)
                .setWeight(2.5f)
                .setRefundable(true)
                .setNote("window seat")
                .setBlob(ByteString.copyFromUtf8("x"))
                .build();

        assertThat(trip.getSeatsAsInteger()).isEqualTo(3);
        assertThat(trip.getPointsAsLong()).isEqualTo(9_000_000_000L);
        assertThat(trip.getLegsAsLong()).isEqualTo(4_294_967_295L);
        assertThat(trip.getSerialAsBigInteger()).isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(trip.getRatingAsDouble()).isEqualTo(4.5d);
        assertThat(trip.getWeightAsFloat()).isEqualTo(2.5f);
        assertThat(trip.getRefundableAsBoolean()).isTrue();
        assertThat(trip.getNoteAsString()).isEqualTo("window seat");
        assertThat(trip.getBlobAsByteString()).isEqualTo(ByteString.copyFromUtf8("x"));
    }

    @Test
    @DisplayName("Given a Struct and a FieldMask, when set from a map and a list, should read back as a map and a list")
    void given_structAndFieldMask_when_setFromCollections_should_readBack() {
        Trip trip = Trip.newBuilder()
                .setAttributes(Map.of("tier", "gold"))
                .setUpdateMask(List.of("price", "note"))
                .build();

        assertThat(trip.getAttributesAsMap()).isEqualTo(Map.of("tier", "gold"));
        assertThat(trip.getUpdateMaskAsPaths()).containsExactly("price", "note");
    }

    @Test
    @DisplayName(
            "Given enum fields, when set from java.time enums, should read back; unspecified reads as null and null sets unspecified")
    void given_enumFields_when_setFromJavaTime_should_readBack() {
        Trip trip = Trip.newBuilder()
                .setWeekday(DayOfWeek.TUESDAY)
                .setMonth(Month.SEPTEMBER)
                .build();

        assertThat(trip.getWeekdayAsJavaDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(trip.getMonthAsJavaMonth()).isEqualTo(Month.SEPTEMBER);
        assertThat(Trip.getDefaultInstance().getWeekdayAsJavaDayOfWeek()).isNull();
        assertThat(trip.toBuilder().setWeekday((DayOfWeek) null).build().getWeekday())
                .isEqualTo(com.google.type.DayOfWeek.DAY_OF_WEEK_UNSPECIFIED);
    }

    // ── Repeated and map fields ──────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given a repeated field, when values are added one by one and in bulk, should read back as an unmodifiable list in order")
    void given_repeatedField_when_added_should_readBackInOrder() {
        Trip trip = Trip.newBuilder()
                .addHolidays(DAY)
                .addAllHolidaysAsLocalDate(List.of(DAY.plusDays(1), DAY.plusDays(2)))
                .addOpenDays(DayOfWeek.MONDAY)
                .addAllOpenDaysAsJavaDayOfWeek(List.of(DayOfWeek.FRIDAY))
                .build();

        List<LocalDate> holidays = trip.getHolidaysAsLocalDateList();
        assertThat(holidays).containsExactly(DAY, DAY.plusDays(1), DAY.plusDays(2));
        assertThatThrownBy(() -> holidays.add(DAY)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(trip.getOpenDaysAsJavaDayOfWeekList()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        assertThat(Trip.getDefaultInstance().getHolidaysAsLocalDateList()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given map fields with string and int keys, when entries are put with Java-typed values, should read back as maps")
    void given_mapFields_when_put_should_readBackAsMaps() {
        Trip trip = Trip.newBuilder()
                .putCheckpoints("gate", AT)
                .putDaysByLeg(1, DAY)
                .putDaysByLeg(2, DAY.plusDays(1))
                .build();

        assertThat(trip.getCheckpointsAsInstantMap()).containsExactly(Map.entry("gate", AT));
        assertThat(trip.getCheckpointsAsOffsetDateTimeMap().get("gate").toInstant())
                .isEqualTo(AT);
        assertThat(trip.getDaysByLegAsLocalDateMap()).containsEntry(1, DAY).containsEntry(2, DAY.plusDays(1));
    }

    // ── Custom mappings ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given a custom mapping for an application message, when set and read, should go through the named converters")
    void given_customMapping_when_setAndRead_should_useNamedConverters() {
        Trip trip = Trip.newBuilder()
                .setSku(new SkuCode("AB-1"))
                .addExtras(new SkuCode("X-2"))
                .build();

        assertThat(trip.getSku().getCode()).isEqualTo("AB-1");
        assertThat(trip.getSkuAsSkuCode()).isEqualTo(new SkuCode("AB-1"));
        assertThat(trip.getExtrasAsSkuCodeList()).containsExactly(new SkuCode("X-2"));
    }

    // ── Names and layouts protoc treats specially ────────────────────────────────────

    @Test
    @DisplayName("Given field names with a digit and the name class, when accessed, should use protoc's names")
    void given_specialFieldNames_when_accessed_should_useProtocNames() {
        Trip trip = Trip.newBuilder().setLine2XDate(DAY).setClass_(AT).build();

        assertThat(trip.getLine2XDateAsLocalDate()).isEqualTo(DAY);
        assertThat(trip.getClass_AsInstant()).isEqualTo(AT);
    }

    @Test
    @DisplayName("Given oneof and optional fields, when one is set, should read it and leave the others null")
    void given_oneofAndOptional_when_oneSet_should_readItAndLeaveOthersNull() {
        Trip trip = Trip.newBuilder()
                .setReturnsOn(DAY)
                .setCancelledOn(DAY.minusDays(1))
                .build();

        assertThat(trip.getReturnsOnAsLocalDate()).isEqualTo(DAY);
        assertThat(trip.getOpenEndedForAsDuration()).isNull();
        assertThat(trip.getCancelledOnAsLocalDate()).isEqualTo(DAY.minusDays(1));
        assertThat(trip.getEndsCase()).isEqualTo(Trip.EndsCase.RETURNS_ON);
    }

    @Test
    @DisplayName("Given nested message types, when accessed, should have accessors at every depth")
    void given_nestedTypes_when_accessed_should_haveAccessors() {
        Trip.Stop stop = Trip.Stop.newBuilder()
                .setArrives(LocalTime.NOON)
                .setPlatform(Trip.Stop.Platform.newBuilder().setClearedAt(AT))
                .build();

        assertThat(stop.getArrivesAsLocalTime()).isEqualTo(LocalTime.NOON);
        assertThat(stop.getPlatform().getClearedAtAsInstant()).isEqualTo(AT);
    }

    @Test
    @DisplayName(
            "Given a file without java_multiple_files whose message is named like the file, when accessed, should find the classes in the OuterClass file")
    void given_outerClassLayout_when_accessed_should_haveAccessors() {
        LegacyOrder order = LegacyOrder.newBuilder()
                .setPlacedOn(DAY)
                .addLines(LegacyOrder.Line.newBuilder().setShipsOn(DAY.plusDays(3)))
                .build();

        assertThat(order.getPlacedOnAsLocalDate()).isEqualTo(DAY);
        assertThat(order.getLines(0).getShipsOnAsLocalDate()).isEqualTo(DAY.plusDays(3));
    }

    @Test
    @DisplayName("Given an edition 2023 file, when accessed, should have accessors")
    void given_editionFile_when_accessed_should_haveAccessors() {
        EditionEvent event = EditionEvent.newBuilder().setAt(AT).addDays(DAY).build();

        assertThat(event.getAtAsInstant()).isEqualTo(AT);
        assertThat(event.getDaysAsLocalDateList()).containsExactly(DAY);
    }

    @Test
    @DisplayName("Given a builder, when read with Java-typed getters before build, should see what was set")
    void given_builder_when_readBeforeBuild_should_seeWhatWasSet() {
        Trip.Builder builder = Trip.newBuilder().setDepartsOn(DAY).addHolidays(DAY);

        assertThat(builder.getDepartsOnAsLocalDate()).isEqualTo(DAY);
        assertThat(builder.getHolidaysAsLocalDateList()).containsExactly(DAY);
        assertThat(builder.getBookedAtAsInstant()).isNull();
    }
}
