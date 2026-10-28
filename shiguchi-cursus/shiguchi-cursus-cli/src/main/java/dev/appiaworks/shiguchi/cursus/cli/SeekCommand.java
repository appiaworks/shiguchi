package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code cursus seek}: move a persistent session's or a consumer group's cursor on one topic. */
@Command(
        name = "seek",
        mixinStandardHelpOptions = true,
        description = "Move a session or group cursor: --to an event id, 'first' or 'head'. Admin only.",
        footer = {
            "",
            "Examples:",
            "  cursus seek --group billing --topic orders:123 --to first",
            "  cursus seek --session mobile-alice --topic orders:123 --to 0000018f"
        })
final class SeekCommand implements Callable<Integer> {

    @ParentCommand
    Cursus root;

    @Option(names = "--session", description = "A persistent session key.")
    String session;

    @Option(names = "--group", description = "A consumer group.")
    String group;

    @Option(
            names = {"-t", "--topic"},
            required = true,
            description = "The topic whose cursor moves.")
    String topic;

    @Option(
            names = "--to",
            required = true,
            description = "An event id, 'first' (replay everything kept) or 'head' (skip to now).")
    String to;

    @Override
    public Integer call() {
        if ((session == null) == (group == null))
            throw new CliException(CliException.USAGE, "give exactly one of --session or --group");
        ObjectNode body = Http.JSON.createObjectNode();
        body.put("topic", topic);
        body.put("to", to);
        String path =
                session != null ? "/sessions/" + Http.enc(session) + "/seek" : "/groups/" + Http.enc(group) + "/seek";
        JsonNode answer = root.http().postAdmin(path, body);
        if (!answer.path("seeked").asBoolean(false)) {
            root.out().json(answer);
            throw new CliException(CliException.TRANSPORT, "the gateway did not move the cursor");
        }
        root.out().json(answer);
        return 0;
    }
}
