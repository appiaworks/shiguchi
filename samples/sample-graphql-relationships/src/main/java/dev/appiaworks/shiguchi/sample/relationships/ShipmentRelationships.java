package dev.appiaworks.shiguchi.sample.relationships;

import dev.appiaworks.shiguchi.graphql.BatchLoaderDefinition;
import dev.appiaworks.shiguchi.graphql.RelationshipDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Path 3: beans. The loader says how to fetch the children of many parents in one call; the
 * relationship says where in the graph they hang. {@code ShopShipment} already exists as a
 * GraphQL type because {@code shop-openapi.json} has an operation that returns a {@code Shipment};
 * the beans add the data and the {@code ShopOrder.shipments} field, nothing else.
 */
@Configuration(proxyBeanMethods = false)
public class ShipmentRelationships {

    @Bean
    BatchLoaderDefinition shipmentsByOrder(ShipmentClient client) {
        // One call for every order of the operation. Each child map carries its parent's key
        // ("orderId"), which is how the gateway files it under the right order.
        return BatchLoaderDefinition.grouped(
                "ShopShipment", "orderId", (orderIds, metadata) -> client.findByOrders(orderIds));
    }

    @Bean
    RelationshipDefinition orderShipments() {
        return RelationshipDefinition.many("ShopOrder.id", "ShopShipment", "orderId")
                .named("shipments");
    }
}
