package com.acme.shop;

import com.acme.shop.proto.Sku;

/**
 * The Java side of {@code acme.shop.Sku}. The {@code javaExtras { map(..) }} block in
 * {@code build.gradle.kts} names this record and its two converters, and the generated classes gain
 * {@code getSkuAsSkuCode()} and {@code setSku(SkuCode)}.
 */
public record SkuCode(String value) {

    public SkuCode {
        if (value == null || !value.matches("[A-Z]{2}-\\d+")) {
            throw new IllegalArgumentException("expected a SKU like AB-1, got " + value);
        }
    }

    public static SkuCode fromProto(Sku sku) {
        return new SkuCode(sku.getCode());
    }

    public static Sku toProto(SkuCode code) {
        return Sku.newBuilder().setCode(code.value()).build();
    }
}
