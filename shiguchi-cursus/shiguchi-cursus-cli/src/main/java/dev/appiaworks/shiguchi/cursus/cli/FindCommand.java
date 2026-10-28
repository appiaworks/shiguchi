package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code cursus find}: search the archive ({@code GET messages}); needs {@code archive.retention} on the gateway. */
@Command(
        name = "find",
        aliases = {"search"},
        mixinStandardHelpOptions = true,
        description = "Search archived events, newest first; one NDJSON line per event.",
        footer = {
            "",
            "Examples:",
            "  cursus find --topic 'orders:*' --from 1h --attr tenant=acme",
            "  cursus find --topic orders:123 --text shipped --limit 20 --pretty",
            "  cursus find --topic 'orders:*' --export > orders.ndjson     (streams <path>/export)"
        })
final class FindCommand implements Callable<Integer> {

    private static final Pattern RELATIVE = Pattern.compile("(\\d+)([smhd])");

    @ParentCommand
    Cursus root;

    @Option(
            names = {"-t", "--topic"},
            description = "Topic, or a pattern such as 'orders:*'.")
    String topic;

    @Option(names = "--from", description = "Not before: an ISO instant or a relative time (30m, 1h, 2d).")
    String from;

    @Option(names = "--to", description = "Not after: an ISO instant or a relative time.")
    String to;

    @Option(names = "--after", description = "Only ids after this one.")
    String after;

    @Option(names = "--before", description = "Only ids before this one.")
    String before;

    @Option(names = "--attr", paramLabel = "K=V", description = "Attribute every hit must carry; repeatable.")
    List<String> attrs = new ArrayList<>();

    @Option(names = "--type", description = "A type URL, or 'json'.")
    String type;

    @Option(
            names = {"--text", "--q"},
            description = "Words in the payload (needs a full-text archive).")
    String text;

    @Option(names = "--limit", description = "At most this many events (the endpoint caps it at 1000).")
    Integer limit;

    @Option(names = "--export", description = "Stream the query from <path>/export (NDJSON, up to 100 000 events).")
    boolean export;

    @Override
    public Integer call() throws IOException {
        List<String> pairs = new ArrayList<>();
        for (String a : attrs) {
            int eq = a.indexOf('=');
            if (eq <= 0) throw new CliException(CliException.USAGE, "--attr expects k=v, got '" + a + "'");
            pairs.add(a.substring(0, eq) + ":" + a.substring(eq + 1));
        }
        String query = Http.query(List.of(
                Map.entry("topic", nz(topic)),
                Map.entry("from", instant(from)),
                Map.entry("to", instant(to)),
                Map.entry("after", nz(after)),
                Map.entry("before", nz(before)),
                Map.entry("attr", String.join(",", pairs)),
                Map.entry("type", nz(type)),
                Map.entry("q", nz(text)),
                Map.entry("limit", limit == null ? "" : limit.toString())));
        Http http = root.http();
        Out out = root.out();
        if (export) {
            exportStream(http, out, query);
            return 0;
        }
        JsonNode messages = http.getAdmin("/messages" + query).path("messages");
        out.rows(messages, "/time", "/topic", "/id", "/bytes", "/typeUrl", "/tombstoned");
        return 0;
    }

    private void exportStream(Http http, Out out, String query) throws IOException {
        HttpResponse<java.io.InputStream> response = http.stream(
                http.settings().pushBase() + "/export" + query, Map.of("Accept", "application/x-ndjson"), false);
        root.lifecycle().onStop(() -> {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // already closed
            }
        });
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) out.text(line);
            }
        } catch (IOException e) {
            if (!root.lifecycle().isStopped()) throw e;
        } finally {
            root.lifecycle().onStop(null);
        }
    }

    /** {@code 1h} becomes the ISO instant one hour ago; anything else passes through for the server to judge. */
    static String instant(String value) {
        if (value == null || value.isBlank()) return "";
        Matcher m = RELATIVE.matcher(value.strip());
        if (!m.matches()) return value.strip();
        long n = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
        return Instant.now().minus(d).toString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
