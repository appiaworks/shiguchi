package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code cursus repub}: publish archived events again, one by id or everything a query matches (a job). */
@Command(
        name = "repub",
        aliases = {"republish"},
        mixinStandardHelpOptions = true,
        description = "Republish archived events: --id (with --topic), or a query. Admin only.",
        footer = {
            "",
            "Examples:",
            "  cursus repub --topic orders:123 --id 0000018f --dry-run",
            "  cursus repub --topic 'orders:*' --from 1h --attr tenant=acme --target orders-replay --reason backfill",
            "  cursus repub --topic 'orders:*' --from 1d --no-wait        (starts the job, prints it, returns)"
        })
final class RepubCommand implements Callable<Integer> {

    @ParentCommand
    Cursus root;

    @Option(names = "--id", description = "One archived event (needs the exact --topic).")
    String id;

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

    @Option(names = "--attr", paramLabel = "K=V", description = "Attribute every event must carry; repeatable.")
    List<String> attrs = new ArrayList<>();

    @Option(names = "--type", description = "A type URL, or 'json'.")
    String type;

    @Option(
            names = {"--text", "--q"},
            description = "Words in the payload (needs a full-text archive).")
    String text;

    @Option(names = "--target", description = "Publish to this topic instead of the original one.")
    String target;

    @Option(names = "--reason", description = "Why; recorded in the audit log.")
    String reason;

    @Option(names = "--dry-run", description = "Say what would be republished; send nothing.")
    boolean dryRun;

    @Option(names = "--no-wait", description = "Print the started job and return instead of following it.")
    boolean noWait;

    @Option(names = "--poll-ms", hidden = true, description = "Job poll interval.")
    long pollMs = 500;

    @Override
    public Integer call() {
        Http http = root.http();
        Out out = root.out();
        ObjectNode body = Http.JSON.createObjectNode();
        body.put("dryRun", dryRun);
        if (target != null && !target.isBlank()) body.put("target", target.strip());
        if (reason != null && !reason.isBlank()) body.put("reason", reason.strip());
        if (id != null) {
            if (topic == null || topic.isBlank())
                throw new CliException(CliException.USAGE, "--id needs the event's exact --topic");
            body.put("topic", topic);
            out.json(http.postAdmin("/messages/" + Http.enc(id) + "/republish", body));
            return 0;
        }
        put(body, "topic", topic);
        put(body, "from", FindCommand.instant(from));
        put(body, "to", FindCommand.instant(to));
        put(body, "after", after);
        put(body, "before", before);
        put(body, "type", type);
        put(body, "q", text);
        List<String> pairs = new ArrayList<>();
        for (String a : attrs) {
            int eq = a.indexOf('=');
            if (eq <= 0) throw new CliException(CliException.USAGE, "--attr expects k=v, got '" + a + "'");
            pairs.add(a.substring(0, eq) + ":" + a.substring(eq + 1));
        }
        put(body, "attr", String.join(",", pairs));
        if (body.size() <= 1 + (target == null ? 0 : 1) + (reason == null ? 0 : 1))
            throw new CliException(CliException.USAGE, "give --id, or at least one query option such as --topic");

        JsonNode started = http.postAdmin("/messages/republish", body);
        out.json(started);
        JsonNode job = started.path("job");
        if (dryRun || noWait || !job.hasNonNull("id")) return 0;
        return follow(http, out, job.path("id").asText());
    }

    /** Polls the job until it leaves RUNNING, printing a line whenever its counters move. */
    private int follow(Http http, Out out, String jobId) {
        String last = "";
        while (true) {
            JsonNode job = http.getAdmin("/jobs/" + Http.enc(jobId));
            String line = job.toString();
            if (!line.equals(last)) {
                out.json(job);
                last = line;
            }
            String state = job.path("state").asText();
            if (!"RUNNING".equals(state)) return "DONE".equals(state) ? 0 : CliException.TRANSPORT;
            if (!root.lifecycle().sleep(pollMs)) return 0;
        }
    }

    private static void put(ObjectNode body, String key, String value) {
        if (value != null && !value.isBlank()) body.put(key, value.strip());
    }
}
