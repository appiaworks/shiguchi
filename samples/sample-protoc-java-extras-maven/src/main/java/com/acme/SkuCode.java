package com.acme;

import com.acme.proto.Sku;

/** The application's own type for an {@code acme.Sku}, with the converters the pom's `map` option names. */
public record SkuCode(String value) {

    public static SkuCode fromProto(Sku sku) {
        return new SkuCode(sku.getCode());
    }

    public static Sku toProto(SkuCode code) {
        return Sku.newBuilder().setCode(code.value()).build();
    }
}
