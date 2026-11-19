package dev.appiaworks.shiguchi.sample.relationships;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Relationships between types of REST services, declared three ways and with no proto
 * annotation at all:
 *
 * <ul>
 *   <li>{@code shop}: {@code x-shiguchi-*} extensions inside {@code shop-openapi.json};
 *   <li>{@code catalog}: {@code relationships} and {@code rest-batch-loaders} in {@code application.yml};
 *   <li>{@code ShopShipment}: a {@code BatchLoaderDefinition} and a {@code RelationshipDefinition}
 *       bean over {@link ShipmentClient}.
 * </ul>
 *
 * <p>The backend ({@link ShopBackend}) is an in-process reactor-netty server on {@code :8081},
 * started before Spring so its documents are there when the gateway loads its routes.
 */
@SpringBootApplication
public class SampleGraphqlRelationshipsApplication {

    public static void main(String[] args) {
        ShopBackend backend = new ShopBackend(8081);
        backend.start();
        Runtime.getRuntime().addShutdownHook(new Thread(backend::stop, "shop-backend-shutdown"));
        SpringApplication.run(SampleGraphqlRelationshipsApplication.class, args);
    }
}
