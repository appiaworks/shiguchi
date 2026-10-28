package dev.appiaworks.shiguchi.cursus.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The HTTP transports of cursus against a scripted fake gateway ({@code com.sun.net.httpserver}). */
@DisplayName("cursus against a fake gateway")
class CursusTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One request the fake saw. */
    record Seen(String method, String uri, Map<String, String> headers, String body) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    /** Registers a handler that records every request before the scripted answer. */
    private void on(String path, BiConsumer<HttpExchange, Seen> handler) {
        server.createContext(path, exchange -> {
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen request = new Seen(
                    exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers, body);
            seen.add(request);
            try {
                handler.accept(exchange, request);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void sse(HttpExchange exchange, String frames) {
        try {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(frames.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void status(HttpExchange exchange, int code, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private CliRun.Result sub(String... args) {
        String[] all = java.util.Arrays.copyOf(args, args.length + 4);
        all[args.length] = "--url";
        all[args.length + 1] = url;
        all[args.length + 2] = "--backoff-ms";
        all[args.length + 3] = "10";
        return CliRun.run(all);
    }

    @Test
    @DisplayName("GIVEN a stream of two events WHEN sub reads them SHOULD print one NDJSON line each and exit 0")
    void given_twoEvents_when_sub_should_printNdjson() throws Exception {
        on("/push/subscribe", (ex, req) -> sse(ex, """
                id: 1
                event: orders:1
                data: {"status":"NEW"}

                id: 2
                event: orders:1
                data: {"status":"PAID"}

                """));

        CliRun.Result result = sub("sub", "orders:1", "-n", "2");

        assertThat(result.code()).isZero();
        assertThat(result.lines()).hasSize(2);
        JsonNode first = JSON.readTree(result.lines().get(0));
        assertThat(first.get("topic").asText()).isEqualTo("orders:1");
        assertThat(first.get("id").asText()).isEqualTo("1");
        assertThat(first.get("data").get("status").asText()).isEqualTo("NEW");
        assertThat(seen.get(0).uri()).isEqualTo("/push/subscribe?topic=orders%3A1");
    }

    @Test
    @DisplayName(
            "GIVEN a stream that closes after one event WHEN sub reconnects SHOULD send Last-Event-ID of the last id seen")
    void given_closedStream_when_subReconnects_should_resumeWithLastEventId() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        on("/push/subscribe", (ex, req) -> {
            if (connections.incrementAndGet() == 1) {
                sse(
                        ex,
                        "id: 41\nevent: orders:1\ndata: {\"n\":1}\n\nevent: close\ndata: {\"reason\":\"rebalance\"}\n\n");
            } else {
                sse(ex, "id: 42\nevent: orders:1\ndata: {\"n\":2}\n\n");
            }
        });

        CliRun.Result result = sub("sub", "orders:1", "-n", "2");

        assertThat(result.code()).isZero();
        assertThat(result.lines()).hasSize(2);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).headers()).doesNotContainKey("last-event-id");
        assertThat(seen.get(1).headers()).containsEntry("last-event-id", "41");
        assertThat(result.err()).contains("reconnecting");
    }

    @Test
    @DisplayName("GIVEN an --after id WHEN sub connects SHOULD send it as Last-Event-ID")
    void given_afterId_when_subConnects_should_sendLastEventId() {
        on("/push/subscribe", (ex, req) -> sse(ex, "id: 8\nevent: orders:1\ndata: {}\n\n"));

        CliRun.Result result = sub("sub", "orders:1", "--after", "7", "-n", "1");

        assertThat(result.code()).isZero();
        assertThat(seen.get(0).headers()).containsEntry("last-event-id", "7");
    }

    @Test
    @DisplayName("GIVEN a reset event WHEN sub reads it SHOULD print a reset line and keep reading the same stream")
    void given_resetEvent_when_sub_should_printResetAndContinue() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        on("/push/subscribe", (ex, req) -> {
            connections.incrementAndGet();
            sse(
                    ex,
                    "event: reset\ndata: {\"topic\":\"orders:1\",\"reason\":\"history_gap\"}\n\n"
                            + "id: 100\nevent: orders:1\ndata: {\"n\":2}\n\n");
        });

        CliRun.Result result = sub("sub", "orders:1", "--after", "5", "-n", "1");

        assertThat(result.code()).isZero();
        assertThat(result.lines()).hasSize(2);
        assertThat(JSON.readTree(result.lines().get(0))
                        .get("reset")
                        .get("reason")
                        .asText())
                .isEqualTo("history_gap");
        assertThat(JSON.readTree(result.lines().get(1)).get("id").asText()).isEqualTo("100");
        assertThat(connections.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a queue_full overflow WHEN sub reads it SHOULD print an overflow line and resume from the last id")
    void given_overflowEvent_when_sub_should_resume() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        on("/push/subscribe", (ex, req) -> {
            if (connections.incrementAndGet() == 1) {
                sse(ex, "id: 9\nevent: orders:1\ndata: {}\n\nevent: overflow\ndata: {\"reason\":\"queue_full\"}\n\n");
            } else {
                sse(ex, "id: 10\nevent: orders:1\ndata: {}\n\n");
            }
        });

        CliRun.Result result = sub("sub", "orders:1", "-n", "2");

        assertThat(result.code()).isZero();
        assertThat(JSON.readTree(result.lines().get(1))
                        .get("overflow")
                        .get("reason")
                        .asText())
                .isEqualTo("queue_full");
        assertThat(seen.get(1).headers()).containsEntry("last-event-id", "9");
    }

    @Test
    @DisplayName("GIVEN two 503 answers WHEN sub retries with backoff SHOULD connect on the third attempt")
    void given_unavailableGateway_when_sub_should_backOffAndConnect() {
        AtomicInteger connections = new AtomicInteger();
        on("/push/subscribe", (ex, req) -> {
            if (connections.incrementAndGet() <= 2) status(ex, 503, "{\"error\":\"draining\"}");
            else sse(ex, "id: 1\nevent: orders:1\ndata: {}\n\n");
        });

        CliRun.Result result = sub("sub", "orders:1", "-n", "1", "--retries", "5");

        assertThat(result.code()).isZero();
        assertThat(seen).hasSize(3);
        assertThat(result.lines()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a gateway that never recovers WHEN the retries run out SHOULD exit 1")
    void given_neverRecovers_when_retriesExhausted_should_exit1() {
        on("/push/subscribe", (ex, req) -> status(ex, 503, ""));

        CliRun.Result result = sub("sub", "orders:1", "--retries", "2");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("giving up");
        assertThat(seen).hasSize(3);
    }

    @Test
    @DisplayName(
            "GIVEN an expired token and --token-cmd WHEN the gateway answers 401 SHOULD run the command and retry once")
    void given_expiredToken_when_401_should_refreshWithTokenCmd() {
        on("/push/subscribe", (ex, req) -> {
            if ("Bearer fresh".equals(req.headers().get("authorization"))) {
                sse(ex, "id: 1\nevent: orders:1\ndata: {\"ok\":true}\n\n");
            } else {
                status(ex, 401, "{\"error\":\"expired\"}");
            }
        });

        CliRun.Result result = sub("sub", "orders:1", "-n", "1", "--token", "stale", "--token-cmd", "echo fresh");

        assertThat(result.code()).isZero();
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).headers()).containsEntry("authorization", "Bearer stale");
        assertThat(seen.get(1).headers()).containsEntry("authorization", "Bearer fresh");
    }

    @Test
    @DisplayName("GIVEN a 401 and no token command WHEN sub runs SHOULD exit 4")
    void given_401WithoutTokenCmd_when_sub_should_exit4() {
        on("/push/subscribe", (ex, req) -> status(ex, 401, ""));

        CliRun.Result result = sub("sub", "orders:1");

        assertThat(result.code()).isEqualTo(4);
        assertThat(seen).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a 403 WHEN sub runs SHOULD exit 4")
    void given_403_when_sub_should_exit4() {
        on("/push/subscribe", (ex, req) -> status(ex, 403, ""));

        assertThat(sub("sub", "orders:1").code()).isEqualTo(4);
    }

    @Test
    @DisplayName("GIVEN a gateway without the push surface WHEN sub runs SHOULD exit 3")
    void given_404_when_sub_should_exit3() {
        on("/push/subscribe", (ex, req) -> status(ex, 404, ""));

        assertThat(sub("sub", "orders:1").code()).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN a quiet stream WHEN --timeout elapses SHOULD exit 0")
    void given_quietStream_when_timeout_should_exit0() {
        on("/push/subscribe", (ex, req) -> {
            sse(ex, ": keepalive\n\n");
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long start = System.nanoTime();
        CliRun.Result result = sub("sub", "orders:1", "--timeout", "1");

        assertThat(result.code()).isZero();
        assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(6000);
    }

    @Test
    @DisplayName("GIVEN SIGINT during a stream WHEN sub is stopped SHOULD flush and exit 130")
    void given_interrupt_when_sub_should_exit130() throws Exception {
        on("/push/subscribe", (ex, req) -> {
            sse(ex, "id: 1\nevent: orders:1\ndata: {}\n\n");
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Lifecycle lifecycle = new Lifecycle();
        new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    lifecycle.stop(true);
                })
                .start();

        CliRun.Result result =
                CliRun.run(Map.of("CURSUS_CONFIG", "/nonexistent/c.yml"), lifecycle, "sub", "orders:1", "--url", url);

        assertThat(result.code()).isEqualTo(130);
        assertThat(result.lines()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN --origin WHEN sub connects SHOULD send it as the origin query parameter")
    void given_origin_when_subConnects_should_sendOriginQueryParameter() {
        on("/push/subscribe", (ex, req) -> sse(ex, "id: 8\nevent: orders:1\ndata: {}\n\n"));

        CliRun.Result result = sub("sub", "orders:1", "--origin", "dev-1", "-n", "1");

        assertThat(result.code()).isZero();
        assertThat(seen.get(0).uri()).isEqualTo("/push/subscribe?topic=orders%3A1&origin=dev-1");
    }

    @Test
    @DisplayName("GIVEN --origin WHEN pub runs SHOULD post it as the origin field")
    void given_origin_when_pub_should_postOriginField() throws Exception {
        on("/push/publish", (ex, req) -> status(ex, 200, "{\"topic\":\"orders:1\",\"id\":\"77\"}"));

        CliRun.Result result = CliRun.run("pub", "orders:1", "--url", url, "-d", "{}", "--origin", "client-7");

        assertThat(result.code()).isZero();
        assertThat(JSON.readTree(seen.get(0).body()).get("origin").asText()).isEqualTo("client-7");
    }

    @Test
    @DisplayName("GIVEN a payload WHEN pub runs SHOULD post topic, data, attributes and key and print the answer line")
    void given_payload_when_pub_should_postAndPrintAnswer() throws Exception {
        on("/push/publish", (ex, req) -> status(ex, 200, "{\"topic\":\"orders:1\",\"id\":\"77\"}"));

        CliRun.Result result = CliRun.run(
                "pub", "orders:1", "--url", url, "-d", "{\"status\":\"NEW\"}", "--attr", "tenant=acme", "--key", "k1");

        assertThat(result.code()).isZero();
        assertThat(result.lines()).containsExactly("{\"topic\":\"orders:1\",\"id\":\"77\"}");
        JsonNode body = JSON.readTree(seen.get(0).body());
        assertThat(body.get("topic").asText()).isEqualTo("orders:1");
        assertThat(body.get("data").get("status").asText()).isEqualTo("NEW");
        assertThat(body.get("attributes").get("tenant").asText()).isEqualTo("acme");
        assertThat(body.get("key").asText()).isEqualTo("k1");
    }

    @Test
    @DisplayName(
            "GIVEN NDJSON on stdin WHEN pub runs with a batch SHOULD publish every line and keep the answers in order")
    void given_ndjsonOnStdin_when_pub_should_publishEachLine() {
        AtomicInteger ids = new AtomicInteger();
        on(
                "/push/publish",
                (ex, req) -> status(ex, 200, "{\"topic\":\"orders:1\",\"id\":\"" + ids.incrementAndGet() + "\"}"));
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream("{\"n\":1}\n\n{\"n\":2}\n{\"n\":3}\n".getBytes(StandardCharsets.UTF_8)));
        try {
            CliRun.Result result = CliRun.run("pub", "orders:1", "--url", url, "--batch", "2");

            assertThat(result.code()).isZero();
            assertThat(result.lines()).hasSize(3);
            assertThat(seen).hasSize(3);
        } finally {
            System.setIn(original);
        }
    }

    @Test
    @DisplayName("GIVEN --type WHEN pub runs SHOULD post an Any message to the v1 route and print each event")
    void given_type_when_pub_should_postTypedMessage() throws Exception {
        on("/push/v1/publish", (ex, req) -> status(ex, 200, "{\"events\":[{\"topic\":\"orders:1\",\"id\":\"5\"}]}"));

        CliRun.Result result =
                CliRun.run("pub", "orders:1", "--url", url, "--type", "acme.OrderEvent", "-d", "{\"orderId\":\"1\"}");

        assertThat(result.code()).isZero();
        assertThat(result.lines()).containsExactly("{\"topic\":\"orders:1\",\"id\":\"5\"}");
        JsonNode body = JSON.readTree(seen.get(0).body());
        assertThat(body.get("topics").get(0).asText()).isEqualTo("orders:1");
        assertThat(body.get("message").get("@type").asText()).isEqualTo("type.googleapis.com/acme.OrderEvent");
        assertThat(body.get("message").get("orderId").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("GIVEN a rejected publish WHEN pub runs SHOULD exit 4 on 403")
    void given_forbiddenPublish_when_pub_should_exit4() {
        on("/push/publish", (ex, req) -> status(ex, 403, ""));

        assertThat(CliRun.run("pub", "orders:1", "--url", url, "-d", "{}").code())
                .isEqualTo(4);
    }

    @Test
    @DisplayName(
            "GIVEN the topics endpoint WHEN ls runs SHOULD print one NDJSON line per topic and a table with --pretty")
    void given_topics_when_ls_should_printLinesAndTable() throws Exception {
        on("/actuator/gateway-push/topics", (ex, req) -> status(ex, 200, """
                {"topics":[{"topic":"orders:1","subscribers":3,"history":10},{"topic":"ticker:btc","subscribers":1,"history":0}]}
                """));

        CliRun.Result lines = CliRun.run("ls", "topics", "--url", url, "--filter", "o");
        CliRun.Result table = CliRun.run("ls", "--url", url, "--pretty");

        assertThat(lines.code()).isZero();
        assertThat(lines.lines()).hasSize(2);
        assertThat(JSON.readTree(lines.lines().get(0)).get("subscribers").asInt())
                .isEqualTo(3);
        assertThat(seen.get(0).uri()).isEqualTo("/actuator/gateway-push/topics?q=o");
        assertThat(table.lines().get(0)).startsWith("TOPIC").contains("SUBSCRIBERS");
        assertThat(table.lines().get(1)).startsWith("orders:1");
    }

    @Test
    @DisplayName(
            "GIVEN a shortcut and basic auth WHEN conns runs SHOULD call connections with the filters and a Basic header")
    void given_connsShortcut_when_run_should_callConnections() {
        on(
                "/actuator/gateway-push/connections",
                (ex, req) -> status(
                        ex, 200, "{\"connections\":[{\"id\":\"c1\",\"transport\":\"sse\",\"topics\":[\"a:b\"]}]}"));

        CliRun.Result result = CliRun.run("conns", "--url", url, "--principal", "alice", "--basic", "admin:secret");

        assertThat(result.code()).isZero();
        assertThat(result.lines()).hasSize(1);
        assertThat(seen.get(0).uri()).isEqualTo("/actuator/gateway-push/connections?principal=alice");
        assertThat(seen.get(0).headers().get("authorization"))
                .isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("admin:secret".getBytes()));
    }

    @Test
    @DisplayName("GIVEN an archive WHEN find runs SHOULD pass the query parameters and print the messages")
    void given_archive_when_find_should_passQueryAndPrint() throws Exception {
        on(
                "/actuator/gateway-push/messages",
                (ex, req) -> status(
                        ex,
                        200,
                        "{\"messages\":[{\"topic\":\"orders:1\",\"id\":\"9\",\"time\":\"2026-10-01T00:00:00Z\"}]}"));

        CliRun.Result result = CliRun.run(
                "find",
                "--url",
                url,
                "--topic",
                "orders:*",
                "--attr",
                "region=eu",
                "--attr",
                "tenant=acme",
                "--limit",
                "5");

        assertThat(result.code()).isZero();
        assertThat(JSON.readTree(result.lines().get(0)).get("id").asText()).isEqualTo("9");
        assertThat(seen.get(0).uri())
                .contains("topic=orders%3A*")
                .contains("attr=region%3Aeu%2Ctenant%3Aacme")
                .contains("limit=5");
    }

    @Test
    @DisplayName("GIVEN a gateway whose archive is off WHEN find runs SHOULD exit 3")
    void given_noArchive_when_find_should_exit3() {
        on("/actuator/gateway-push/messages", (ex, req) -> status(ex, 404, "{\"error\":\"no_archive\"}"));

        CliRun.Result result = CliRun.run("find", "--url", url, "--topic", "orders:*");

        assertThat(result.code()).isEqualTo(3);
        assertThat(result.err()).contains("404");
    }

    @Test
    @DisplayName("GIVEN --export WHEN find runs SHOULD stream the NDJSON of the push path export")
    void given_export_when_find_should_streamNdjson() {
        on("/push/export", (ex, req) -> {
            try {
                byte[] body = "{\"id\":\"1\"}\n{\"id\":\"2\"}\n".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        CliRun.Result result = CliRun.run("find", "--url", url, "--topic", "orders:*", "--export");

        assertThat(result.code()).isZero();
        assertThat(result.lines()).containsExactly("{\"id\":\"1\"}", "{\"id\":\"2\"}");
    }

    @Test
    @DisplayName("GIVEN --insecure WHEN a command runs SHOULD warn once on stderr, and not under --quiet")
    void given_insecure_when_run_should_warnOnStderrOnce() {
        on("/actuator/gateway-push/topics", (ex, req) -> status(ex, 200, "{\"topics\":[]}"));

        CliRun.Result loud = CliRun.run("ls", "--url", url, "--insecure");
        CliRun.Result quiet = CliRun.run("ls", "--url", url, "--insecure", "--quiet");

        assertThat(loud.err()).containsOnlyOnce("--insecure");
        assertThat(quiet.err()).isEmpty();
        assertThat(CliRun.run("ls", "--url", url).err()).doesNotContain("insecure");
    }
}
