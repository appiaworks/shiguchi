package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code cursus pub}: publish one event (or an NDJSON stream of them) to a topic. */
@Command(
        name = "pub",
        aliases = {"publish"},
        mixinStandardHelpOptions = true,
        description = "Publish events: -d '{...}', -d @file.json, or NDJSON on stdin (one event per line).",
        footer = {
            "",
            "Examples:",
            "  cursus pub orders:123 -d '{\"status\":\"SHIPPED\"}' --attr tenant=acme",
            "  cat events.ndjson | cursus pub orders:123 --batch 20",
            "  cursus pub orders:123 --type acme.OrderEvent -d @order.json     (typed, through <path>/v1/publish)"
        })
final class PubCommand implements Callable<Integer> {

    @ParentCommand
    Cursus root;

    @Parameters(arity = "1..*", paramLabel = "TOPIC", description = "Topic(s) to publish to.")
    List<String> topics;

    @Option(
            names = {"-d", "--data"},
            description = "The payload: JSON text or @file. Absent: NDJSON from stdin.")
    String data;

    @Option(names = "--key", description = "Ordering key.")
    String key;

    @Option(names = "--attr", paramLabel = "K=V", description = "Attribute; repeatable.")
    List<String> attrs = new ArrayList<>();

    @Option(
            names = "--origin",
            description = "Who the event came from (stored as push.origin): a subscriber that declared the same"
                    + " origin does not receive it.")
    String origin;

    @Option(
            names = "--type",
            description = "Protobuf message type (e.g. acme.OrderEvent): a typed publish through <path>/v1/publish.")
    String type;

    @Option(names = "--batch", description = "Publishes in flight at once (default 1).")
    int batch = 1;

    @Option(names = "--fire-and-forget", description = "Do not wait for or print the answers.")
    boolean fireAndForget;

    /** Stdin, replaceable in tests. */
    InputStream stdin = System.in;

    @Override
    public Integer call() throws Exception {
        if (batch < 1) throw new CliException(CliException.USAGE, "--batch must be at least 1");
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String a : attrs) {
            int eq = a.indexOf('=');
            if (eq <= 0) throw new CliException(CliException.USAGE, "--attr expects k=v, got '" + a + "'");
            attributes.put(a.substring(0, eq), a.substring(eq + 1));
        }
        Out out = root.out();
        Http http = root.http();
        List<JsonNode> payloads = payloads();
        if (payloads.isEmpty())
            throw new CliException(CliException.USAGE, "nothing to publish: no -d and stdin is empty");

        List<CompletableFuture<JsonNode>> window = new ArrayList<>();
        int inFlight = fireAndForget ? Math.max(batch, 256) : batch;
        String path = type == null ? "/publish" : "/v1/publish";
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (JsonNode payload : payloads) {
                if (root.lifecycle().isStopped()) break;
                ObjectNode body = body(payload, attributes);
                window.add(CompletableFuture.supplyAsync(() -> http.postPush(path, body), executor));
                if (window.size() >= inFlight) drain(window, out);
            }
            drain(window, out);
        }
        return 0;
    }

    private void drain(List<CompletableFuture<JsonNode>> window, Out out) {
        for (CompletableFuture<JsonNode> f : window) {
            JsonNode answer;
            try {
                answer = f.join();
            } catch (java.util.concurrent.CompletionException e) {
                if (fireAndForget) {
                    out.info("publish failed: " + Http.describe(e.getCause()));
                    continue;
                }
                if (e.getCause() instanceof CliException c) throw c;
                throw e;
            }
            if (fireAndForget) continue;
            // a multi-topic publish answers {"results":[...]}, a typed one {"events":[...]}
            JsonNode many = answer.has("results") ? answer.get("results") : answer.get("events");
            if (many != null && many.isArray()) many.forEach(out::json);
            else out.json(answer);
        }
        window.clear();
    }

    private ObjectNode body(JsonNode payload, Map<String, String> attributes) {
        ObjectNode body = Http.JSON.createObjectNode();
        if (type == null) {
            if (topics.size() == 1) body.put("topic", topics.get(0));
            else {
                var array = body.putArray("topics");
                topics.forEach(array::add);
            }
            body.set("data", payload);
        } else {
            var array = body.putArray("topics");
            topics.forEach(array::add);
            ObjectNode message = Http.JSON.createObjectNode();
            message.put("@type", type.contains("/") ? type : "type.googleapis.com/" + type);
            if (payload.isObject()) message.setAll((ObjectNode) payload);
            else message.set("value", payload);
            body.set("message", message);
        }
        if (!attributes.isEmpty()) {
            ObjectNode attrs = body.putObject("attributes");
            attributes.forEach(attrs::put);
        }
        if (key != null) body.put("key", key);
        if (origin != null) body.put("origin", origin);
        return body;
    }

    private List<JsonNode> payloads() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (data != null) {
            String text = data.startsWith("@") ? readFile(data.substring(1)) : data;
            out.add(parse(text, "-d"));
            return out;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = reader.readLine()) != null) {
                n++;
                if (line.isBlank()) continue;
                out.add(parse(line, "stdin line " + n));
            }
        }
        return out;
    }

    private static String readFile(String name) throws IOException {
        Path path = Path.of(name);
        if (!Files.isRegularFile(path)) throw new CliException(CliException.USAGE, "no such file: " + name);
        return Files.readString(path);
    }

    private static JsonNode parse(String text, String where) {
        try {
            return Http.JSON.readTree(text);
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, where + " is not valid JSON: " + Http.describe(e));
        }
    }
}
