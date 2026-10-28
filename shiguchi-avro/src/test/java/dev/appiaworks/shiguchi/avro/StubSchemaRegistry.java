package dev.appiaworks.shiguchi.avro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A minimal, in-process stand-in for the slice of the Confluent Schema Registry REST API that
 * {@link SchemaRegistryClient} calls: register a schema under a subject, list a subject's
 * versions, fetch one version, and fetch a schema by global id.
 *
 * <p>Ids are assigned per distinct schema text, incrementing from 1, regardless of subject —
 * matching a real registry where the same schema registered under two subjects gets the same id.
 * Every request's "METHOD path" and {@code Authorization} header (if any) are recorded so tests
 * can assert on what the client actually sent, including verifying it did NOT call again when a
 * cache should have been hit.
 */
final class StubSchemaRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DisposableServer server;
    private final AtomicInteger nextId = new AtomicInteger(1);
    // schema text -> id, shared across subjects like a real registry.
    private final Map<String, Integer> idsBySchemaText = new LinkedHashMap<>();
    // subject -> ordered list of {version, id, schema text}
    private final Map<String, List<int[]>> versionsBySubject = new LinkedHashMap<>();
    private final Map<Integer, String> schemaTextById = new LinkedHashMap<>();
    private final List<String> receivedRequests = new ArrayList<>();
    private final List<String> receivedAuthHeaders = new ArrayList<>();

    StubSchemaRegistry() {
        this.server = HttpServer.create()
                .port(0)
                .route(routes -> routes.post(
                                "/subjects/{subject}/versions", (req, res) -> handle(req, res, this::register))
                        .get("/subjects/{subject}/versions", (req, res) -> handle(req, res, this::listVersions))
                        .get("/subjects/{subject}/versions/{version}", (req, res) -> handle(req, res, this::version))
                        .get("/schemas/ids/{id}", (req, res) -> handle(req, res, this::byId)))
                .bindNow();
    }

    private Publisher<Void> handle(
            reactor.netty.http.server.HttpServerRequest req,
            reactor.netty.http.server.HttpServerResponse res,
            Handler handler) {
        synchronized (this) {
            receivedRequests.add(req.method() + " " + req.fullPath());
            String auth = req.requestHeaders().get("Authorization");
            if (auth != null) receivedAuthHeaders.add(auth);
        }
        return req.receive()
                .aggregate()
                .asString(StandardCharsets.UTF_8)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    Result result;
                    synchronized (this) {
                        result = handler.handle(req, body);
                    }
                    res.status(result.status());
                    return res.sendString(Mono.just(result.body())).then();
                });
    }

    @FunctionalInterface
    private interface Handler {
        Result handle(reactor.netty.http.server.HttpServerRequest req, String body);
    }

    private record Result(int status, String body) {}

    private Result register(reactor.netty.http.server.HttpServerRequest req, String body) {
        String subject = req.param("subject");
        String schemaText;
        try {
            JsonNode node = JSON.readTree(body);
            schemaText = node.path("schema").asText();
        } catch (Exception e) {
            return new Result(400, "{\"error_code\":42201,\"message\":\"bad request\"}");
        }
        int id = idsBySchemaText.computeIfAbsent(schemaText, k -> nextId.getAndIncrement());
        schemaTextById.put(id, schemaText);
        List<int[]> versions = versionsBySubject.computeIfAbsent(subject, k -> new ArrayList<>());
        boolean alreadyRegistered = versions.stream().anyMatch(v -> v[1] == id);
        if (!alreadyRegistered) {
            int version = versions.size() + 1;
            versions.add(new int[] {version, id});
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("id", id);
        return new Result(200, out.toString());
    }

    private Result listVersions(reactor.netty.http.server.HttpServerRequest req, String body) {
        String subject = req.param("subject");
        List<int[]> versions = versionsBySubject.get(subject);
        if (versions == null || versions.isEmpty()) {
            return new Result(404, "{\"error_code\":40401,\"message\":\"Subject not found\"}");
        }
        ArrayNode arr = JSON.createArrayNode();
        for (int[] v : versions) arr.add(v[0]);
        return new Result(200, arr.toString());
    }

    private Result version(reactor.netty.http.server.HttpServerRequest req, String body) {
        String subject = req.param("subject");
        int version = Integer.parseInt(req.param("version"));
        List<int[]> versions = versionsBySubject.get(subject);
        int[] match = versions == null
                ? null
                : versions.stream().filter(v -> v[0] == version).findFirst().orElse(null);
        if (match == null) {
            return new Result(404, "{\"error_code\":40402,\"message\":\"Version not found\"}");
        }
        int id = match[1];
        ObjectNode out = JSON.createObjectNode();
        out.put("subject", subject);
        out.put("id", id);
        out.put("version", version);
        out.put("schema", schemaTextById.get(id));
        return new Result(200, out.toString());
    }

    private Result byId(reactor.netty.http.server.HttpServerRequest req, String body) {
        int id = Integer.parseInt(req.param("id"));
        String text = schemaTextById.get(id);
        if (text == null) {
            return new Result(404, "{\"error_code\":40403,\"message\":\"Schema not found\"}");
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("schema", text);
        return new Result(200, out.toString());
    }

    /** Directly registers {@code schemaText} under {@code subject} without going through the client, e.g. to
     * simulate a schema an earlier deployment already registered. Returns the assigned id. */
    synchronized int seedVersion(String subject, String schemaText) {
        int id = idsBySchemaText.computeIfAbsent(schemaText, k -> nextId.getAndIncrement());
        schemaTextById.put(id, schemaText);
        List<int[]> versions = versionsBySubject.computeIfAbsent(subject, k -> new ArrayList<>());
        boolean alreadyRegistered = versions.stream().anyMatch(v -> v[1] == id);
        if (!alreadyRegistered) {
            int version = versions.size() + 1;
            versions.add(new int[] {version, id});
        }
        return id;
    }

    String url() {
        return "http://localhost:" + port();
    }

    int port() {
        return server.port();
    }

    synchronized List<String> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    synchronized List<String> receivedAuthHeaders() {
        return List.copyOf(receivedAuthHeaders);
    }

    void dispose() {
        server.disposeNow();
    }
}
