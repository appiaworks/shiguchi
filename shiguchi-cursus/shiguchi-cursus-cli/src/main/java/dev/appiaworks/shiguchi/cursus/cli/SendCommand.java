package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code cursus send}: an event to one connection, or to every connection of a user, wherever it lives. */
@Command(
        name = "send",
        mixinStandardHelpOptions = true,
        description = "Send an event to one connection (--conn) or all of a user's connections (--user). Admin only.",
        footer = {
            "",
            "Examples:",
            "  cursus send --conn 3f9c -d '{\"hello\":\"you\"}' --require-ack",
            "  cursus send --user alice -d @notice.json",
            "",
            "--user finds the user's connections through 'ls conns' (the principal) and sends to each;",
            "one NDJSON line per connection with its delivery outcome."
        })
final class SendCommand implements Callable<Integer> {

    @ParentCommand
    Cursus root;

    @Option(names = "--conn", description = "A connection id (see 'cursus ls conns').")
    String conn;

    @Option(names = "--user", description = "A principal name: every connection it holds.")
    String user;

    @Option(
            names = {"-d", "--data"},
            required = true,
            description = "The payload: JSON text or @file.")
    String data;

    @Option(names = "--require-ack", description = "Wait for the client to acknowledge the event.")
    boolean requireAck;

    @Option(names = "--timeout-ms", description = "How long --require-ack waits (the gateway caps it).")
    Long timeoutMs;

    @Override
    public Integer call() throws IOException {
        if ((conn == null) == (user == null))
            throw new CliException(CliException.USAGE, "give exactly one of --conn or --user");
        String payload = data.startsWith("@") ? read(data.substring(1)) : data;
        try {
            Http.JSON.readTree(payload);
        } catch (IOException e) {
            throw new CliException(CliException.USAGE, "-d is not valid JSON: " + Http.describe(e));
        }
        Http http = root.http();
        Out out = root.out();
        List<String> ids = new ArrayList<>();
        if (conn != null) ids.add(conn);
        else {
            for (JsonNode c : http.getAdmin("/connections").path("connections")) {
                if (user.equals(c.path("principal").asText(null)))
                    ids.add(c.path("id").asText());
            }
            if (ids.isEmpty()) {
                out.info("no connection held by '" + user + "'");
                throw new CliException(CliException.TRANSPORT, "no connection held by '" + user + "'");
            }
        }
        boolean delivered = false;
        for (String id : ids) {
            ObjectNode body = Http.JSON.createObjectNode();
            body.put("data", payload);
            body.put("requireAck", requireAck);
            if (timeoutMs != null) body.put("timeoutMs", timeoutMs);
            JsonNode answer = http.postAdmin("/connections/" + Http.enc(id) + "/send", body);
            out.json(answer);
            for (JsonNode d : answer.path("deliveries")) {
                if ("DELIVERED".equals(d.path("status").asText())) delivered = true;
            }
        }
        return delivered ? 0 : CliException.TRANSPORT;
    }

    private static String read(String name) throws IOException {
        Path path = Path.of(name);
        if (!Files.isRegularFile(path)) throw new CliException(CliException.USAGE, "no such file: " + name);
        return Files.readString(path);
    }
}
