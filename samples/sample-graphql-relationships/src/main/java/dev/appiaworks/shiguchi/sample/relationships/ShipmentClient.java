package dev.appiaworks.shiguchi.sample.relationships;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * A hand-written client the gateway knows nothing about: a database, an SDK, a service that has
 * no OpenAPI document. It answers in the JSON form of the entity, the {@code Map} a Jackson
 * client produces, with the JSON field names. {@link ShipmentRelationships} wires it to the
 * graph.
 */
@Component
public class ShipmentClient {

    private static final List<Map<String, Object>> SHIPMENTS = List.of(
            shipment("s1", "o1", "DHL", "DELIVERED"),
            shipment("s2", "o1", "DHL", "IN_TRANSIT"),
            shipment("s3", "o2", "UPS", "PACKED"),
            shipment("s4", "o4", "UPS", "DELIVERED"));

    private final List<List<String>> calls = new CopyOnWriteArrayList<>();

    /** The shipments of every given order, in one call. */
    public Mono<List<Map<String, Object>>> findByOrders(List<String> orderIds) {
        calls.add(List.copyOf(orderIds));
        List<Map<String, Object>> found = new ArrayList<>();
        for (Map<String, Object> shipment : SHIPMENTS) {
            if (orderIds.contains(shipment.get("orderId"))) found.add(shipment);
        }
        return Mono.just(found);
    }

    /** The order-id lists this client was asked for, one entry per call. */
    public List<List<String>> calls() {
        return new ArrayList<>(calls);
    }

    public void clearCalls() {
        calls.clear();
    }

    private static Map<String, Object> shipment(String id, String orderId, String carrier, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("orderId", orderId);
        m.put("carrier", carrier);
        m.put("status", status);
        return m;
    }
}
