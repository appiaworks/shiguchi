package dev.appiaworks.shiguchi.cursus.cli;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code cursus sub}: subscribe to topics and print each event as one NDJSON line. */
@Command(
        name = "sub",
        aliases = {"subscribe", "tail"},
        mixinStandardHelpOptions = true,
        description = "Subscribe to topics; one NDJSON line per event, reconnecting and resuming on its own.",
        footer = {
            "",
            "Examples:",
            "  cursus sub 'orders:123'",
            "  cursus sub orders:123 ticker:btc --after 1759953600000000042 --ws",
            "  cursus sub jobs:q --group workers --member w1 --ack     (gRPC, acknowledges each event)"
        })
final class SubCommand implements Callable<Integer> {

    @ParentCommand
    Cursus root;

    @Parameters(arity = "1..*", paramLabel = "TOPIC", description = "Topics (namespace:name).")
    List<String> topics;

    @Option(names = "--sse", description = "Server-Sent Events (default without --group/--ack).")
    boolean sse;

    @Option(names = "--ws", description = "WebSocket, subprotocol push.v1.json.")
    boolean ws;

    @Option(names = "--grpc", description = "gRPC PushService.Subscribe (default with --group or --ack).")
    boolean grpc;

    @Option(names = "--after", description = "Resume after this event id.")
    String after;

    @Option(names = "--group", description = "Join this consumer group.")
    String group;

    @Option(names = "--member", description = "Member name within the group (default: host-pid).")
    String member;

    @Option(names = "--session", description = "Client session id (session-key: CLIENT namespaces).")
    String session;

    @Option(
            names = "--origin",
            description = "This subscriber's own origin id: events published with the same origin are not delivered"
                    + " to it (echo suppression; ignored with --group).")
    String origin;

    @Option(names = "--qos", description = "AT_MOST_ONCE or AT_LEAST_ONCE (never more than the namespace offers).")
    String qos;

    @Option(names = "--ack", description = "Acknowledge each event after printing it (persistent sessions, groups).")
    boolean ack;

    @Option(
            names = {"-n", "--max-events"},
            description = "Exit 0 after this many events.")
    long maxEvents;

    @Option(names = "--timeout", description = "Exit 0 after this many seconds.")
    long timeoutSeconds;

    @Option(names = "--retries", description = "Give up (exit 1) after this many failed reconnects; default: never.")
    int retries = -1;

    @Option(names = "--backoff-ms", hidden = true, description = "Base reconnect delay in ms.")
    long backoffMs = 500;

    @Override
    public Integer call() throws Exception {
        int chosen = (sse ? 1 : 0) + (ws ? 1 : 0) + (grpc ? 1 : 0);
        if (chosen > 1) throw new CliException(CliException.USAGE, "choose one of --sse, --ws, --grpc");
        boolean useGrpc = grpc || (chosen == 0 && (group != null || ack));
        if (group != null && member == null) member = defaultMember();

        Settings settings = root.settings();
        Lifecycle lifecycle = root.lifecycle();
        Transport transport = useGrpc
                ? GrpcTransport.connect(settings)
                : ws ? new WsTransport(root.http()) : new SseTransport(root.http());
        ScheduledExecutorService timer = null;
        if (timeoutSeconds > 0) {
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cursus-timeout");
                t.setDaemon(true);
                return t;
            });
            timer.schedule(() -> lifecycle.stop(false), timeoutSeconds, TimeUnit.SECONDS);
        }
        try {
            Subscriber subscriber = new Subscriber(
                    transport,
                    settings.token(),
                    root.out(),
                    lifecycle,
                    new Subscriber.Options(maxEvents, retries, backoffMs, 30_000));
            return subscriber.run(new Transport.SubRequest(
                    List.copyOf(topics), after, null, group, member, session, qos, ack, origin));
        } finally {
            if (timer != null) timer.shutdownNow();
            if (transport instanceof AutoCloseable c) c.close();
        }
    }

    private static String defaultMember() {
        String host = System.getenv().getOrDefault("HOSTNAME", System.getenv().getOrDefault("COMPUTERNAME", "cursus"));
        return host + "-" + ProcessHandle.current().pid();
    }
}
