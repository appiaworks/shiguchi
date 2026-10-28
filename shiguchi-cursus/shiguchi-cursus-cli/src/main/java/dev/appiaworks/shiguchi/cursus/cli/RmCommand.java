package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code cursus rm}: tombstone an archived event (redact its payload). */
@Command(
        name = "rm",
        aliases = {"tombstone"},
        mixinStandardHelpOptions = true,
        description = "Tombstone an archived event: the payload is redacted, the id stays. Admin only.",
        footer = {"", "Example:", "  cursus rm 0000018f --topic orders:123"})
final class RmCommand implements Callable<Integer> {

    @ParentCommand
    Cursus root;

    @Parameters(index = "0", paramLabel = "ID", description = "The event id.")
    String id;

    @Option(
            names = {"-t", "--topic"},
            required = true,
            description = "The event's exact topic.")
    String topic;

    @Override
    public Integer call() {
        root.http().deleteAdmin("/messages/" + Http.enc(id) + Http.query(java.util.List.of(Map.entry("topic", topic))));
        ObjectNode line = Http.JSON.createObjectNode();
        line.put("tombstoned", true);
        line.put("topic", topic);
        line.put("id", id);
        root.out().json(line);
        return 0;
    }
}
