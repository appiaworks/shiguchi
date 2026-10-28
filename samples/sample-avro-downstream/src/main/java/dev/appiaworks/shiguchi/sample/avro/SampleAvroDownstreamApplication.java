package dev.appiaworks.shiguchi.sample.avro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demonstrates a {@code REST_DOWNSTREAM} backend spoken to in <b>Avro</b>.
 *
 * <p>The backend's OpenAPI document lists {@code avro/binary} next to {@code application/json}
 * on some operations and carries the Avro record as {@code x-avro-schema} on the {@code Item}
 * component. With {@code upstream-content.preferred: avro/binary} the gateway asks those
 * operations for Avro (JSON as fallback), encodes mutation bodies as Avro, and decodes whatever
 * {@code Content-Type} comes back. Callers see the same JSON and GraphQL a JSON backend gives.
 *
 * <p>The backend itself ({@link AvroInventoryBackend}) is an in-process reactor-netty server on
 * {@code :8081} using plain Apache Avro, started before Spring so its document is there when the
 * gateway's route loader runs.
 *
 * <pre>
 * curl -s localhost:8080/graphql -H 'content-type: application/json' \
 *   -d '{"query":"{ getItem(sku: \"42\") { sku name qty price status updatedAt category { name } } }"}'
 * # backend log: GET /items/42  Accept=avro/binary, application/json;q=0.5
 *
 * curl -s localhost:8080/inv/items/42                  # REST passthrough: your own Accept applies
 * curl -s localhost:8080/inv/items/42 -H 'accept: avro/binary' | xxd | head
 * </pre>
 */
@SpringBootApplication
public class SampleAvroDownstreamApplication {

    public static void main(String[] args) {
        AvroInventoryBackend backend = new AvroInventoryBackend(8081);
        backend.start();
        Runtime.getRuntime().addShutdownHook(new Thread(backend::stop, "inventory-backend-shutdown"));
        SpringApplication.run(SampleAvroDownstreamApplication.class, args);
    }
}
