package dev.appiaworks.shiguchi.avro.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The whole Boot 4 gateway (Avro starter + GraphQL starter) in front of a reactor-netty stub
 * whose OpenAPI document advertises {@code avro/binary} next to JSON and carries the Avro schema
 * as {@code x-avro-schema}. The stub speaks plain Apache Avro, not the gateway's codec, so the
 * bytes on the wire are independent of the code under test.
 *
 * <p>Proves: the gateway asks for Avro first and decodes an Avro response into the same GraphQL
 * result a JSON backend gives; it sends a mutation body as Avro; an operation that only declares
 * JSON stays JSON; a backend that ignores {@code Accept} and answers JSON is decoded by the
 * returned {@code Content-Type}; single-object framing is recognised by its header.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("Avro REST_DOWNSTREAM backend end to end (Boot 4)")
class AvroDownstreamEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    /** What the stub saw: "METHOD path accept=... contentType=..." plus decoded request records. */
    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    private static final Schema ITEM = itemSchema();

    private static final DisposableServer STUB = HttpServer.create()
            .port(0)
            .route(routes -> routes.get(
                            "/v3/api-docs",
                            (req, res) -> res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(fixture("/openapi/inventory-avro.json"))))
                    .get("/items/{sku}", (req, res) -> {
                        String accept = req.requestHeaders().get("Accept");
                        RECEIVED.add("GET /items/" + req.param("sku") + " accept=" + accept);
                        if (accept != null && accept.startsWith("avro/binary")) {
                            return res.header("Content-Type", "avro/binary")
                                    .sendByteArray(Mono.just(encode(item(req.param("sku"), "Widget", 7L), false)));
                        }
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.just(
                                        "{\"sku\":\"" + req.param("sku") + "\",\"name\":\"Widget\",\"qty\":7}"));
                    })
                    .get("/legacy/{sku}", (req, res) -> {
                        RECEIVED.add("GET /legacy/" + req.param("sku") + " accept="
                                + req.requestHeaders().get("Accept"));
                        // Ignores Accept: answers JSON although the document promised Avro.
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.just(
                                        "{\"sku\":\"" + req.param("sku") + "\",\"name\":\"Legacy\",\"qty\":1}"));
                    })
                    .get("/framed/{sku}", (req, res) -> {
                        RECEIVED.add("GET /framed/" + req.param("sku"));
                        return res.header("Content-Type", "avro/binary")
                                .sendByteArray(Mono.just(encode(item(req.param("sku"), "Framed", 3L), true)));
                    })
                    .get("/items", (req, res) -> {
                        RECEIVED.add("GET /items accept=" + req.requestHeaders().get("Accept"));
                        return res.header("Content-Type", "application/json")
                                .sendString(Mono.just("[{\"sku\":\"1\",\"qty\":1},{\"sku\":\"2\",\"qty\":2}]"));
                    })
                    .post(
                            "/items",
                            (req, res) -> req.receive()
                                    .aggregate()
                                    .asByteArray()
                                    .flatMap(body -> {
                                        String contentType =
                                                req.requestHeaders().get("Content-Type");
                                        GenericRecord in = decode(body);
                                        RECEIVED.add("POST /items contentType=" + contentType + " sku=" + in.get("sku")
                                                + " name=" + in.get("name") + " qty=" + in.get("qty") + " status="
                                                + in.get("status"));
                                        return res.status(201)
                                                .header("Content-Type", "avro/binary")
                                                .sendByteArray(Mono.just(encode(
                                                        item(
                                                                String.valueOf(in.get("sku")),
                                                                String.valueOf(in.get("name")),
                                                                (Long) in.get("qty")),
                                                        false)))
                                                .then();
                                    })))
            .bindNow();

    /** All {@code services[0].*} keys in one source: Boot binds a list from a single property source. */
    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "inventory");
        registry.add("shiguchi.services[0].mode", () -> "REST_DOWNSTREAM");
        registry.add("shiguchi.services[0].path-prefix", () -> "/inv");
        registry.add("shiguchi.services[0].openapi-url", () -> "http://localhost:" + STUB.port() + "/v3/api-docs");
        registry.add("shiguchi.services[0].upstream-content.preferred", () -> "avro/binary");
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClientAndClearReceived() {
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                // The 5 s default is too tight when the build runs modules in parallel on a small
                // CI pod: the first /v3/api-docs render alone has timed out there.
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        RECEIVED.clear();
    }

    private WebTestClient.BodyContentSpec graphql(String query) {
        return client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    @Test
    @DisplayName("Given an operation advertising avro/binary and JSON, when queried through GraphQL, "
            + "should ask for Avro first and decode the Avro response into the usual fields")
    void given_avroCapableOperation_when_queried_should_preferAvroAndDecode() {
        graphql("{ getItem(sku: \"42\") { sku name qty updatedAt status } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.getItem.sku")
                .isEqualTo("42")
                .jsonPath("$.data.getItem.name")
                .isEqualTo("Widget")
                .jsonPath("$.data.getItem.qty")
                .isEqualTo("7")
                .jsonPath("$.data.getItem.updatedAt")
                .isEqualTo("2024-01-02T03:04:05Z")
                .jsonPath("$.data.getItem.status")
                .isEqualTo("ITEM_STATUS_RETIRED"); // the backend's "retired", as the SDL names it

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0))
                .startsWith("GET /items/42 accept=avro/binary")
                .contains("application/json;q=0.5");
    }

    @Test
    @DisplayName("Given a mutation whose body is declared as avro/binary, when called, "
            + "should send the input as Avro bytes the backend decodes with the same schema")
    void given_avroRequestBody_when_mutated_should_sendAvro() {
        graphql(
                        "mutation { createItem(input: { sku: \"9\", name: \"New\", qty: \"1\", updatedAt: \"2024-05-06T07:08:09Z\", status: ITEM_STATUS_RETIRED }) { sku name qty } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.createItem.sku")
                .isEqualTo("9")
                .jsonPath("$.data.createItem.name")
                .isEqualTo("New");

        assertThat(RECEIVED).containsExactly("POST /items contentType=avro/binary sku=9 name=New qty=1 status=retired");
    }

    @Test
    @DisplayName("Given an operation that only declares JSON, when queried, "
            + "should keep asking for JSON although Avro is the service preference")
    void given_jsonOnlyOperation_when_queried_should_stayJson() {
        graphql("{ listItems { sku } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.listItems.length()")
                .isEqualTo(2);

        assertThat(RECEIVED).containsExactly("GET /items accept=application/json");
    }

    @Test
    @DisplayName("Given a backend that answers JSON to an Avro request, when queried, "
            + "should decode by the returned Content-Type and still succeed")
    void given_backendIgnoringAccept_when_queried_should_decodeByContentType() {
        graphql("{ getLegacyItem(sku: \"1\") { sku name } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.getLegacyItem.name")
                .isEqualTo("Legacy");

        assertThat(RECEIVED.get(0)).startsWith("GET /legacy/1 accept=avro/binary");
    }

    @Test
    @DisplayName("Given a backend using single-object encoding, when queried, "
            + "should recognise the header and fingerprint and decode the datum")
    void given_singleObjectFraming_when_queried_should_decode() {
        graphql("{ getFramedItem(sku: \"5\") { sku name qty } }")
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.getFramedItem.name")
                .isEqualTo("Framed")
                .jsonPath("$.data.getFramedItem.qty")
                .isEqualTo("3");
    }

    // ---------------------------------------------------------------- stub-side Avro (plain Apache Avro)

    private static Schema itemSchema() {
        return new Schema.Parser()
                .parse(
                        "{\"type\":\"record\",\"name\":\"Item\",\"namespace\":\"com.acme.inventory\",\"fields\":["
                                + "{\"name\":\"sku\",\"type\":\"string\"},"
                                + "{\"name\":\"name\",\"type\":[\"null\",\"string\"],\"default\":null},"
                                + "{\"name\":\"qty\",\"type\":\"long\"},"
                                + "{\"name\":\"updatedAt\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},"
                                + "{\"name\":\"status\",\"type\":{\"type\":\"enum\",\"name\":\"ItemStatus\",\"symbols\":[\"active\",\"retired\"]},\"default\":\"active\"},"
                                + "{\"name\":\"category\",\"type\":[\"null\",{\"type\":\"record\",\"name\":\"Category\",\"fields\":["
                                + "{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"name\",\"type\":\"string\"}]}],\"default\":null}]}");
    }

    private static GenericRecord item(String sku, String name, long qty) {
        GenericRecord r = new GenericData.Record(ITEM);
        r.put("sku", sku);
        r.put("name", name);
        r.put("qty", qty);
        r.put("updatedAt", 1704164645000L); // 2024-01-02T03:04:05Z
        r.put("status", new GenericData.EnumSymbol(ITEM.getField("status").schema(), "retired"));
        r.put("category", null);
        return r;
    }

    private static byte[] encode(GenericRecord record, boolean singleObject) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (singleObject) {
                out.write(0xC3);
                out.write(0x01);
                long fp = SchemaNormalization.parsingFingerprint64(ITEM);
                for (int i = 0; i < 8; i++) out.write((int) (fp >>> (8 * i)) & 0xFF);
            }
            BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<GenericRecord>(ITEM).write(record, enc);
            enc.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GenericRecord decode(byte[] bytes) {
        try {
            return new GenericDatumReader<GenericRecord>(ITEM)
                    .read(null, DecoderFactory.get().binaryDecoder(bytes, null));
        } catch (IOException e) {
            throw new IllegalStateException("stub could not decode the request as Item: " + e.getMessage(), e);
        }
    }

    private static String fixture(String path) {
        try (InputStream in = AvroDownstreamEndToEndTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
