package com.acme.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.shop.proto.Order;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    @DisplayName("Given an order, when printing with default dialect, should render money as proto object")
    void given_order_when_printingWithDefaultDialect_should_renderMoneyAsProtoObject() {
        Order order = Main.order();
        String json = Main.printDefaults(order);
        assertThat(json).isEqualTo(
                "{\"id\":\"o-1001\",\"placedOn\":\"2026-09-22\",\"paidAt\":\"2026-09-22T14:03:11Z\","
                        + "\"total\":{\"currencyCode\":\"EUR\",\"units\":\"41\",\"nanos\":500000000},"
                        + "\"deliveryWindow\":\"7200s\",\"lines\":[{\"sku\":{\"code\":\"AB-1\"},\"quantity\":2,"
                        + "\"unitWeightKg\":\"0.25\"}]}");
    }

    @Test
    @DisplayName("Given an order, when printing with shop dialect, should render money as amount and duration as ISO 8601")
    void given_order_when_printingWithShopDialect_should_renderMoneyAsAmountAndDurationAsIso8601() {
        Order order = Main.order();
        String json = Main.printShop(order);
        assertThat(json).isEqualTo(
                "{\"id\":\"o-1001\",\"placedOn\":\"2026-09-22\",\"paidAt\":\"2026-09-22T14:03:11Z\","
                        + "\"total\":{\"currency\":\"EUR\",\"amount\":\"41.5\"},\"deliveryWindow\":\"PT2H\","
                        + "\"lines\":[{\"sku\":\"AB-1\",\"quantity\":2,\"unitWeightKg\":\"0.25\"}]}");
    }

    @Test
    @DisplayName("Given shop dialect JSON of an order, when parsing and printing, should round-trip successfully")
    void given_shopDialectJson_when_parsing_should_roundTripSuccessfully() {
        Order original = Main.order();
        String shopJson = Main.printShop(original);
        Order parsed = Main.parse(shopJson);
        String reprintedJson = Main.printShop(parsed);
        assertThat(reprintedJson).isEqualTo(shopJson);
    }

    @Test
    @DisplayName("Given client JSON with shop dialect, when parsing, should extract Java types correctly")
    void given_clientJson_when_parsing_should_extractJavaTypesCorrectly() {
        String clientJson = "{\"id\":\"o-1002\",\"placedOn\":\"2026-10-01\",\"total\":{\"currency\":\"USD\",\"amount\":\"9.99\"},"
                + "\"deliveryWindow\":\"PT30M\",\"lines\":[{\"sku\":\"CD-7\",\"quantity\":1}]}";
        Order parsed = Main.parse(clientJson);

        assertThat(parsed.getId()).isEqualTo("o-1002");
        assertThat(parsed.getPlacedOnAsLocalDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(parsed.getDeliveryWindowAsDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(parsed.getLines(0).getSkuAsSkuCode().value()).isEqualTo("CD-7");
        assertThat(parsed.getTotalAsMoneyValue().amount()).isEqualByComparingTo(new BigDecimal("9.99"));
    }

    @Test
    @DisplayName("Given a SKU sent as its proto object, when parsing in the shop dialect, should still accept it")
    void given_skuAsProtoObject_when_parsing_should_stillAcceptIt() {
        Order parsed = Main.parse("{\"lines\":[{\"sku\":{\"code\":\"CD-7\"}}]}");

        assertThat(parsed.getLines(0).getSkuAsSkuCode()).isEqualTo(new SkuCode("CD-7"));
    }

    @Test
    @DisplayName("Given order bytes in wire format, when transcoding, should produce shop dialect JSON")
    void given_orderBytes_when_transcoding_should_produceShopDialectJson() {
        Order order = Main.order();
        byte[] wireFormat = order.toByteArray();
        String json = Main.fromBytes(wireFormat);
        assertThat(json).isEqualTo(Main.printShop(order));
    }

    @Test
    @DisplayName("Given JSON with invalid date format, when parsing, should reject with type-aware message")
    void given_invalidDateFormat_when_parsing_should_rejectWithTypeAwareMessage() {
        String invalidJson = "{\"placedOn\":\"22/09/2026\"}";
        String rejection = Main.rejection(invalidJson);
        assertThat(rejection).isEqualTo(
                "Invalid google.type.Date value '22/09/2026': expected YYYY-MM-DD, YYYY-MM, YYYY or --MM-DD");
    }

    @Test
    @DisplayName("Given JSON with invalid SKU code format, when parsing, should reject with type-aware message")
    void given_invalidSkuFormat_when_parsing_should_rejectWithTypeAwareMessage() {
        String invalidJson = "{\"lines\":[{\"sku\":\"ab1\"}]}";
        String rejection = Main.rejection(invalidJson);
        assertThat(rejection).isEqualTo(
                "Invalid acme.shop.Sku value 'ab1': expected a SKU like AB-1, got ab1");
    }

    @Test
    @DisplayName("Given a partial date with year and month but no day, when converting, should produce YearMonth")
    void given_partialDate_when_converting_should_produceYearMonth() {
        var temporal = Main.partialDate();
        assertThat(temporal).isInstanceOf(YearMonth.class);
        assertThat((YearMonth) temporal).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    @DisplayName("Given an invalid SKU code string, when constructing SkuCode, should throw IllegalArgumentException")
    void given_invalidSkuCodeString_when_constructing_should_throwIllegalArgumentException() {
        assertThatThrownBy(() -> new SkuCode("ab1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a SKU like AB-1, got ab1");
    }
}
