package com.acme.shop;

import com.acme.shop.proto.Sku;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ScalarSchema;

/** Scalar types of this application's own. */
final class Codecs {

    private Codecs() {}

    /**
     * {@code acme.shop.Sku} as its code: {@code "AB-1"} instead of {@code {"code":"AB-1"}}, in every
     * message that has one, at any depth. Malformed text throws {@link IllegalArgumentException},
     * which {@code ProtoJson} reports as a {@code ProtoJsonException} naming the type and the value.
     */
    static final ProtoScalarCodec SKU = ProtoScalarCodec.ofString(
                    Sku.getDefaultInstance(), Sku::getCode, text -> SkuCode.toProto(new SkuCode(text)))
            // For the schemas a gateway publishes; ProtoJson reads and writes the same without it.
            .withSchema(ScalarSchema.string()
                    .withPattern("[A-Z]{2}-\\d+")
                    .withDescription("Stock-keeping unit")
                    .withExample("AB-1")
                    .withGraphQlName("Sku"));
}
