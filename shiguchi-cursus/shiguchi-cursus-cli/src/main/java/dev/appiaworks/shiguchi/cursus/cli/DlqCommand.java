package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code cursus dlq}: list, retry and delete dead-lettered source records. */
@Command(
        name = "dlq",
        mixinStandardHelpOptions = true,
        description = "Dead-letter queue of the push sources. Admin only.",
        subcommands = {DlqCommand.Ls.class, DlqCommand.Retry.class, DlqCommand.Rm.class},
        footer = {
            "",
            "Examples:",
            "  cursus dlq ls --source orders-kafka",
            "  cursus dlq retry 7f3a --topic orders:replay",
            "  cursus dlq rm 7f3a"
        })
final class DlqCommand {

    @ParentCommand
    Cursus root;

    @Command(name = "ls", aliases = "list", mixinStandardHelpOptions = true, description = "List dead letters.")
    static final class Ls implements java.util.concurrent.Callable<Integer> {

        @ParentCommand
        DlqCommand parent;

        @Option(names = "--source", description = "Only this source (or a prefix).")
        String source;

        @Option(names = "--limit", description = "At most this many (default 200).")
        Integer limit;

        @Override
        public Integer call() {
            Cursus root = parent.root;
            String query = Http.query(List.of(
                    Map.entry("q", source == null ? "" : source),
                    Map.entry("limit", limit == null ? "" : limit.toString())));
            root.out()
                    .rows(
                            root.http().getAdmin("/dlq" + query).path("dlq"),
                            "/time",
                            "/id",
                            "/source",
                            "/reason",
                            "/topic",
                            "/bytes");
            return 0;
        }
    }

    @Command(
            name = "retry",
            mixinStandardHelpOptions = true,
            description = "Publish a dead letter again, to its intended topic or --topic.")
    static final class Retry implements java.util.concurrent.Callable<Integer> {

        @ParentCommand
        DlqCommand parent;

        @Parameters(index = "0", paramLabel = "ID", description = "The dead-letter id.")
        String id;

        @Option(
                names = {"-t", "--topic"},
                description = "Publish here instead of the intended topic.")
        String topic;

        @Override
        public Integer call() {
            Cursus root = parent.root;
            ObjectNode body = Http.JSON.createObjectNode();
            if (topic != null && !topic.isBlank()) body.put("topic", topic);
            root.out().json(root.http().postAdmin("/dlq/" + Http.enc(id) + "/retry", body));
            return 0;
        }
    }

    @Command(name = "rm", mixinStandardHelpOptions = true, description = "Delete a dead letter.")
    static final class Rm implements java.util.concurrent.Callable<Integer> {

        @ParentCommand
        DlqCommand parent;

        @Parameters(index = "0", paramLabel = "ID", description = "The dead-letter id.")
        String id;

        @Override
        public Integer call() {
            Cursus root = parent.root;
            root.http().deleteAdmin("/dlq/" + Http.enc(id));
            ObjectNode line = Http.JSON.createObjectNode();
            line.put("deleted", true);
            line.put("id", id);
            root.out().json(line);
            return 0;
        }
    }
}
