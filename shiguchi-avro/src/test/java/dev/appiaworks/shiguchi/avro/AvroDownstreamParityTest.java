package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.DownstreamOpenApiDocument;
import dev.appiaworks.shiguchi.routing.OpenApiToDescriptor;
import java.io.InputStream;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract the Avro codec has to keep: for the same values, the Avro path and the JSON path
 * arrive at the same {@code Map} after binding through the synthetic message derived from the
 * backend's OpenAPI document. Everything above that Map (GraphQL, JSON rendering, projections)
 * is shared, so equal Maps here mean callers cannot tell the two encodings apart.
 */
@DisplayName("Avro and JSON downstream paths produce identical Maps")
class AvroDownstreamParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Descriptor itemDescriptor;
    private static AvroUpstreamCodec codec;
    private static Schema itemSchema;

    @BeforeAll
    static void deriveDescriptorAndBindSchemas() throws Exception {
        byte[] doc = fixture("/openapi/inventory-avro.json");
        DownstreamOpenApiDocument parsed = DownstreamOpenApiDocument.parse(doc, "application/json");
        OpenApiToDescriptor.Result result = OpenApiToDescriptor.build("inventory", "", parsed);
        itemDescriptor = result.file().findMessageTypeByName("Item");
        assertThat(itemDescriptor).as("Item message derived from the document").isNotNull();

        codec = new AvroUpstreamCodec();
        ServiceDefinition def = new ServiceDefinition();
        def.setName("inventory");
        // Category is declared inline inside Item's x-avro-schema, so only Item is a bound component.
        assertThat(codec.registerService("inventory", def, parsed.componentSchemas()))
                .containsExactly("Item");
        itemSchema = codec.registry().find("inventory", "Item").orElseThrow();
    }

    @Test
    @DisplayName("Given the same item encoded as Avro and as JSON, when both are decoded and bound "
            + "through the synthetic Item message, should yield identical Maps")
    void given_sameItemInAvroAndJson_when_boundThroughSyntheticMessage_should_matchExactly() throws Exception {
        GenericRecord record = new GenericData.Record(itemSchema);
        record.put("sku", "42");
        record.put("name", "Widget");
        record.put("qty", 7L);
        record.put("updatedAt", 1704164645000L); // 2024-01-02T03:04:05Z
        Schema category = itemSchema.getField("category").schema().getTypes().get(1);
        GenericRecord cat = new GenericData.Record(category);
        cat.put("id", 3);
        cat.put("name", "tools");
        record.put("category", cat);
        byte[] avro = AvroFraming.encode(record, itemSchema, ServiceDefinition.AvroWireFormat.BINARY);
        String json = "{\"sku\":\"42\",\"name\":\"Widget\",\"qty\":7,\"updatedAt\":\"2024-01-02T03:04:05Z\","
                + "\"category\":{\"id\":3,\"name\":\"tools\"}}";

        Map<String, Object> viaAvro = bind(asMap(codec.decode("inventory", "Item", false, avro)));
        Map<String, Object> viaJson = bind(asMap(JSON.readValue(json, Map.class)));

        assertThat(viaAvro).isEqualTo(viaJson);
        assertThat(viaAvro).containsEntry("sku", "42").containsEntry("updatedAt", "2024-01-02T03:04:05Z");
    }

    @Test
    @DisplayName("Given a Map the JSON path would send as a body, when encoded as Avro and decoded again, "
            + "should bind to the same Map as the original")
    void given_requestMap_when_roundTrippedThroughAvro_should_bindIdentically() {
        Map<String, Object> body = Map.of("sku", "9", "name", "New", "qty", "1", "updatedAt", "2024-05-06T07:08:09Z");

        byte[] avro = codec.encode("inventory", "Item", body);
        Map<String, Object> decoded = asMap(codec.decode("inventory", "Item", false, avro));

        assertThat(bind(decoded)).isEqualTo(bind(body));
    }

    // ------------------------------------------------------------------

    /** The dispatcher's normalisation step: Map → synthetic message → Map. */
    private static Map<String, Object> bind(Map<String, Object> raw) {
        DynamicMessage message = ProtoMapConverter.dynamicFromMap(raw, itemDescriptor);
        return ProtoMapConverter.dynamicToMap(message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static byte[] fixture(String path) throws Exception {
        try (InputStream in = AvroDownstreamParityTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return in.readAllBytes();
        }
    }
}
