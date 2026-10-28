package dev.appiaworks.shiguchi.cursus.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The admin verbs of cursus (repub, rm, dlq, seek, send, bench) against a scripted fake gateway. */
@DisplayName("cursus admin verbs against a fake gateway")
class CursusAdminTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "/actuator/gateway-push";

    record Seen(String method, String uri, String body) {}

    private HttpServer server;
    private String url;
    private boolean started;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ((ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void route(String path, BiConsumer<HttpExchange, Seen> handler) {
        server.createContext(path, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen request = new Seen(
                    exchange.getRequestMethod(), exchange.getRequestURI().toString(), body);
            seen.add(request);
            try {
                handler.accept(exchange, request);
            } finally {
                exchange.close();
            }
        });
    }

    private static void reply(HttpExchange exchange, int code, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private CliRun.Result run(String... args) {
        if (!started) {
            server.start();
            started = true;
        }
        String[] all = java.util.Arrays.copyOf(args, args.length + 2);
        all[args.length] = "--url";
        all[args.length + 1] = url;
        return CliRun.run(all);
    }

    @Test
    @DisplayName("GIVEN an id and topic WHEN repub --dry-run runs SHOULD post a dry run to the message and print it")
    void given_id_when_repubDryRun_should_postDryRun() throws Exception {
        route(
                ADMIN + "/messages/",
                (ex, req) -> reply(ex, 200, "{\"dryRun\":true,\"origin\":{\"topic\":\"orders:1\",\"id\":\"9\"}}"));

        CliRun.Result result = run("repub", "--id", "9", "--topic", "orders:1", "--dry-run", "--reason", "test");

        assertThat(result.code()).isZero();
        assertThat(seen.get(0).uri()).isEqualTo(ADMIN + "/messages/9/republish");
        JsonNode body = JSON.readTree(seen.get(0).body());
        assertThat(body.get("dryRun").asBoolean()).isTrue();
        assertThat(body.get("topic").asText()).isEqualTo("orders:1");
        assertThat(body.get("reason").asText()).isEqualTo("test");
        assertThat(result.lines()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN --id without --topic WHEN repub runs SHOULD exit 2")
    void given_idWithoutTopic_when_repub_should_exit2() {
        assertThat(run("repub", "--id", "9").code()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN no id and no query WHEN repub runs SHOULD exit 2")
    void given_nothing_when_repub_should_exit2() {
        assertThat(run("repub").code()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a query WHEN repub starts a job SHOULD follow it until DONE and exit 0")
    void given_query_when_repubJob_should_followToDone() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        route(
                ADMIN + "/messages/republish",
                (ex, req) -> reply(
                        ex,
                        200,
                        "{\"dryRun\":false,\"matched\":3,\"job\":{\"id\":\"j1\",\"state\":\"RUNNING\",\"matched\":3,\"published\":0}}"));
        route(ADMIN + "/jobs/j1", (ex, req) -> {
            int n = polls.incrementAndGet();
            reply(
                    ex,
                    200,
                    n < 2
                            ? "{\"id\":\"j1\",\"state\":\"RUNNING\",\"matched\":3,\"published\":1}"
                            : "{\"id\":\"j1\",\"state\":\"DONE\",\"matched\":3,\"published\":3}");
        });

        CliRun.Result result =
                run("repub", "--topic", "orders:*", "--from", "1h", "--attr", "tenant=acme", "--poll-ms", "10");

        assertThat(result.code()).isZero();
        JsonNode body = JSON.readTree(seen.get(0).body());
        assertThat(body.get("topic").asText()).isEqualTo("orders:*");
        assertThat(body.get("attr").asText()).isEqualTo("tenant:acme");
        assertThat(body.has("from")).isTrue();
        List<String> lines = result.lines();
        assertThat(JSON.readTree(lines.get(lines.size() - 1)).get("state").asText())
                .isEqualTo("DONE");
    }

    @Test
    @DisplayName("GIVEN a job that fails WHEN repub follows it SHOULD exit 1")
    void given_failingJob_when_repub_should_exit1() {
        route(
                ADMIN + "/messages/republish",
                (ex, req) -> reply(ex, 200, "{\"job\":{\"id\":\"j2\",\"state\":\"RUNNING\"}}"));
        route(ADMIN + "/jobs/j2", (ex, req) -> reply(ex, 200, "{\"id\":\"j2\",\"state\":\"FAILED\",\"error\":\"x\"}"));

        assertThat(run("repub", "--topic", "orders:*", "--poll-ms", "10").code())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN an event WHEN rm runs SHOULD DELETE the message with its topic and print the tombstone")
    void given_event_when_rm_should_deleteWithTopic() {
        route(ADMIN + "/messages/", (ex, req) -> reply(ex, 200, ""));

        CliRun.Result result = run("rm", "9", "--topic", "orders:1");

        assertThat(result.code()).isZero();
        assertThat(seen.get(0).method()).isEqualTo("DELETE");
        assertThat(seen.get(0).uri()).isEqualTo(ADMIN + "/messages/9?topic=orders%3A1");
        assertThat(result.lines()).containsExactly("{\"tombstoned\":true,\"topic\":\"orders:1\",\"id\":\"9\"}");
    }

    @Test
    @DisplayName("GIVEN a gateway without an archive WHEN rm runs SHOULD exit 3")
    void given_noArchive_when_rm_should_exit3() {
        route(ADMIN + "/messages/", (ex, req) -> reply(ex, 404, "{}"));

        assertThat(run("rm", "9", "--topic", "orders:1").code()).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN dead letters WHEN dlq ls, retry and rm run SHOULD hit list, retry and delete")
    void given_deadLetters_when_dlq_should_listRetryAndDelete() throws Exception {
        route(ADMIN + "/dlq", (ex, req) -> {
            if (req.uri().contains("/retry"))
                reply(ex, 200, "{\"id\":\"d1\",\"topic\":\"orders:replay\",\"eventId\":\"e1\"}");
            else if ("DELETE".equals(req.method())) reply(ex, 200, "");
            else reply(ex, 200, "{\"dlq\":[{\"id\":\"d1\",\"source\":\"orders-kafka\"}]}");
        });

        CliRun.Result list = run("dlq", "ls", "--source", "orders-kafka", "--limit", "5");
        CliRun.Result retry = run("dlq", "retry", "d1", "--topic", "orders:replay");
        CliRun.Result rm = run("dlq", "rm", "d1");

        assertThat(list.code()).isZero();
        assertThat(JSON.readTree(list.lines().get(0)).get("source").asText()).isEqualTo("orders-kafka");
        assertThat(seen.get(0).uri()).contains("q=orders-kafka").contains("limit=5");
        assertThat(retry.code()).isZero();
        assertThat(seen.get(1).uri()).isEqualTo(ADMIN + "/dlq/d1/retry");
        assertThat(JSON.readTree(seen.get(1).body()).get("topic").asText()).isEqualTo("orders:replay");
        assertThat(rm.code()).isZero();
        assertThat(seen.get(2).method()).isEqualTo("DELETE");
        assertThat(seen.get(2).uri()).isEqualTo(ADMIN + "/dlq/d1");
    }

    @Test
    @DisplayName("GIVEN a group WHEN seek --to first runs SHOULD post the cursor move to the group")
    void given_group_when_seek_should_postToGroup() throws Exception {
        route(ADMIN + "/groups/", (ex, req) -> reply(ex, 200, "{\"seeked\":true}"));

        CliRun.Result result = run("seek", "--group", "billing", "--topic", "orders:1", "--to", "first");

        assertThat(result.code()).isZero();
        assertThat(seen.get(0).uri()).isEqualTo(ADMIN + "/groups/billing/seek");
        JsonNode body = JSON.readTree(seen.get(0).body());
        assertThat(body.get("topic").asText()).isEqualTo("orders:1");
        assertThat(body.get("to").asText()).isEqualTo("first");
    }

    @Test
    @DisplayName("GIVEN a session the gateway cannot move WHEN seek runs SHOULD exit 1")
    void given_unmovedSession_when_seek_should_exit1() {
        route(ADMIN + "/sessions/", (ex, req) -> reply(ex, 200, "{\"seeked\":false}"));

        assertThat(run("seek", "--session", "s1", "--topic", "t", "--to", "head")
                        .code())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN both or neither of --session and --group WHEN seek runs SHOULD exit 2")
    void given_ambiguousTarget_when_seek_should_exit2() {
        assertThat(run("seek", "--topic", "t", "--to", "head").code()).isEqualTo(2);
        assertThat(run("seek", "--session", "a", "--group", "b", "--topic", "t", "--to", "head")
                        .code())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a connection id WHEN send runs SHOULD post the payload and exit 0 when delivered")
    void given_connection_when_send_should_postAndExit0() throws Exception {
        route(
                ADMIN + "/connections/",
                (ex, req) -> reply(
                        ex,
                        200,
                        "{\"id\":\"e1\",\"deliveries\":[{\"connectionId\":\"c1\",\"status\":\"DELIVERED\"}]}"));

        CliRun.Result result =
                run("send", "--conn", "c1", "-d", "{\"hello\":1}", "--require-ack", "--timeout-ms", "500");

        assertThat(result.code()).isZero();
        assertThat(seen.get(0).uri()).isEqualTo(ADMIN + "/connections/c1/send");
        JsonNode body = JSON.readTree(seen.get(0).body());
        assertThat(body.get("data").asText()).isEqualTo("{\"hello\":1}");
        assertThat(body.get("requireAck").asBoolean()).isTrue();
        assertThat(body.get("timeoutMs").asLong()).isEqualTo(500);
    }

    @Test
    @DisplayName("GIVEN a user with two connections WHEN send --user runs SHOULD send to each of them")
    void given_user_when_send_should_sendToEveryConnection() {
        route(ADMIN + "/connections", (ex, req) -> {
            if (req.uri().endsWith("/send"))
                reply(ex, 200, "{\"id\":\"e\",\"deliveries\":[{\"connectionId\":\"x\",\"status\":\"DELIVERED\"}]}");
            else
                reply(
                        ex,
                        200,
                        "{\"connections\":[{\"id\":\"a\",\"principal\":\"alice\"},{\"id\":\"b\",\"principal\":\"bob\"},{\"id\":\"c\",\"principal\":\"alice\"}]}");
        });

        CliRun.Result result = run("send", "--user", "alice", "-d", "{}");

        assertThat(result.code()).isZero();
        assertThat(seen.stream().filter(s -> s.uri().endsWith("/send")).map(Seen::uri))
                .containsExactly(ADMIN + "/connections/a/send", ADMIN + "/connections/c/send");
    }

    @Test
    @DisplayName("GIVEN a user with no connection WHEN send --user runs SHOULD exit 1")
    void given_absentUser_when_send_should_exit1() {
        route(ADMIN + "/connections", (ex, req) -> reply(ex, 200, "{\"connections\":[]}"));

        assertThat(run("send", "--user", "nobody", "-d", "{}").code()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a connection that is not there WHEN send runs SHOULD exit 1 when nothing was delivered")
    void given_notConnected_when_send_should_exit1() {
        route(
                ADMIN + "/connections/",
                (ex, req) -> reply(
                        ex,
                        200,
                        "{\"id\":\"e1\",\"deliveries\":[{\"connectionId\":\"c1\",\"status\":\"NOT_CONNECTED\"}]}"));

        assertThat(run("send", "--conn", "c1", "-d", "{}").code()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN invalid JSON or no target WHEN send runs SHOULD exit 2")
    void given_badInput_when_send_should_exit2() {
        assertThat(run("send", "--conn", "c1", "-d", "{nope").code()).isEqualTo(2);
        assertThat(run("send", "-d", "{}").code()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a gateway that echoes publishes WHEN bench runs one second SHOULD print a result object")
    void given_gateway_when_bench_should_printLatencySummary() throws Exception {
        route("/push/publish", (ex, req) -> reply(ex, 200, "{\"id\":\"1\"}"));
        route("/push/subscribe", (ex, req) -> {
            try {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                String data =
                        "{\"topic\":\"bench:cursus\",\"id\":\"1\",\"data\":{\"t\":" + System.nanoTime() / 1_000 + "}}";
                out.write(("id: 1\nevent: event\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(2500);
            } catch (IOException | InterruptedException ignored) {
                // the client closed the stream
            }
        });

        CliRun.Result result =
                run("bench", "--rate", "20", "--duration", "1", "--subscribers", "1", "--drain-ms", "100");

        assertThat(result.code()).isZero();
        JsonNode summary = JSON.readTree(result.lines().get(0));
        assertThat(summary.get("sent").asLong()).isGreaterThan(0);
        assertThat(summary.get("publishFailures").asLong()).isZero();
        assertThat(summary.get("latencyMs").has("samples")).isTrue();
    }

    @Test
    @DisplayName("GIVEN a publish endpoint that always fails WHEN bench runs SHOULD exit 1 with failures counted")
    void given_failingPublish_when_bench_should_exit1() {
        route("/push/publish", (ex, req) -> reply(ex, 500, "{}"));

        CliRun.Result result =
                run("bench", "--rate", "20", "--duration", "1", "--subscribers", "0", "--drain-ms", "10");

        assertThat(result.code()).isEqualTo(1);
    }
}
