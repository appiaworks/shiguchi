package dev.appiaworks.shiguchi.sample.merge;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A plain HTTP order backend: no gRPC, no proto, an OpenAPI document at {@code /v3/api-docs} and a
 * JSON array at {@code /orders}. The gateway registers it as a {@code REST_DOWNSTREAM} service and
 * the merge route reads it next to the two gRPC shards.
 *
 * <p>It stands in for the service nobody will ever port: it knows nothing about {@code
 * sort_fields} or page tokens, so the gateway always reads it whole and sorts it in memory — the
 * same cost as {@code orders-us}, for the same reason.
 */
public class LegacyOrderHttpServer {

    private static final Logger log = LoggerFactory.getLogger(LegacyOrderHttpServer.class);

    private final int port;
    private final List<DynamicMessage> rows;
    private DisposableServer server;

    public LegacyOrderHttpServer(int port, List<DynamicMessage> rows) {
        this.port = port;
        this.rows = rows;
    }

    public void start() {
        String document = resource("/legacy-openapi.json");
        String orders = toJsonArray(rows);
        server = HttpServer.create()
                .port(port)
                .route(routes -> routes.get("/v3/api-docs", (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(Mono.just(document)))
                        .get("/orders", (req, res) -> {
                            // Whatever the query string says, the whole table comes back: this
                            // backend has no notion of the gateway's sort or page conventions.
                            log.info("legacy backend: GET {} → {} rows", req.uri(), rows.size());
                            return res.header("Content-Type", "application/json")
                                    .sendString(Mono.just(orders));
                        }))
                .bindNow();
        log.info("legacy backend listening on :{} (plain JSON over HTTP — gateway materialises it)", port);
    }

    public void stop() {
        if (server != null) server.disposeNow();
    }

    private static String toJsonArray(List<DynamicMessage> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            DynamicMessage m = rows.get(i);
            Descriptor d = m.getDescriptorForType();
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"")
                    .append(m.getField(d.findFieldByName("id")))
                    .append("\",\"customer\":\"")
                    .append(m.getField(d.findFieldByName("customer")))
                    .append("\",\"placed_at\":")
                    .append(m.getField(d.findFieldByName("placed_at")))
                    .append(",\"total_cents\":")
                    .append(m.getField(d.findFieldByName("total_cents")))
                    .append(",\"region\":\"")
                    .append(m.getField(d.findFieldByName("region")))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String resource(String path) {
        try (InputStream in = LegacyOrderHttpServer.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
