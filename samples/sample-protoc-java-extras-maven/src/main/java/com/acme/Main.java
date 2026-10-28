package com.acme;

import com.acme.proto.Order;
import dev.appiaworks.protobuf.extras.MoneyValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Builds and reads an order through the accessors protoc-gen-java-extras added. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Order order = Order.newBuilder()
                .setSku(new SkuCode("AB-1234"))
                .setPlacedOn(LocalDate.of(2026, 9, 22))
                .setPaidAt(Instant.parse("2026-09-22T14:03:11Z"))
                .setTotal(new MoneyValue("USD", new BigDecimal("12.34")))
                .addAllDeliveryDaysAsLocalDate(List.of(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25)))
                .build();

        System.out.println("sku       " + order.getSkuAsSkuCode().value());
        System.out.println("placed on " + order.getPlacedOnAsLocalDate());
        System.out.println("paid at   " + order.getPaidAtAsInstant());
        System.out.println("total     " + order.getTotalAsMoneyValue().amount() + " "
                + order.getTotalAsMoneyValue().currencyCode());
        System.out.println("delivery  " + order.getDeliveryDaysAsLocalDateList());
    }
}
