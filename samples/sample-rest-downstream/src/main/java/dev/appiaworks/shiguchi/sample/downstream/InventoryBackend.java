package dev.appiaworks.shiguchi.sample.downstream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Stand-in for a legacy REST service: a few JSON endpoints plus the OpenAPI document that
 * describes them, served exactly where a springdoc-enabled service would serve it.
 *
 * <p>Started from {@code main} before the Spring context so the document is there when the
 * gateway's route loader fetches it. If a backend is down at startup the gateway retries once
 * the application is ready and again on the refresh schedule.
 */
public class InventoryBackend {

    private static final Logger log = LoggerFactory.getLogger(InventoryBackend.class);

    private final int port;
    private final Map<String, String> items = new ConcurrentHashMap<>();
    private final AtomicInteger nextSku = new AtomicInteger(100);
    private volatile DisposableServer server;

    public InventoryBackend(int port) {
        this.port = port;
        items.put("42", "{\"sku\":\"42\",\"name\":\"Widget\",\"qty\":7}");
        items.put("43", "{\"sku\":\"43\",\"name\":\"Gadget\",\"qty\":0}");
    }

    public void start() {
        String openApi = resource("/inventory-openapi.json");
        server = HttpServer.create()
                .port(port)
                .route(routes -> routes.get("/v3/api-docs", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just(openApi)))
                        .get("/items", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just("[" + String.join(",", items.values()) + "]")))
                        .get("/items/{sku}", (req, res) -> {
                            String body = items.get(req.param("sku"));
                            if (body == null) {
                                return res.status(404)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"code\":\"NOT_FOUND\",\"message\":\"no such sku\"}"));
                            }
                            return res.header("Content-Type", "application/json").sendString(Mono.just(body));
                        })
                        .post("/items", (req, res) -> req.receive()
                                .aggregate()
                                .asString()
                                .defaultIfEmpty("{}")
                                .flatMap(json -> {
                                    String sku = String.valueOf(nextSku.getAndIncrement());
                                    String created = "{\"sku\":\"" + sku + "\",\"name\":\"New item\",\"qty\":1}";
                                    items.put(sku, created);
                                    return res.status(201)
                                            .header("Content-Type", "application/json")
                                            .sendString(Mono.just(created))
                                            .then();
                                }))
                        .delete("/items/{sku}", (req, res) -> {
                            items.remove(req.param("sku"));
                            return res.status(204).send();
                        }))
                .bindNow();
        log.info("Inventory REST backend listening on http://localhost:{} (OpenAPI at /v3/api-docs)", port);
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
    }

    private static String resource(String path) {
        try (InputStream in = InventoryBackend.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
