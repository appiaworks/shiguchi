package dev.appiaworks.shiguchi.sample.avro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Stand-in for a REST service that speaks Avro: it answers {@code avro/binary} when asked for
 * it (raw datum, schema known to both sides) and JSON otherwise, and decodes Avro request
 * bodies. Plain Apache Avro; nothing of the gateway is used here, so what goes over the wire is
 * what any Avro-capable service would send.
 *
 * <p>Started from {@code main} before the Spring context so the document is there when the
 * gateway's route loader fetches it.
 */
public class AvroInventoryBackend {

    private static final Logger log = LoggerFactory.getLogger(AvroInventoryBackend.class);

    private final int port;
    private final Schema itemSchema;
    private final Map<String, GenericRecord> items = new ConcurrentHashMap<>();
    private final AtomicInteger nextSku = new AtomicInteger(100);
    private volatile DisposableServer server;

    public AvroInventoryBackend(int port) {
        this.port = port;
        this.itemSchema = new Schema.Parser().parse(resource("/item.avsc"));
        items.put("42", item("42", "Widget", 7, "12.50", "ACTIVE", 3, "tools"));
        items.put("43", item("43", "Gadget", 0, "99.99", "RETIRED", 4, "toys"));
    }

    public void start() {
        String openApi = resource("/inventory-openapi.json");
        server = HttpServer.create()
                .port(port)
                .route(routes -> routes.get("/v3/api-docs", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just(openApi)))
                        .get("/items", (req, res) -> {
                            log.info("GET /items  Accept={}", req.requestHeaders().get("Accept"));
                            StringBuilder json = new StringBuilder("[");
                            for (GenericRecord r : items.values()) {
                                if (json.length() > 1) json.append(',');
                                json.append(toJson(r));
                            }
                            return res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(json.append(']').toString()));
                        })
                        .get("/items/{sku}", (req, res) -> {
                            GenericRecord r = items.get(req.param("sku"));
                            log.info("GET /items/{}  Accept={}", req.param("sku"), req.requestHeaders().get("Accept"));
                            if (r == null) {
                                return res.status(404)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"code\":\"NOT_FOUND\",\"message\":\"no such sku\"}"));
                            }
                            return negotiated(req, res, r);
                        })
                        .get("/legacy/{sku}", (req, res) -> {
                            // Ignores Accept on purpose: the gateway decodes by the returned Content-Type.
                            GenericRecord r = items.getOrDefault(req.param("sku"), items.get("42"));
                            log.info("GET /legacy/{}  Accept={} (answering JSON regardless)", req.param("sku"),
                                    req.requestHeaders().get("Accept"));
                            return res.header("Content-Type", "application/json").sendString(Mono.just(toJson(r)));
                        })
                        .post("/items", (req, res) -> req.receive()
                                .aggregate()
                                .asByteArray()
                                .defaultIfEmpty(new byte[0])
                                .flatMap(body -> {
                                    String contentType = req.requestHeaders().get("Content-Type");
                                    log.info("POST /items  Content-Type={} ({} bytes)", contentType, body.length);
                                    GenericRecord in = contentType != null && contentType.startsWith("avro/binary")
                                            ? decode(body)
                                            : null;
                                    String sku = String.valueOf(nextSku.getAndIncrement());
                                    GenericRecord created = item(
                                            sku,
                                            in != null && in.get("name") != null ? in.get("name").toString() : "New item",
                                            in != null ? (Long) in.get("qty") : 1L,
                                            "1.00",
                                            "ACTIVE",
                                            0,
                                            "new");
                                    items.put(sku, created);
                                    return Mono.from(negotiated(req, res.status(201), created));
                                }))
                        .delete("/items/{sku}", (req, res) -> {
                            items.remove(req.param("sku"));
                            return res.status(204).send();
                        }))
                .bindNow();
        log.info("Avro-capable inventory backend listening on http://localhost:{} (OpenAPI at /v3/api-docs)", port);
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
    }

    // ---------------------------------------------------------------- Avro / JSON

    private org.reactivestreams.Publisher<Void> negotiated(
            HttpServerRequest req, HttpServerResponse res, GenericRecord record) {
        String accept = req.requestHeaders().get("Accept");
        if (accept != null && accept.contains("avro/binary")) {
            return res.header("Content-Type", "avro/binary").sendByteArray(Mono.just(encode(record)));
        }
        return res.header("Content-Type", "application/json").sendString(Mono.just(toJson(record)));
    }

    private GenericRecord item(String sku, String name, long qty, String price, String status, int catId, String cat) {
        GenericRecord r = new GenericData.Record(itemSchema);
        r.put("sku", sku);
        r.put("name", name);
        r.put("qty", qty);
        Schema priceSchema = itemSchema.getField("price").schema();
        r.put("price", new Conversions.DecimalConversion()
                .toBytes(new BigDecimal(price), priceSchema, LogicalTypes.decimal(10, 2)));
        r.put("status", new GenericData.EnumSymbol(itemSchema.getField("status").schema(), status));
        r.put("updatedAt", Instant.parse("2024-01-02T03:04:05Z").toEpochMilli());
        Schema categorySchema = itemSchema.getField("category").schema().getTypes().get(1);
        GenericRecord category = new GenericData.Record(categorySchema);
        category.put("id", catId);
        category.put("name", cat);
        r.put("category", category);
        return r;
    }

    private byte[] encode(GenericRecord record) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<GenericRecord>(itemSchema).write(record, enc);
            enc.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private GenericRecord decode(byte[] bytes) {
        try {
            return new GenericDatumReader<GenericRecord>(itemSchema)
                    .read(null, DecoderFactory.get().binaryDecoder(bytes, null));
        } catch (IOException e) {
            throw new IllegalArgumentException("request body is not an Item: " + e.getMessage(), e);
        }
    }

    /** Hand-rolled JSON of the same record, for the JSON side of the negotiation. */
    private String toJson(GenericRecord r) {
        GenericRecord c = (GenericRecord) r.get("category");
        BigDecimal price = new Conversions.DecimalConversion()
                .fromBytes((ByteBuffer) r.get("price"), itemSchema.getField("price").schema(), LogicalTypes.decimal(10, 2));
        return "{\"sku\":\"" + r.get("sku") + "\",\"name\":\"" + r.get("name") + "\",\"qty\":" + r.get("qty")
                + ",\"price\":\"" + price.toPlainString() + "\",\"status\":\"" + r.get("status") + "\",\"updatedAt\":\""
                + Instant.ofEpochMilli((Long) r.get("updatedAt")) + "\",\"category\":{\"id\":" + c.get("id")
                + ",\"name\":\"" + c.get("name") + "\"}}";
    }

    private static String resource(String path) {
        try (InputStream in = AvroInventoryBackend.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
