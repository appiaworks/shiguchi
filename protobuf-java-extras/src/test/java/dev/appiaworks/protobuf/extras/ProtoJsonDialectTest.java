package dev.appiaworks.protobuf.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Int64Value;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.rpc.RetryInfo;
import com.google.type.Date;
import com.google.type.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each {@link ProtoJson} speaks the dialect its builder was given, and a message whose JSON form
 * is a single value prints and parses as that value at the top level.
 */
class ProtoJsonDialectTest {

    private static final Date DAY = ProtoTypes.date(LocalDate.of(2026, 9, 22));
    private static final Money PRICE = ProtoTypes.money(new MoneyValue("USD", new java.math.BigDecimal("12.34")));
    private static final RetryInfo RETRY = RetryInfo.newBuilder()
            .setRetryDelay(ProtoTypes.duration(Duration.ofMinutes(90)))
            .build();

    // ── Single-value messages at the top level ───────────────────────────────────────

    @Test
    @DisplayName("Given a Timestamp, when printed and parsed on its own, should be RFC 3339 text and round-trip")
    void given_timestamp_when_printedAlone_should_beRfc3339Text() {
        Timestamp at = ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z"));

        String text = ProtoJson.defaults().print(at);

        assertThat(text).isEqualTo("\"2026-09-22T14:03:11Z\"");
        assertThat(ProtoJson.defaults().parse(text, Timestamp.getDefaultInstance()))
                .isEqualTo(at);
    }

    @Test
    @DisplayName(
            "Given a Date, when printed with the default dialect, should be ISO text; with proto3, should be its object")
    void given_date_when_printedAlone_should_followDialect() {
        assertThat(ProtoJson.defaults().print(DAY)).isEqualTo("\"2026-09-22\"");
        assertThat(ProtoJson.proto3().print(DAY)).isEqualTo("{\"year\":2026,\"month\":9,\"day\":22}");
    }

    @Test
    @DisplayName(
            "Given the default dialect, when a Date is parsed from ISO text or from its object form, should build the same Date")
    void given_defaults_when_dateParsedFromTextOrObject_should_buildSameDate() {
        ProtoJson json = ProtoJson.defaults();

        assertThat(json.parse("\"2026-09-22\"", Date.getDefaultInstance())).isEqualTo(DAY);
        assertThat(json.parse("{\"year\":2026,\"month\":9,\"day\":22}", Date.getDefaultInstance()))
                .isEqualTo(DAY);
    }

    @Test
    @DisplayName(
            "Given proto3, when a Date is parsed from ISO text, should throw because the type is a plain message there")
    void given_proto3_when_dateParsedFromText_should_throw() {
        assertThatThrownBy(() -> ProtoJson.proto3().parse("\"2026-09-22\"", Date.getDefaultInstance()))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("expected a JSON object");
    }

    @Test
    @DisplayName(
            "Given a malformed Date at the top level, when parsed, should throw ProtoJsonException naming the type and value")
    void given_malformedTopLevelDate_when_parsed_should_throw() {
        assertThatThrownBy(() -> ProtoJson.defaults().parse("\"2026-02-30\"", Date.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.typeName()).isEqualTo("google.type.Date");
                    assertThat(e.value()).isEqualTo("2026-02-30");
                });
    }

    @Test
    @DisplayName("Given JSON null for a single-value message, when parsed, should give the default instance")
    void given_jsonNull_when_singleValueMessageParsed_should_giveDefaultInstance() {
        assertThat(ProtoJson.defaults().parse("null", Timestamp.getDefaultInstance()))
                .isEqualTo(Timestamp.getDefaultInstance());
    }

    @Test
    @DisplayName("Given an Int64Value, when printed, should be a string by default and a number with int64AsNumber")
    void given_int64Value_when_printed_should_followInt64Switch() {
        assertThat(ProtoJson.defaults().print(Int64Value.of(42))).isEqualTo("\"42\"");
        assertThat(ProtoJson.builder().int64AsNumber(true).build().print(Int64Value.of(42)))
                .isEqualTo("42");
    }

    @Test
    @DisplayName("Given a Struct, when printed or mapped, should be the JSON object it holds")
    void given_struct_when_printedOrMapped_should_beItsObject() {
        Struct struct = ProtoTypes.struct(Map.of("a", "x"));

        assertThat(ProtoJson.defaults().print(struct)).isEqualTo("{\"a\":\"x\"}");
        assertThat(ProtoJson.defaults().toMap(struct)).isEqualTo(Map.of("a", "x"));
        assertThat(ProtoJson.defaults().fromMap(Map.of("a", "x"), Struct.getDefaultInstance()))
                .isEqualTo(struct);
    }

    @Test
    @DisplayName("Given a message written as one text value, when asked for a map, should refuse and point to print")
    void given_singleValueMessage_when_toMap_should_refuse() {
        assertThatThrownBy(() -> ProtoJson.defaults().toMap(DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("google.type.Date")
                .hasMessageContaining("use print");
    }

    // ── Builder switches ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given moneyAsAmount, when Money is printed, mapped and parsed, should use currency and amount")
    void given_moneyAsAmount_when_roundTripped_should_useCurrencyAndAmount() {
        ProtoJson json = ProtoJson.builder().moneyAsAmount(true).build();

        assertThat(json.print(PRICE)).isEqualTo("{\"currency\":\"USD\",\"amount\":\"12.34\"}");
        assertThat(json.toMap(PRICE)).isEqualTo(Map.of("currency", "USD", "amount", "12.34"));
        assertThat(json.parse("{\"currency\":\"USD\",\"amount\":\"12.34\"}", Money.getDefaultInstance()))
                .isEqualTo(PRICE);
    }

    @Test
    @DisplayName("Given the default dialect, when Money is printed, should keep units and nanos")
    void given_defaults_when_moneyPrinted_should_keepUnitsAndNanos() {
        assertThat(ProtoJson.defaults().print(PRICE))
                .isEqualTo("{\"currencyCode\":\"USD\",\"units\":\"12\",\"nanos\":340000000}");
    }

    @Test
    @DisplayName(
            "Given durationFormat ISO_8601, when a Duration field is printed, should be ISO text; by default the proto form")
    void given_durationFormat_when_printed_should_followFormat() {
        ProtoJson iso = ProtoJson.builder()
                .durationFormat(ProtoJson.DurationFormat.ISO_8601)
                .build();

        assertThat(iso.print(RETRY)).isEqualTo("{\"retryDelay\":\"PT1H30M\"}");
        assertThat(ProtoJson.defaults().print(RETRY)).isEqualTo("{\"retryDelay\":\"5400s\"}");
    }

    @Test
    @DisplayName(
            "Given acceptIso8601Duration, when ISO duration text is parsed, should be read when on and rejected when off")
    void given_acceptIso8601Duration_when_isoTextParsed_should_followSwitch() {
        String text = "{\"retryDelay\":\"PT1H30M\"}";

        assertThat(ProtoJson.defaults().parse(text, RetryInfo.getDefaultInstance()))
                .isEqualTo(RETRY);
        assertThatThrownBy(() -> ProtoJson.builder()
                        .acceptIso8601Duration(false)
                        .build()
                        .parse(text, RetryInfo.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        ProtoJsonException.class, e -> assertThat(e.typeName()).isEqualTo("google.protobuf.Duration"));
        assertThatThrownBy(() -> ProtoJson.proto3().parse(text, RetryInfo.getDefaultInstance()))
                .isInstanceOf(ProtoJsonException.class);
    }

    @Test
    @DisplayName("Given the builder's defaults, when built, should speak the same dialect as defaults()")
    void given_untouchedBuilder_when_built_should_matchDefaults() {
        ProtoJson built = ProtoJson.builder().build();

        assertThat(built.print(DAY)).isEqualTo(ProtoJson.defaults().print(DAY));
        assertThat(built.print(PRICE)).isEqualTo(ProtoJson.defaults().print(PRICE));
        assertThat(built.print(RETRY)).isEqualTo(ProtoJson.defaults().print(RETRY));
    }
}
