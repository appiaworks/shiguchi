package com.acme.shop;

import com.acme.shop.kafka.JsonBridge;
import com.acme.shop.kafka.ProtoDeserializer;
import com.acme.shop.kafka.ProtoSerializer;
import com.acme.shop.kafka.WireFormat;
import com.acme.shop.proto.Line;
import com.acme.shop.proto.Order;
import com.google.type.Date;
import dev.appiaworks.protobuf.extras.JsonWritable;
import dev.appiaworks.protobuf.extras.MoneyValue;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAccessor;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * protobuf-java-extras in a plain Java program, one step at a time. Run it with
 * {@code ./gradlew -p samples sample-protobuf-java-extras:run}.
 */
public final class Main {

    /** The gateway's default dialect: google.type values as ISO text, Money as its proto object. */
    static final ProtoJson DEFAULTS = ProtoJson.defaults();

    /**
     * This shop's dialect. Build it once and share it: a ProtoJson is immutable and thread-safe, and
     * independent of any other instance in the JVM.
     */
    static final ProtoJson SHOP = ProtoJson.builder()
            .moneyAsAmount(true)
            .durationFormat(ProtoJson.DurationFormat.ISO_8601)
            .scalar(Codecs.SKU)
            .build();

    private Main() {}

    /** 1. Build a message with Java types, through the accessors protoc-gen-java-extras added. */
    static Order order() {
        return Order.newBuilder()
                .setId("o-1001")
                .setPlacedOn(LocalDate.of(2026, 9, 22))
                .setPaidAt(Instant.parse("2026-09-22T14:03:11Z"))
                .setTotal(new MoneyValue("EUR", new BigDecimal("41.50")))
                .setDeliveryWindow(Duration.ofHours(2))
                .addLines(Line.newBuilder()
                        .setSku(new SkuCode("AB-1"))
                        .setQuantity(2)
                        .setUnitWeightKg(new BigDecimal("0.25")))
                .build();
    }

    /** 2. Print it in the gateway's default dialect. */
    static String printDefaults(Order order) {
        return DEFAULTS.print(order);
    }

    /** 3. Print it in the shop's dialect: Money as an amount, ISO durations, SKUs as codes. */
    static String printShop(Order order) {
        return SHOP.print(order);
    }

    /** 4. Read JSON a client sent, then use the values as Java types. */
    static Order parse(String json) {
        return SHOP.parse(json, Order.getDefaultInstance());
    }

    /**
     * 5. Serialized bytes straight to JSON, as a consumer holding a Kafka record or a gRPC payload
     * would: no Order object is built on the way.
     */
    static String fromBytes(byte[] wire) {
        return new String(SHOP.transcode(wire, Order.getDescriptor()), StandardCharsets.UTF_8);
    }

    /** 6. A malformed value is refused with a message that names the type and the value. */
    static String rejection(String json) {
        try {
            parse(json);
            return "accepted";
        } catch (ProtoJsonException e) {
            return e.getMessage();
        }
    }

    /** 7. ProtoTypes on its own: a Date with no day is a YearMonth, not a LocalDate. */
    static TemporalAccessor partialDate() {
        return ProtoTypes.toTemporal(
                Date.newBuilder().setYear(2026).setMonth(9).build());
    }

    /**
     * 8. Kafka, both ways round: the same order produced as proto binary and as JSON, and one
     * deserializer reading either, told apart by the content-type header the serializer stamps.
     */
    static String kafkaRoundTrip(Order order, WireFormat format) {
        RecordHeaders headers = new RecordHeaders();
        byte[] value = new ProtoSerializer<Order>(SHOP, format).serialize("orders", headers, order);
        Order read = new ProtoDeserializer<>(SHOP, WireFormat.PROTOBUF, Order.getDefaultInstance())
                .deserialize("orders", headers, value);
        return format + ": " + value.length + " bytes, " + headers.lastHeader(WireFormat.HEADER).key() + "="
                + new String(headers.lastHeader(WireFormat.HEADER).value(), StandardCharsets.UTF_8)
                + ", read back equal: " + read.equals(order);
    }

    /** 9. A bridge republishing a binary topic as JSON, through the transcoder: no Order is built. */
    static String bridge(byte[] protobufRecord) {
        return new String(
                new JsonBridge(SHOP, Order.getDescriptor()).toJson(protobufRecord, new RecordHeaders()),
                StandardCharsets.UTF_8);
    }

    /**
     * 10. Which paths print and parse: the Order's own methods from the plugin; on Java 25+ with protobuf-java-extras-classfile on the
     * classpath, a serializer generated per message type; otherwise the reflective converter,
     * with the same JSON.
     */
    static String serializers() {
        int java = Runtime.version().feature();
        // Printing and parsing an Order go through the writeJson/mergeJson the plugin generated
        // into the class, on any Java; DynamicMessages and parse(text, descriptor) go through
        // serializers generated at run time on Java 25+, else the reflective converter.
        String carried = Order.getDefaultInstance() instanceof JsonWritable ? "generated into the class" : "absent";
        String dynamic = SHOP.usesCompiledSerializers()
                ? "generated at run time (Java " + java + ")"
                : "reflective (Java " + java + "; generated ones need 25+ and protobuf-java-extras-classfile)";
        return "Order's own writer and reader " + carried + "; DynamicMessage serializers " + dynamic;
    }

    public static void main(String[] args) {
        Order order = order();
        System.out.println("1. Built with Java types:  placed on " + order.getPlacedOnAsLocalDate()
                + ", total " + order.getTotalAsMoneyValue());
        System.out.println("2. Gateway defaults:       " + printDefaults(order));
        System.out.println("3. Shop dialect:           " + printShop(order));

        Order incoming = parse("""
                {"id":"o-1002","placedOn":"2026-10-01","total":{"currency":"USD","amount":"9.99"},
                 "deliveryWindow":"PT30M","lines":[{"sku":"CD-7","quantity":1}]}""");
        System.out.println("4. Parsed:                 " + incoming.getDeliveryWindowAsDuration() + " window, sku "
                + incoming.getLines(0).getSkuAsSkuCode().value() + ", total "
                + incoming.getTotalAsMoneyValue().amount());

        System.out.println("5. From bytes:             " + fromBytes(order.toByteArray()));
        System.out.println("6. Malformed:              " + rejection("{\"placedOn\":\"22/09/2026\"}"));
        System.out.println("                           " + rejection("{\"lines\":[{\"sku\":\"ab1\"}]}"));
        System.out.println("7. Partial date:           " + partialDate());
        System.out.println("8. Kafka:                  " + kafkaRoundTrip(order, WireFormat.PROTOBUF));
        System.out.println("                           " + kafkaRoundTrip(order, WireFormat.JSON));
        System.out.println("9. Binary topic as JSON:   " + bridge(order.toByteArray()));
        System.out.println("10. Serializers:           " + serializers());
    }
}
