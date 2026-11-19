package dev.appiaworks.shiguchi.sample.relationships;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRoutes;

/**
 * Stand-in for two legacy REST services on one port: a shop ({@code customers}, {@code orders})
 * and a catalog ({@code products}, {@code reviews}), each with the OpenAPI document that
 * describes it. Every request to a data endpoint is recorded in {@link #requests()}, which is how
 * the sample (and its test) shows that N parents cost one call.
 *
 * <p>The data endpoints take an optional batch parameter ({@code ?ids=c1,c2},
 * {@code ?customerIds=c1,c2}, ...), which is what the gateway's batch loaders send.
 */
public class ShopBackend {

    private static final Logger log = LoggerFactory.getLogger(ShopBackend.class);

    private static final List<String[]> CUSTOMERS = List.of(
            new String[] {"c1", "Ada"}, new String[] {"c2", "Grace"}, new String[] {"c3", "Edsger"});
    /** id, customerId, total */
    private static final List<String[]> ORDERS = List.of(
            new String[] {"o1", "c1", "30"},
            new String[] {"o2", "c2", "15"},
            new String[] {"o3", "c1", "8"},
            new String[] {"o4", "c3", "42"});

    private static final List<String[]> PRODUCTS =
            List.of(new String[] {"p1", "Widget"}, new String[] {"p2", "Gadget"}, new String[] {"p3", "Gizmo"});
    /** id, productId, stars */
    private static final List<String[]> REVIEWS = List.of(
            new String[] {"r1", "p1", "5"},
            new String[] {"r2", "p1", "3"},
            new String[] {"r3", "p2", "4"},
            new String[] {"r4", "p3", "2"},
            new String[] {"r5", "p3", "5"});

    private final int port;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile DisposableServer server;

    public ShopBackend(int port) {
        this.port = port;
    }

    public void start() {
        String shopDoc = resource("/shop-openapi.json");
        String catalogDoc = resource("/catalog-openapi.json");
        server = HttpServer.create()
                .port(port)
                .route(routes -> {
                    document(routes, "/shop-openapi.json", shopDoc);
                    document(routes, "/catalog-openapi.json", catalogDoc);
                    data(routes, "/customers", "ids", null, CUSTOMERS, row -> json("id", row[0], "name", row[1]));
                    data(routes, "/orders", "customerIds", 1, ORDERS, row -> "{\"id\":\"" + row[0]
                            + "\",\"customerId\":\"" + row[1] + "\",\"total\":" + row[2] + "}");
                    data(routes, "/products", "ids", null, PRODUCTS, row -> json("id", row[0], "name", row[1]));
                    data(routes, "/reviews", "productIds", 1, REVIEWS, row -> "{\"id\":\"" + row[0]
                            + "\",\"productId\":\"" + row[1] + "\",\"stars\":" + row[2] + "}");
                })
                .bindNow();
        log.info("Shop and catalog REST backend listening on http://localhost:{}", port());
    }

    public void stop() {
        if (server != null) {
            server.disposeNow();
            server = null;
        }
    }

    /** The port the server is bound to (useful when constructed with port 0). */
    public int port() {
        return server != null ? server.port() : port;
    }

    /** Every data request received, as {@code "GET /orders?customerIds=c1,c2"}, in arrival order. */
    public List<String> requests() {
        return new ArrayList<>(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    private static void document(HttpServerRoutes routes, String path, String body) {
        routes.get(path, (req, res) -> res.header("Content-Type", "application/json")
                .sendString(Mono.just(body)));
    }

    /**
     * A list endpoint. {@code batchParam} holds comma separated keys; they are matched against
     * column {@code keyColumn} (the id column, 0, when null). Without the parameter every row is returned.
     */
    private void data(
            HttpServerRoutes routes,
            String path,
            String batchParam,
            Integer keyColumn,
            List<String[]> rows,
            Function<String[], String> toJson) {
        int column = keyColumn == null ? 0 : keyColumn;
        routes.get(path, (req, res) -> {
            requests.add("GET " + req.uri());
            String raw = req.uri().contains("?") ? req.uri().substring(req.uri().indexOf('?') + 1) : "";
            List<String> wanted = null;
            for (String pair : raw.split("&")) {
                if (pair.startsWith(batchParam + "=")) {
                    wanted = List.of(pair.substring(batchParam.length() + 1).split(","));
                }
            }
            List<String> json = new ArrayList<>();
            for (String[] row : rows) {
                if (wanted == null || wanted.contains(row[column])) json.add(toJson.apply(row));
            }
            return res.header("Content-Type", "application/json")
                    .sendString(Mono.just("[" + String.join(",", json) + "]"));
        });
    }

    private static String json(String k1, String v1, String k2, String v2) {
        return "{\"" + k1 + "\":\"" + v1 + "\",\"" + k2 + "\":\"" + v2 + "\"}";
    }

    private static String resource(String path) {
        try (InputStream in = ShopBackend.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
