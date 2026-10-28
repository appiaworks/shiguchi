package dev.appiaworks.protobuf.extras.protoc.fixture;

import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Sku;

/** An application's own value type, with the two static converters the `map` option names. */
public record SkuCode(String value) {

    public static SkuCode fromProto(Sku sku) {
        return new SkuCode(sku.getCode());
    }

    public static Sku toProto(SkuCode code) {
        return Sku.newBuilder().setCode(code.value()).build();
    }
}
